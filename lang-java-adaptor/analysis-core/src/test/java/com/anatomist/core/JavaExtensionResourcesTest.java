package com.anatomist.core;

import com.anatomist.core.java.*;
import com.anatomist.framework.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class JavaExtensionResourcesTest {
    private static JavaExtensionData freeze(Map<String,Object> data, AnalysisContext root) {
        return new JavaExtensionData(Map.of(),Map.of("A",data),List.of(),Map.of(),root);
    }
    @Test void rejects_before_iterating_or_copying_the_metadata() {
        var root=new AnalysisContext(new AnalysisContext.Limits(100,100,100,256));
        var untouched=new AbstractMap<String,Object>() {
            @Override public int size(){return 1;}
            @Override public Set<Entry<String,Object>> entrySet(){throw new AssertionError("copied before admission");}
        };
        assertThrows(HardResourceLimit.class,()->freeze(untouched,root));
        assertEquals(0L,root.usage().get("payload_bytes"));
        assertNotNull(root.usage().get("failure"));
    }
    @Test void nested_snapshots_share_cumulative_usage_and_are_immutable() {
        var root=new AnalysisContext(AnalysisContext.Limits.defaults());
        var values=new ArrayList<Object>(List.of(1L,true,"text"));
        var metadata=new LinkedHashMap<String,Object>();metadata.put("nested",values);
        var frozen=freeze(metadata,root);long first=(long)root.usage().get("work");
        values.clear();metadata.clear();
        assertEquals(List.of(1L,true,"text"),frozen.declarationMetadata().get("A").get("nested"));
        assertThrows(UnsupportedOperationException.class,()->frozen.declarationMetadata().get("A").put("other",1));
        freeze(Map.of("second",1),root);
        assertTrue((long)root.usage().get("work")>first);
    }
    @Test void cycles_and_local_limits_poison_the_shared_root() {
        var root=new AnalysisContext(AnalysisContext.Limits.defaults());
        var cycle=new ArrayList<Object>();cycle.add(cycle);
        assertThrows(HardResourceLimit.class,()->freeze(Map.of("cycle",cycle),root));
        assertNotNull(root.usage().get("failure"));
        assertThrows(HardResourceLimit.class,()->freeze(Map.of("healthy",1),root));
    }
    @Test void codec_node_limit_is_hard_and_cumulative_within_one_snapshot() {
        var root=new AnalysisContext(AnalysisContext.Limits.defaults());
        var values=Collections.nCopies(50001,1);
        assertThrows(HardResourceLimit.class,()->freeze(Map.of("a",values,"b",values),root));
        assertNotNull(root.usage().get("failure"));
    }
    @Test void call_evidence_uses_the_same_budget_before_freezing() {
        var root=new AnalysisContext(new AnalysisContext.Limits(100,100,100,256));
        assertThrows(HardResourceLimit.class,()->new JavaCallSiteEvidenceProvider.Evidence("lombok_usage",Map.of("a",1),"count",root));
        assertNotNull(root.usage().get("failure"));
    }
    @Test void production_requires_budget_callback_at_compile_time(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        String prefix="import com.anatomist.framework.*; import com.anatomist.core.java.*; import com.anatomist.core.asmsolver.*; ";
        var legacy=tmp.resolve("Legacy.java");
        java.nio.file.Files.writeString(legacy,prefix+"public final class Legacy implements JavaModelExtension { public String id(){return \"legacy\";} public JavaAnalysisContext augment(JavaAnalysisContext c,JavaBinaryRepository b){return c;} }");
        var output=tmp.resolve("classes");java.nio.file.Files.createDirectory(output);
        var options=List.of("-proc:none","-classpath",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"-d",output.toString());
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)) {
            assertFalse(compiler.getTask(null,files,diagnostics,options,null,files.getJavaFileObjects(legacy.toFile())).call());
            assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d->d.getCode().contains("does.not.override.abstract")),diagnostics.getDiagnostics().toString());
            var aware=tmp.resolve("Aware.java");
            java.nio.file.Files.writeString(aware,prefix+"public final class Aware implements JavaModelExtension { public String id(){return \"aware\";} public JavaAnalysisContext augment(JavaAnalysisContext c,JavaBinaryRepository b,com.anatomist.core.AnalysisContext resources){resources.charge(1,0,0);return c;} }");
            assertTrue(compiler.getTask(null,files,null,options,null,files.getJavaFileObjects(aware.toFile())).call());
        }
        assertThrows(NullPointerException.class,()->new JavaModelExtensions(List.of(),null));
    }
    @Test void explicit_callback_gets_root_and_hard_failure_escapes_recovery() {
        var root=new AnalysisContext(AnalysisContext.Limits.defaults());
        JavaModelExtension aware=new JavaModelExtension(){
            public String id(){return "aware";}
            public JavaAnalysisContext augment(JavaAnalysisContext context,com.anatomist.core.asmsolver.JavaBinaryRepository binaries){throw new AssertionError("legacy callback invoked");}
            public JavaAnalysisContext augment(JavaAnalysisContext context,com.anatomist.core.asmsolver.JavaBinaryRepository binaries,AnalysisContext actual){
                assertSame(root,actual);
                freeze(Map.of("cycle",Collections.nCopies(100001,1)),actual);
                return context;
            }
        };
        assertThrows(HardResourceLimit.class,()->new JavaModelExtensions(List.of(aware),root).augment(empty(),null));
        assertNotNull(root.usage().get("failure"));
    }
    @Test void live_type_name_lookup_keeps_canonical_nested_names_and_cycles() {
        var nested=new JavaType(JavaType.Kind.DECLARED,"p.Outer.Inner","",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of(),List.of(),"p.Outer$Inner");
        var array=new JavaType(JavaType.Kind.ARRAY,"","",-1,List.of(),List.of(),-1,0,List.of(),-1,List.of());
        var wildcard=new JavaType(JavaType.Kind.WILDCARD,"","",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of());
        var cyclic=new JavaType(JavaType.Kind.TYPEVAR,"T","T",-1,List.of(),List.of(3),-1,-1,List.of(),-1,List.of());
        var entries=List.of(nested,array,wildcard,cyclic);
        var graph=new JavaTypes(entries);
        var root=new AnalysisContext(AnalysisContext.Limits.defaults());
        for(int i=0;i<entries.size();i++)assertEquals(JavaTypeNames.erased(graph,i),JavaTypeNames.erased(entries,i,root));
        assertEquals("p.Outer.Inner",JavaTypeNames.erased(entries,0,root));
        assertEquals("p.Outer.Inner[]",JavaTypeNames.erased(entries,1,root));
    }
    @Test void live_type_lookup_does_not_snapshot_unrelated_entries() {
        var one=new JavaType(JavaType.Kind.INT,"int","int",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of());
        var entries=new AbstractList<JavaType>() {
            public int size(){return 1000000;}
            public JavaType get(int index){if(index!=0)throw new AssertionError("unrelated type visited");return one;}
        };
        var root=new AnalysisContext(new AnalysisContext.Limits(2,2,1024,256));
        assertEquals("int",JavaTypeNames.erased(entries,0,root));
        assertEquals(1L,root.usage().get("work"));
    }
    private static JavaAnalysisContext empty(){return new JavaAnalysisContext("app",SourceScope.MAIN,List.of(),Set.of(),List.of(),"",Map.of());}
}
