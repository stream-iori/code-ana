package com.anatomist.application;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.framework.JavaFrameworkContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class JavacIndexRuntimeBudgetTest {
    @Test void overlays_consume_the_framework_task_account(@TempDir Path root) {
        var overlay=new AtomicReference<JavaDeclarationOverlay>();
        var backend=(JavaSemanticBackend)java.lang.reflect.Proxy.newProxyInstance(
                JavaSemanticBackend.class.getClassLoader(),new Class<?>[]{JavaSemanticBackend.class},
                (instance,method,args)-> {
                    if(method.getName().equals("setDeclarationOverlay")) { overlay.set((JavaDeclarationOverlay)args[0]);return null; }
                    throw new AssertionError("Unexpected backend operation: "+method);
                });
        var config=new ProjectConfig();config.setLombokMode("ast");
        var resources=new AnalysisContext(new AnalysisContext.Limits(1000,1000,2500,256));
        var context=new JavaFrameworkContext(root,List.of(),config,false,resources,Map.of());
        new JavacIndexRuntime(backend,context);
        assertTrue(((Number)resources.usage().get("payload_bytes")).longValue()>0);
        var empty=new JavaAnalysisContext(".",SourceScope.MAIN,List.of(),Set.of(),List.of(),"");
        assertThrows(HardResourceLimit.class,()->overlay.get().augment(empty,null));
        assertNotNull(resources.usage().get("failure"));
    }
}
