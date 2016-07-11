package loghub.configuration;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.antlr.v4.runtime.ANTLRFileStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import loghub.Event;
import loghub.Helpers.ThrowingConsumer;
import loghub.Helpers.ThrowingFunction;
import loghub.Helpers.ThrowingPredicate;
import loghub.Pipeline;
import loghub.Processor;
import loghub.Receiver;
import loghub.RouteLexer;
import loghub.RouteParser;
import loghub.Sender;
import loghub.configuration.ConfigListener.Input;
import loghub.configuration.ConfigListener.ObjectReference;
import loghub.configuration.ConfigListener.Output;
import loghub.processors.AnonymousSubPipeline;
import loghub.processors.NamedSubPipeline;
import loghub.processors.Test;

public class Configuration {

    private static final int DEFAULTQUEUEDEPTH = 100;

    private static final Logger logger = LogManager.getLogger();
    private static final ThrowingFunction<Class<Object>, Object> emptyConstructor = i -> {return i.getConstructor().newInstance();};


    private List<Receiver> receivers;
    private Set<String> inputpipelines = new HashSet<>();
    private Set<String> outputpipelines = new HashSet<>();
    private List<Sender> senders;
    private ClassLoader classLoader = Configuration.class.getClassLoader();

    Configuration() {
    }

    public static Properties parse(String fileName) {
        try {
            Configuration conf = new Configuration();
            ConfigListener listener = conf.antlrparsing(fileName);
            return conf.analyze(listener);
        } catch (IOException e) {
            throw new RuntimeException("Unreadable configuration file '"  + fileName + "': " + e.getMessage(), e);
        } catch (ConfigException e) {
            throw new RuntimeException("Error at " + e.getStartPost() + ": " + e.getMessage(), e);
        }
    }

    private ConfigListener antlrparsing(String fileName) throws IOException{
        CharStream cs = new ANTLRFileStream(fileName);

        //Passing the input to the lexer to create tokens
        RouteLexer lexer = new RouteLexer(cs);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        //Passing the tokens to the parser to create the parse tree.
        RouteParser parser = new RouteParser(tokens);
        parser.removeErrorListeners();
        ConfigErrorListener errListener = new ConfigErrorListener();
        parser.addErrorListener(errListener);
        loghub.RouteParser.ConfigurationContext tree = parser.configuration();
        if(errListener.failed) {
            throw new RuntimeException("parsing failed");
        }

        ConfigListener conf = new ConfigListener();
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(conf, tree);
        return conf;
    }

