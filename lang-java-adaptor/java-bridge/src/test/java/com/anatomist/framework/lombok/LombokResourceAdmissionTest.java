package com.anatomist.framework.lombok;

import com.anatomist.core.*;
import com.anatomist.framework.JavaModelExtensions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class LombokResourceAdmissionTest {
    @Test void generated_declarations_and_ids_match_diagnostic_path(@TempDir Path root) throws Exception {
        var fixture=LombokTestSupport.analyze(root,"@lombok.Data @lombok.Builder class User { String name; int count; }",false);
        var original=JavaFactsTestSupport.analyze(root,"Models.java","@lombok.Data @lombok.Builder class User { String name; int count; }",fixture.context().classpath());
        var resources=new AnalysisContext(AnalysisContext.Limits.defaults());
        var extension=new LombokJavaModelExtension(root,false,Map.of(),resources);
        var expected=new LombokJavaModelExtension(root,false,Map.of()).augment(original,null);
        var actual=new JavaModelExtensions(List.of(extension),resources).augment(original,null);
        assertEquals(expected.units().getFirst().declarationInventory(),actual.units().getFirst().declarationInventory());
        assertEquals(expected.units().getFirst().types(),actual.units().getFirst().types());
        assertEquals(expected.units().getFirst().extensions(),actual.units().getFirst().extensions());
        assertTrue((long)resources.usage().get("nodes")>0);
        assertTrue((long)resources.usage().get("payload_bytes")>0);
        assertNull(resources.usage().get("failure"));
    }
    @Test void exhausted_overlay_never_returns_partial_inventory(@TempDir Path root) throws Exception {
        var fixture=LombokTestSupport.analyze(root,"@lombok.Data class User { String name; }",false);
        var original=JavaFactsTestSupport.analyze(root,"Models.java","@lombok.Data class User { String name; }",fixture.context().classpath());
        var resources=new AnalysisContext(new AnalysisContext.Limits(100000,100000,4000,256));
        var extension=new LombokJavaModelExtension(root,false,Map.of());
        assertThrows(HardResourceLimit.class,()->new JavaModelExtensions(List.of(extension),resources).augment(original,null));
        assertNotNull(resources.usage().get("failure"));
        assertTrue(original.units().getFirst().extensions().generated().isEmpty());
    }
    @Test void configuration_inventory_and_repeated_layers_use_one_root(@TempDir Path root) {
        var configs=Map.of(root.resolve("lombok.config"),"lombok.getter.noIsPrefix = true\n");
        var resources=new AnalysisContext(AnalysisContext.Limits.defaults());
        var scope=new LombokConfigurationScope(root,configs,resources);
        long snapshot=(long)resources.usage().get("payload_bytes");
        var first=scope.forSource(root.resolve("A.java"),resources);
        long once=(long)resources.usage().get("payload_bytes");
        assertEquals(first,scope.forSource(root.resolve("B.java"),resources));
        assertTrue(once>snapshot);
        assertTrue((long)resources.usage().get("payload_bytes")>once);
        var denied=new AnalysisContext(new AnalysisContext.Limits(10000,10000,100,256));
        assertThrows(HardResourceLimit.class,()->new LombokConfigurationScope(root,configs,denied));
        assertEquals(0L,denied.usage().get("payload_bytes"));
    }
}
