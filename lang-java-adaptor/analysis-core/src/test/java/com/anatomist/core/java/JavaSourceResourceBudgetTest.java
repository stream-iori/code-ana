package com.anatomist.core.java;

import com.anatomist.core.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceResourceBudgetTest {
    private static long used(AnalysisContext budget,String field){return ((Number)budget.usage().get(field)).longValue();}
    @Test void substringIsAdmittedBeforeCopyAndRejectedChargeIsAtomic() {
        String text="a\n".repeat(400);
        var probe=new AnalysisContext(AnalysisContext.Limits.defaults());
        new JavaSource(Path.of("A.java"),text,ContentHash.sha256(text),probe);
        long construction=used(probe,"payload_bytes");
        var budget=new AnalysisContext(new AnalysisContext.Limits(100000,1000000,construction+100,256));
        var source=new JavaSource(Path.of("A.java"),text,ContentHash.sha256(text),budget);
        long work=used(budget,"work"),nodes=used(budget,"nodes");
        assertThrows(HardResourceLimit.class,()->source.slice(new SourceSpan(0,100)));
        assertEquals(construction,used(budget,"payload_bytes"));
        assertEquals(work,used(budget,"work"));assertEquals(nodes,used(budget,"nodes"));
        assertThrows(HardResourceLimit.class,()->source.position(0));
    }
    @Test void coordinateLookupsUseTheSameWorkLedgerAndCannotContinueAfterExhaustion() {
        String text="a\nb\nc";var budget=new AnalysisContext(AnalysisContext.Limits.defaults());
        var source=new JavaSource(Path.of("A.java"),text,ContentHash.sha256(text),budget);
        budget.charge(budget.limits().maxWork()-used(budget,"work")-1,0,0);
        long bytes=used(budget,"payload_bytes");
        assertThrows(HardResourceLimit.class,()->source.position(2));
        assertEquals(bytes,used(budget,"payload_bytes"));
    }
    @Test void meteringKeepsCrLfAndUtf16CoordinatesAndSliceBytes() {
        String text="字\r\n🙂x";var budget=new AnalysisContext(AnalysisContext.Limits.defaults());
        var source=new JavaSource(Path.of("A.java"),text,ContentHash.sha256(text),budget);
        assertEquals(new JavaSource.Position(2,3),source.position(5));
        assertEquals("🙂",source.slice(new SourceSpan(3,5)));
        assertTrue(used(budget,"payload_bytes")>text.length());
    }
}
