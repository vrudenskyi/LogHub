package loghub.processors;

import loghub.Event;
import loghub.Pipeline;
import loghub.Processor;
import loghub.ProcessorException;
import loghub.SubPipeline;
import loghub.configuration.Properties;

public class AnonymousSubPipeline extends Processor implements SubPipeline {

    private Pipeline pipeline;

    @Override
    public String getName() {
        return null;
    }

    /**
     * @return the pipeline
     */
    public Pipeline getPipeline() {
        return pipeline;
    }

    /**
     * @param pipeline the pipeline to set
     */
    public void setPipeline(Pipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void process(Event event) throws ProcessorException {
        assert false;
    }

    @Override
    public boolean configure(Properties properties) {
        return pipeline.processors.stream().allMatch(i -> i.configure(properties)) &&
                super.configure(properties);
    }

}
