package com.anatomist.framework.lombok;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.AnalysisContext;
import com.anatomist.core.HardResourceLimit;
import com.anatomist.framework.JavaFrameworkContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class LombokFrameworkPluginTest {
    @Test void disabled_module_registers_no_lombok_facts(@TempDir Path root) {
        var registry=new LombokFrameworkPlugin().detachedAnalyzers(new JavaFrameworkContext(root,List.of(),new ProjectConfig(),false));
        assertTrue(registry.declarations().isEmpty());
        assertTrue(registry.callSiteEvidenceProviders().isEmpty());
    }
    @Test void captured_configuration_and_task_account_reach_both_extensions(@TempDir Path root) throws Exception {
        var config=new ProjectConfig();config.setLombokMode("ast");
        var resources=new AnalysisContext(AnalysisContext.Limits.defaults());
        var captured=Map.of(root.resolve("lombok.config"),"lombok.accessors.fluent = true\n");
        var context=new JavaFrameworkContext(root,List.of(),config,false,resources,captured);
        var plugin=new LombokFrameworkPlugin();
        var registry=plugin.detachedAnalyzers(context);
        assertEquals(List.of("lombok-ast"),registry.declarations().stream().map(e->e.id()).toList());
        assertEquals(List.of("lombok-call-site"),registry.callSiteEvidenceProviders().stream().map(e->e.id()).toList());
        assertTrue(((Number)resources.usage().get("payload_bytes")).longValue()>0);
        Files.writeString(root.resolve("lombok.config"),"live content must not replace captured input");
        registry.overlays(resources).verifyInputs();
        assertEquals(registry.fingerprint(),plugin.detachedAnalyzers(context).fingerprint());
    }
    @Test void registration_admits_inventory_before_iteration(@TempDir Path root) {
        var config=new ProjectConfig();config.setLombokMode("ast");
        var resources=new AnalysisContext(new AnalysisContext.Limits(100,100,100,256));
        Map<Path,String> untouched=new AbstractMap<>() {
            @Override public int size(){return 1;}
            @Override public Set<Entry<Path,String>> entrySet(){throw new AssertionError("copied before admission");}
        };
        var context=new JavaFrameworkContext(root,List.of(),config,false,resources,untouched);
        assertThrows(HardResourceLimit.class,()->new LombokFrameworkPlugin().detachedAnalyzers(context));
        assertNotNull(resources.usage().get("failure"));
    }
}
