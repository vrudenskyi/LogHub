package loghub;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.util.concurrent.Future;
import loghub.PausedEvent.Builder;
import loghub.Stats.PipelineStat;
import loghub.configuration.Properties;
import loghub.configuration.TestEventProcessing;
import loghub.processors.Drop;
import loghub.processors.Forker;
import loghub.processors.Forwarder;
import loghub.processors.FuturProcessor;
import loghub.processors.UnwrapEvent;
import loghub.processors.WrapEvent;

public class EventsProcessor extends Thread {

    enum ProcessingStatus {
        CONTINUE,
        PAUSED,
        DROPED,
        FAILED
    }

    private static final Logger logger = LogManager.getLogger();
    private static final AtomicInteger id = new AtomicInteger();

    private final BlockingQueue<Event> inQueue;
    private final Map<String, BlockingQueue<Event>> outQueues;
    private final Map<String,Pipeline> namedPipelines;
    private final int maxSteps;
    private final EventsRepository<Future<?>> evrepo;

    public EventsProcessor(BlockingQueue<Event> inQueue, Map<String, BlockingQueue<Event>> outQueues, Map<String,Pipeline> namedPipelines, int maxSteps, EventsRepository<Future<?>> evrepo) {
        this.inQueue = inQueue;
        this.outQueues = outQueues;
        this.namedPipelines = namedPipelines;
        this.maxSteps = maxSteps;
        this.evrepo = evrepo;
        setName("EventsProcessor/" + id.getAndIncrement());
        setDaemon(false);
    }

