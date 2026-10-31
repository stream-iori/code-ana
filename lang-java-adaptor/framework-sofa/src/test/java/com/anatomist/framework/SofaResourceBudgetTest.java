package com.anatomist.framework;

import com.anatomist.core.*;
import com.anatomist.framework.sofa.SofaXmlAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.anatomist.config.ProjectConfig;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SofaResourceBudgetTest {
    @TempDir Path directory;
    private final String prefix="<beans xmlns:s='http://schema.alipay.com/sofa/schema/service'>";
    private void analyze(String xml,AnalysisContext budget,ExtractionResult result) throws Exception {
        Path source=directory.resolve("sofa.xml");Files.writeString(source,xml);
        new SofaXmlAnalyzer().analyze(new JavaFrameworkContext(directory,List.of(),new ProjectConfig(),true,budget),List.of(new ProjectResource(source,"sofa.xml","spring-xml")),null,result);
    }
    @Test void rootDepthFailureNeverReturnsAnEarlierCompletedRegistration() throws Exception {
        var budget=new AnalysisContext(new AnalysisContext.Limits(100000,1000000,1<<20,8));
        var result=new ExtractionResult(budget);
        String xml=prefix+"<s:reference id='early' interface='p.Api'/>"+"<list>".repeat(20)+"</list>".repeat(20)+"</beans>";
        assertThrows(HardResourceLimit.class,()->analyze(xml,budget,result));
        assertTrue(result.nodes.isEmpty());
        var failure=(Map<?,?>)((Map<?,?>)budget.usage().get("failure")).get("Limit");
        assertEquals("depth",failure.get("resource"));assertEquals(8L,((Number)failure.get("limit")).longValue());
    }
    @Test void inputIsAdmittedBeforeFileReadAndBudgetExhaustionIsSticky() throws Exception {
        var budget=new AnalysisContext(new AnalysisContext.Limits(100000,1000000,4096,256));
        var result=new ExtractionResult(budget);
        String xml=prefix+"<s:reference id='early' interface='p.Api'/></beans>";
        assertThrows(HardResourceLimit.class,()->analyze(xml,budget,result));
        assertTrue(result.nodes.isEmpty());
        long used=((Number)budget.usage().get("payload_bytes")).longValue();assertTrue(used<=4096);
        assertThrows(HardResourceLimit.class,()->analyze(xml,budget,result));
        assertEquals(used,((Number)budget.usage().get("payload_bytes")).longValue());
    }
    @Test void registrationsAndMetadataConsumeTheOriginalAccountAndCancellationStaysFatal() throws Exception {
        var budget=new AnalysisContext(AnalysisContext.Limits.defaults());var result=new ExtractionResult(budget);
        String xml=prefix+"<s:reference id='real' interface='p.Api'/></beans>";
        analyze(xml,budget,result);assertEquals(1,result.nodes.size());
        long used=((Number)budget.usage().get("payload_bytes")).longValue();
        assertTrue(used>xml.length()*8L);
        budget.cancel();assertThrows(FatalAnalysisFailure.class,()->analyze(xml,budget,result));
        assertEquals(used,((Number)budget.usage().get("payload_bytes")).longValue());
    }
}
