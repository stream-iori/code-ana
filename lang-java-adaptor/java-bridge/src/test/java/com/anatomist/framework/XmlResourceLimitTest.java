package com.anatomist.framework;

import com.anatomist.core.HardResourceLimit;
import com.anatomist.core.SpringBeanParser;
import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmlResourceLimitTest {
    @Test void platformLimitIsFatalAndDoesNotReturnEarlierBean() {
        String previous = System.getProperty("jdk.xml.maxElementDepth");
        System.setProperty("jdk.xml.maxElementDepth", "100");
        try {
            String xml = "<beans><bean id='early' class='A'/>" + "<list>".repeat(110) + "</list>".repeat(110) + "</beans>";
            var failure = assertThrows(HardResourceLimit.class,
                    () -> new SpringBeanParser().parse(new StringReader(xml)));
            assertNotNull(failure.getCause());
        } finally {
            if (previous == null) System.clearProperty("jdk.xml.maxElementDepth");
            else System.setProperty("jdk.xml.maxElementDepth", previous);
        }
    }
    @Test void sharedFileBudgetIsMonotonicAndUsesUtf8() {
        var budget = new com.anatomist.core.XmlResourceBudget();
        budget.text("中😀");
        budget.bytes((64L << 20) - 7);
        assertThrows(HardResourceLimit.class, () -> budget.bytes(1));
    }
    @Test void applicationDepthLimitIsNotSwallowed() {
        String xml = "<beans>" + "<list>".repeat(256) + "</list>".repeat(256) + "</beans>";
        assertThrows(HardResourceLimit.class, () -> new SpringBeanParser().parse(new StringReader(xml)));
    }
    @Test void wideInputStopsBeforeReturningPartialBeans() {
        String xml = "<beans><bean id='first' class='A'/>" + "<bean/>".repeat(100000) + "</beans>";
        assertThrows(HardResourceLimit.class, () -> new SpringBeanParser().parse(new StringReader(xml)));
    }
    @Test void wrappedAndCyclicCausesTerminate() {
        HardResourceLimit limit = new HardResourceLimit("limit");
        assertSame(limit, assertThrows(HardResourceLimit.class,
                () -> HardResourceLimit.rethrow(new RuntimeException(limit))));
        RuntimeException a = new RuntimeException(), b = new RuntimeException(a);
        a.initCause(b);
        assertDoesNotThrow(() -> HardResourceLimit.rethrow(a));
    }
    @Test void ordinaryMalformedXmlKeepsRecovery() {
        assertDoesNotThrow(() -> new SpringBeanParser().parse(new StringReader("<beans><bean")));
    }

    @org.junit.jupiter.api.Test
    void distinct_xml_files_cannot_reset_the_task_quota() {
        var context=new com.anatomist.core.AnalysisContext(new com.anatomist.core.AnalysisContext.Limits(1000,4,100000,256));
        var parser=new com.anatomist.core.SpringBeanParser(context);
        parser.parse(new java.io.StringReader("<beans/>"));
        org.junit.jupiter.api.Assertions.assertThrows(com.anatomist.core.FatalAnalysisFailure.class,()->parser.parse(new java.io.StringReader("<beans/>")));
        org.junit.jupiter.api.Assertions.assertEquals(4L,context.usage().get("nodes"));
    }

    @org.junit.jupiter.api.Test
    void stricter_platform_depth_is_recorded_without_raising_the_platform_limit() {
        var context=new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults());
        var parser=new com.anatomist.core.SpringBeanParser(context);
        org.junit.jupiter.api.Assertions.assertThrows(com.anatomist.core.FatalAnalysisFailure.class,()->parser.parse(new java.io.StringReader("<beans>"+"<list>".repeat(300)+"</list>".repeat(300)+"</beans>")));
        Object failure=context.usage().get("failure");
        org.junit.jupiter.api.Assertions.assertInstanceOf(java.util.Map.class,failure);
        var limit=(java.util.Map<?,?>)((java.util.Map<?,?>)failure).get("Limit");
        org.junit.jupiter.api.Assertions.assertEquals("depth",limit.get("resource"));
        org.junit.jupiter.api.Assertions.assertTrue(((Number)limit.get("limit")).longValue()<=256);
        org.junit.jupiter.api.Assertions.assertEquals(1L,limit.get("requested"));
    }

    @org.junit.jupiter.api.Test
    void local_xml_payload_rejection_does_not_charge_root_nodes_or_work() {
        var root=new com.anatomist.core.AnalysisContext(com.anatomist.core.AnalysisContext.Limits.defaults());
        var budget=new com.anatomist.core.XmlResourceBudget(root);
        var scope=budget.analysisContext();
        budget.bytes(scope.limits().maxPayloadBytes()-(long)scope.scopeUsage().get("payload_bytes"));
        var before=root.usage();
        org.junit.jupiter.api.Assertions.assertThrows(com.anatomist.core.HardResourceLimit.class,()->budget.json("x"));
        for(String dimension:java.util.List.of("work","nodes","payload_bytes"))org.junit.jupiter.api.Assertions.assertEquals(before.get(dimension),root.usage().get(dimension));
    }
}