    private Properties analyze(ConfigListener conf) {

        final Map<String, Object > newProperties = new HashMap<>(conf.properties.size() + Properties.PROPSNAMES.values().length);

        // Resolvers properties found and and it to new properties
        Function<Object, Object> resolve = i -> ((i instanceof ConfigListener.ObjectWrapped) ? ((ConfigListener.ObjectWrapped) i).wrapped : 
            (i instanceof ConfigListener.ObjectReference) ? parseObjectDescription((ConfigListener.ObjectDescription) i, emptyConstructor) : i);
        conf.properties.entrySet().stream().forEach( i-> newProperties.put(i.getKey(), resolve.apply(i.getValue())));

        Map<String, Pipeline> namedPipeLine = new HashMap<>(conf.pipelines.size());

        // Neeeded because conf.properties store a lot of wrapped object, they needs to be resolved
        if(newProperties.containsKey("plugins") && newProperties.get("plugins") instanceof List) {
            try {
                @SuppressWarnings("unchecked")
                List<Object> plugins = (List<Object>) newProperties.remove("plugins");
                classLoader = doClassLoader(plugins);
            } catch (IOException ex) {
                throw new RuntimeException("can't load plugins: " + ex.getMessage(), ex);
            }
        }
        newProperties.put(Properties.PROPSNAMES.CLASSLOADERNAME.toString(), classLoader);

        Set<Pipeline> pipelines = new HashSet<>();
        // Generate all the named pipeline
        for(Entry<String, ConfigListener.PipenodesList> e: conf.pipelines.entrySet()) {
            String name = e.getKey(); 
            Pipeline p = parsePipeline(e.getValue(), name, 0, new AtomicInteger());
            pipelines.add(p);
            namedPipeLine.put(name, p);
        }
        newProperties.put(Properties.PROPSNAMES.PIPELINES.toString(), Collections.unmodifiableSet(pipelines));
        namedPipeLine = Collections.unmodifiableMap(namedPipeLine);
        newProperties.put(Properties.PROPSNAMES.NAMEDPIPELINES.toString(), namedPipeLine);

        //Find the queue depth
        final int queuesDepth = newProperties.containsKey("queueDepth") ? (Integer) newProperties.remove("queueDepth") : DEFAULTQUEUEDEPTH;
        newProperties.put(Properties.PROPSNAMES.QUEUESDEPTH.toString(), queuesDepth);

        BlockingQueue<Event> mainQueue = new ArrayBlockingQueue<Event>(queuesDepth);
        Map<String, BlockingQueue<Event>> outputQueues = new HashMap<>(namedPipeLine.size());
        namedPipeLine.keySet().stream().forEach( i-> outputQueues.put(i, new ArrayBlockingQueue<Event>(queuesDepth)));

        newProperties.put(Properties.PROPSNAMES.FORMATTERS.toString(), conf.formatters);
        newProperties.put(Properties.PROPSNAMES.MAINQUEUE.toString(), mainQueue);
        newProperties.put(Properties.PROPSNAMES.OUTPUTQUEUE.toString(), outputQueues);

        // Fill the receivers list
        receivers = new ArrayList<>();
        for(Input i: conf.inputs) {
            if(i.piperef == null || ! namedPipeLine.containsKey(i.piperef)) {
                throw new RuntimeException("Invalid input, no destination pipeline: " + i);
            }
            for(ConfigListener.ObjectDescription desc: i.receiver) {
                Pipeline p = namedPipeLine.get(i.piperef);
                ThrowingFunction<Class<Receiver>, Receiver> receiverConstructor = r -> {return r.getConstructor(BlockingQueue.class, Pipeline.class).newInstance(mainQueue, p);};
                Receiver r = (Receiver) parseObjectDescription(desc, receiverConstructor);
                receivers.add(r);
            }
            inputpipelines.add(i.piperef);
        }
        inputpipelines = Collections.unmodifiableSet(inputpipelines);
        receivers = Collections.unmodifiableList(receivers);
        newProperties.put(Properties.PROPSNAMES.RECEIVERS.toString(), receivers);

        // Fill the senders list
        senders = new ArrayList<>();
        for(Output o: conf.outputs) {
            if(o.piperef == null || ! namedPipeLine.containsKey(o.piperef)) {
                throw new RuntimeException("Invalid output, no source pipeline: " + o);
            }
            for(ConfigListener.ObjectDescription desc: o.sender) {
                BlockingQueue<Event> out = outputQueues.get(o.piperef);
                ThrowingFunction<Class<Sender>, Sender> senderConstructor = r -> {return r.getConstructor(BlockingQueue.class).newInstance(out);};
                Sender s = (Sender) parseObjectDescription(desc, senderConstructor);
                //logger.debug("sender {} source point will be {}", () -> s, () -> namedPipeLine.get(o.piperef).outQueue);
                senders.add(s);
            }
            outputpipelines.add(o.piperef);
        }
        outputpipelines = Collections.unmodifiableSet(outputpipelines);
        senders = Collections.unmodifiableList(senders);
        newProperties.put(Properties.PROPSNAMES.SENDERS.toString(), senders);

        return new Properties(newProperties);
    }

    private Pipeline parsePipeline(ConfigListener.PipenodesList desc, String currentPipeLineName, int depth, AtomicInteger subPipeCount) {
        List<Processor> allSteps = new ArrayList<Processor>() {
            @Override
            public String toString() {
                StringBuilder buffer = new StringBuilder();
                buffer.append("PipeList(");
                for(Processor i: this) {
                    buffer.append(i);
                    buffer.append(", ");
                }
                buffer.setLength(buffer.length() - 2);
                buffer.append(')');
                return buffer.toString();
            }
        };

        desc.processors.stream().map(i -> getProcessor(i, currentPipeLineName, depth, subPipeCount)).forEach(allSteps::add);
        Pipeline pipe = new Pipeline(allSteps, currentPipeLineName + (depth == 0 ? "" : "$" + subPipeCount.getAndIncrement()), desc.nextPipelineName);
        return pipe;
    }

    private Processor getProcessor(ConfigListener.Pipenode i, String currentPipeLineName, int depth, AtomicInteger subPipeLine) {
        Processor t;
        if(i instanceof ConfigListener.ProcessorInstance) {
            ConfigListener.ProcessorInstance ti = (ConfigListener.ProcessorInstance) i;
            t = (Processor) parseObjectDescription(ti, emptyConstructor, currentPipeLineName, depth, subPipeLine);
        } else if (i instanceof ConfigListener.Test){
            ConfigListener.Test ti = (ConfigListener.Test) i;
            Test test = new Test();
            test.setTest(ti.test);
            test.setThen(getProcessor(ti.True, currentPipeLineName, depth + 1, subPipeLine));
            if(ti.False != null) {
                test.setElse(getProcessor(ti.False, currentPipeLineName, depth + 1, subPipeLine));
            }
            t = test;
        } else if (i instanceof ConfigListener.PipeRef){
            ConfigListener.PipeRef cpr = (ConfigListener.PipeRef) i;
            NamedSubPipeline pr = new NamedSubPipeline();
            pr.setPipeRef(cpr.pipename);
            t = pr;
        } else if (i instanceof ConfigListener.PipenodesList){
            ConfigListener.PipenodesList pl = (ConfigListener.PipenodesList) i;
            Pipeline pipe = parsePipeline(pl, currentPipeLineName, depth + 1, subPipeLine);
            t = new AnonymousSubPipeline();
            ((AnonymousSubPipeline) t).setPipeline(pipe);
            assert false;
        } else {
            throw new RuntimeException("unknown configuration element: " + i);
        }
        return t;
    }