    @Override
    public void run() {
        while (! isInterrupted()) {
            Event event = null;
            try {
                event = inQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            { // Needed because eventtemp must be final
                final Event eventtemp  = event;
                logger.trace("received {} in {}", () -> eventtemp, () -> eventtemp.getCurrentPipeline());
            }
            Processor processor = event.next();
            while (processor != null) {
                logger.trace("processing with {}", processor);
                if (processor instanceof WrapEvent) {
                    event = new EventWrapper(event, processor.getPathArray());
                } else if (processor instanceof UnwrapEvent) {
                    event = event.unwrap();
                } else {
                    ProcessingStatus processingstatus = process(event, processor);
                    if (processingstatus != ProcessingStatus.CONTINUE) {
                        // Processing status was non null, so the event will not be processed any more
                        // But it's needed to check why.
                        switch (processingstatus) {
                        case DROPED: {
                            //It was a drop action
                            logger.debug("Dropped event {}", event);
                            event.doMetric(PipelineStat.INFLIGHTDOWN);
                            event.drop();
                            break;
                        }
                        case FAILED: {
                            //Processing failed critically (with an exception) and no recovery was attempted
                            logger.debug("Failed event {}", event);
                            event.doMetric(PipelineStat.INFLIGHTDOWN);
                            event.end();
                            break;
                        }
                        default:
                            // Non fatal processing interruption
                            break;
                        }
                        event = null;
                        break;
                    }
                }
                processor = event.next();
                // If next processor is null, refill the event
                while (processor == null && event.getNextPipeline() != null) {
                    logger.trace("next processor is {}", processor);
                    // Send to another pipeline, loop in the main processing queue
                    Pipeline next = namedPipelines.get(event.getNextPipeline());
                    event.refill(next);
                    processor = event.next();
                }
            }
            logger.trace("event is now {}", event);
            // Processing of the event is finished, what to do next with it ?
            // Detect if will send to another pipeline, or just wait for a sender to take it
            if (event != null) {
                if (event.isTest()) {
                    // A test event, it will not be send an output queue
                    // Checked after pipeline forwarding, but before output sending
                    TestEventProcessing.log(event);
                    event.end();
                } else if (event.getCurrentPipeline() != null && outQueues.containsKey(event.getCurrentPipeline())){
                    // Put in the output queue, where the wanting output will come to take it
                    try {
                        outQueues.get(event.getCurrentPipeline()).put(event);
                    } catch (InterruptedException e) {
                        event.doMetric(PipelineStat.BLOCKOUT);
                        event.end();
                        Thread.currentThread().interrupt();
                    }
                } else if (event.getCurrentPipeline() != null && ! outQueues.containsKey(event.getCurrentPipeline())){
                    Stats.newUnhandledException(new IllegalArgumentException("No sender consumming pipeline " + event.getCurrentPipeline()));
                    logger.debug("No sender using pipeline {} for event {}", event.getCurrentPipeline(), event);
                    Properties.metrics.meter("Allevents.failed").mark();
                    event.end();
                } else {
                    Stats.newUnhandledException(new IllegalStateException("Invalid end state for event, no pipeline"));
                    logger.debug("Invalid end state for event {}", event);
                    Properties.metrics.meter("Allevents.failed").mark();
                    event.end();
                }
            }
        }
    }

    ProcessingStatus process(Event e, Processor p) {
        ProcessingStatus status = null;
        if (p instanceof Forker) {
            if (((Forker) p).fork(e)) {
                status = ProcessingStatus.CONTINUE;
            } else {
                status = ProcessingStatus.FAILED;
            }
        } else if (p instanceof Forwarder) {
            ((Forwarder) p).forward(e);
            status = ProcessingStatus.CONTINUE;
        } else if (p instanceof Drop) {
            status = ProcessingStatus.DROPED;
            e.doMetric(Stats.PipelineStat.DROP);
        } else if (e.processingDone() > maxSteps) {
            logger.error("Too much steps for an event in pipeline. Done {} steps, still {} left, throwing away", () -> e.processingDone(), () -> e.processingLeft());
            logger.debug("Thrown event: {}", e);
            e.doMetric(Stats.PipelineStat.LOOPOVERFLOW);
            status = ProcessingStatus.FAILED;
        } else {
            try {
                boolean success = false;
                if (p.isprocessNeeded(e)) {
                    success = e.process(p);
                }
                // After processing, check the failures and success processors
                Processor failureProcessor = p.getFailure();
                Processor successProcessor = p.getSuccess();
                if (success && successProcessor != null) {
                    e.insertProcessor(successProcessor);
                } else if (! success && failureProcessor != null) {
                    e.insertProcessor(failureProcessor);
                }
                status = ProcessingStatus.CONTINUE;
            } catch (ProcessorException.PausedEventException ex) {
                status = ProcessingStatus.PAUSED;
                // First check if the process will be able to manage the call back
                if (p instanceof AsyncProcessor) {
                    // The event to pause might be a transformation of the original event.
                    Event topause = ex.getEvent();
                    AsyncProcessor<?> ap = (AsyncProcessor<?>) p;
                    // A paused event was catch, create a custom FuturProcess for it that will be awaken when event come back
                    Future<?> future = ex.getFuture();
                    // Compilation fails if done in a one-liner
                    Builder<Future<?>> builder = PausedEvent.builder(topause, future);
                    PausedEvent<Future<?>> paused = builder
                                    .onSuccess(p.getSuccess())
                                    .onFailure(p.getFailure())
                                    .onException(p.getException())
                                    .expiration(ap.getTimeout(), TimeUnit.SECONDS)
                                    .build();
                    //Create the processor that will process the call back processor
                    @SuppressWarnings({ "rawtypes", "unchecked"})
                    FuturProcessor<?> pauser = new FuturProcessor(future, paused, ap);
                    ex.getEvent().insertProcessor(pauser);

                    //Store the callback informations
                    future.addListener(i -> {
                        if (! paused.isDone()) {
                            inQueue.put(topause);
                        }
                        evrepo.cancel(future);
                    });
                    evrepo.pause(paused);
                }
            } catch (ProcessorException.DroppedEventException ex) {
                status = ProcessingStatus.DROPED;
                e.doMetric(Stats.PipelineStat.DROP);
            } catch (IgnoredEventException ex) {
                // A do nothing event
                status = ProcessingStatus.CONTINUE;
            } catch (ProcessorException | UncheckedProcessorException ex) {
                logger.debug("got a processing exception");
                logger.catching(Level.DEBUG, ex);
                Processor exceptionProcessor = p.getException();
                if (exceptionProcessor != null) {
                    e.insertProcessor(exceptionProcessor);
                    status = ProcessingStatus.CONTINUE;
                } else {
                    e.doMetric(Stats.PipelineStat.FAILURE, ex);
                    status = ProcessingStatus.FAILED;
                }
            } catch (Throwable ex) {
                // We received a fatal exception
                // Can't do nothing but die
                if (Helpers.isFatal(ex)) {
                    throw ex;
                }
                e.doMetric(Stats.PipelineStat.EXCEPTION, ex);
                logger.error("failed to transform event {} with unmanaged error {}", e, Helpers.resolveThrowableException(ex));
                logger.catching(Level.DEBUG, ex);
                status = ProcessingStatus.FAILED;
            }
        }
        return status;
    }

    public void stopProcessing() {
        interrupt();
    }

}
