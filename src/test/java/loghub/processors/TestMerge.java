package loghub.processors;

import java.io.IOException;
import java.io.StringReader;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import loghub.Event;
import loghub.LogUtils;
import loghub.ProcessorException;
import loghub.Tools;
import loghub.configuration.Configuration;
import loghub.configuration.Properties;

public class TestMerge {

    private static Logger logger;

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub.processors.Merge");
    }

    @Test()
    public void test() throws Throwable {
        String conf= "pipeline[main] { merge {index: \"${e%s}\", seeds: {\"a\": 0, \"b\": \",\", \"e\": null, \"c\": [], \"count\": 'c'}, doFire: [a] >= 2, onFire: $main, forward: false}}";

        Properties p = Configuration.parse(new StringReader(conf));
        Assert.assertTrue(p.pipelines.stream().allMatch(i-> i.configure(p)));
        Merge m = (Merge) p.namedPipeLine.get("main").processors.stream().findFirst().get();
        try {
            m.process(Event.emptyEvent());
        } catch (ProcessorException.DroppedEventException e1) {
        }
        Event e = Event.emptyEvent();
        e.put("a", 1);
        e.put("b", 2);
        e.put("c", 3);
        e.put("d", 4);
        e.put("e", "5");
        boolean dropped = false;
        try {
            m.process(e);
        } catch (ProcessorException.DroppedEventException e1) {
            dropped = true;
        }
        try {
            m.process(e);
        } catch (ProcessorException.DroppedEventException e1) {
            dropped = true;
        }
        e = p.mainQueue.remove();
        Assert.assertTrue(dropped);
        Assert.assertTrue(p.mainQueue.isEmpty());
        Assert.assertEquals(String.class, e.get("b").getClass());
        Assert.assertEquals("2,2", e.get("b"));
        Assert.assertEquals(null, e.get("e"));
    }

    @Test(timeout=5000)
    public void testTimeout() throws Throwable {
        String conf= "pipeline[main] { merge {index: \"${e%s}\", seeds: {\"a\": 0, \"b\": \",\", \"e\": 'c', \"c\": []}, onTimeout: $main, timeout: 1 }}";

        Properties p = Configuration.parse(new StringReader(conf));
        Assert.assertTrue(p.pipelines.stream().allMatch(i-> i.configure(p)));
        Merge m = (Merge) p.namedPipeLine.get("main").processors.stream().findFirst().get();
        Event e = Event.emptyEvent();
        e.put("a", 1);
        e.put("b", 2);
        e.put("c", 3);
        e.put("d", 4);
        e.put("e", "5");
        try {
            m.process(e);
        } catch (ProcessorException.DroppedEventException e1) {
        }
        Assert.assertTrue(p.mainQueue.isEmpty());
        Thread.sleep(2000);
        e = p.mainQueue.element();
        Assert.assertEquals(String.class, e.get("b").getClass());
        Assert.assertEquals("2", e.get("b"));
        Assert.assertEquals(1L, e.get("e"));
    }

}