    private <T, C> T parseObjectDescription(ConfigListener.ObjectDescription desc, ThrowingFunction<Class<T>, T> constructor) {
        return parseObjectDescription(desc, constructor, null, 0, null);
    }

    private <T, C> T parseObjectDescription(ConfigListener.ObjectDescription desc, ThrowingFunction<Class<T>, T> constructor, String currentPipeLineName, int depth, AtomicInteger numSubpipe) {
        try {
            @SuppressWarnings("unchecked")
            Class<T> clazz = (Class<T>) classLoader.loadClass(desc.clazz);
            T object = constructor.apply(clazz);

            for(Entry<String, ObjectReference> i: desc.beans.entrySet()) {
                ObjectReference ref = i.getValue();
                Object beanValue;
                if(ref instanceof ConfigListener.ObjectWrapped) {
                    beanValue = ((ConfigListener.ObjectWrapped) ref).wrapped;
                    if(beanValue instanceof ConfigListener.PipenodesList) {
                        assert currentPipeLineName != null;
                        numSubpipe.incrementAndGet();
                        beanValue = parsePipeline((ConfigListener.PipenodesList)beanValue, currentPipeLineName, depth, numSubpipe);
                    }
                } else if (ref instanceof ConfigListener.ObjectDescription) {
                    beanValue = parseObjectDescription((ConfigListener.ObjectDescription) ref, emptyConstructor, currentPipeLineName, depth + 1, numSubpipe);
                } else {
                    throw new ConfigException(String.format("Invalid class '%s': %s", desc.clazz, ref.getClass().getCanonicalName()), desc.ctx.start, desc.ctx.stop);
                }
                try {
                    BeansManager.beanSetter(object, i.getKey(), beanValue);
                } catch (InvocationTargetException ex) {
                    throw new ConfigException(String.format("Invalid bean '%s.%s': %s", desc.clazz, i.getKey(), ex.getCause()), desc.ctx.start, desc.ctx.stop, (Exception) ex.getCause());
                }
            }
            return object;
        } catch (ClassNotFoundException e) {
            throw new ConfigException(String.format("Unknown class '%s'", desc.clazz), desc.ctx.start, desc.ctx.stop);
        } catch (ConfigException e) {
            throw e;
        } catch (RuntimeException | ExceptionInInitializerError e) {
            Exception rootCause = ( Exception) e;
            if(rootCause.getCause() instanceof InvocationTargetException) {
                rootCause = (InvocationTargetException) rootCause.getCause();
            }
            logger.throwing(rootCause);
            throw new ConfigException(String.format("Invalid class '%s': %s", desc.clazz, rootCause), desc.ctx.start, desc.ctx.stop);
        }
    }

    ClassLoader doClassLoader(List<Object> pathElements) throws IOException {

        final Collection<URL> urls = new ArrayList<URL>();

        // Needed for all the lambda that throws exception
        ThrowingPredicate<Path> filterReadable = i -> ! Files.isHidden(i);
        ThrowingConsumer<Path> toUrl = i -> urls.add(i.toUri().toURL());

        pathElements.stream()
        .map(i -> Paths.get(i.toString()))
        .filter(i -> Files.isReadable(i))
        .filter(filterReadable)
        .filter(i -> (Files.isRegularFile(i) && i.toString().endsWith(".jar")) || Files.isDirectory(i))
        .forEach( i-> {
            toUrl.accept(i);
            if(Files.isDirectory(i)) {
                try {
                    Files.list(i)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".jar"))
                    .forEach(toUrl);
                } catch (Exception e) {
                    new RuntimeException(e);
                }
            }
        });

        //Add myself to class loader, so the custom class loader is used in priority
        Path myself = locateResourcefile("loghub");
        if(myself.endsWith("loghub")) {
            myself = myself.getParent();
        }
        urls.add(myself.toUri().toURL());

        return new URLClassLoader(urls.toArray(new URL[] {}), getClass().getClassLoader()) {
            @Override
            public String toString() {
                return "Loghub's class loader";
            }
        };
    }

    private Path locateResourcefile(String ressource) {
        try {
            URL url = getClass().getClassLoader().getResource(ressource);
            String protocol = url.getProtocol();
            String file = null;
            switch(protocol) {
            case "file":
                file = url.getFile();
                break;
            case "jar":
                url = new URL(url.getFile().replaceFirst("!.*", ""));
                file = url.getFile();
                break;
            default:
                throw new IllegalArgumentException("unmanaged ressource URL");
            }
            return Paths.get(file);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("can't locate the ressource file path", e);
        }
    }

    public Collection<Receiver> getReceivers() {
        return Collections.unmodifiableList(receivers);
    }

    public Collection<Sender> getSenders() {
        return Collections.unmodifiableList(senders);
    }
}
