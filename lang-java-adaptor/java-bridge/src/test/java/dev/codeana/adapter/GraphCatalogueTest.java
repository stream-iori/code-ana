package dev.codeana.adapter;

import static org.junit.jupiter.api.Assertions.*;
import com.anatomist.core.*;
import com.anatomist.model.*;
import dev.codeana.common.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphCatalogueTest {
    @Test void localAmbiguityDoesNotLeakIntoNextUnit(@TempDir Path root) {
        var resolver = SourceIdentityResolver.fromRoots(root,
                List.of(new SourceRoot(root.resolve("a"), "a", SourceScope.MAIN),
                        new SourceRoot(root.resolve("b"), "b", SourceScope.MAIN)));
        Set<String> known = Set.of("java-core::a::MAIN::p.Service#run(int)");
        var rewrite = GraphIdentityRewriter.prepare(known);
        var bind = EdgeTargetBinder.prepare(known);
        for (boolean local : List.of(true, false)) {
            var result = new ExtractionResult();
            if (local) {
                var n = new Node(); n.kind = "METHOD"; n.id = "p.Service#run(String)";
                n.sourceFile = "b/Service.java"; result.nodes.add(n);
            }
            var edge = new Edge(); edge.isExternal = true;
            edge.externalTargetFqn = "p.Service#run(long)"; result.edges.add(edge);
            GraphIdentityRewriter.rewrite(result, resolver, rewrite);
            EdgeTargetBinder.bindExternalTargets(result, bind);
            assertEquals(local, edge.isExternal);
            if (!local) assertEquals(known.iterator().next(), edge.targetId);
        }
    }

    @Test void preparedAndFreshCataloguesChooseTheSameTargets(@TempDir Path root) {
        var resolver = SourceIdentityResolver.fromRoots(root,
                List.of(new SourceRoot(root.resolve("a/Use.java"), "a", SourceScope.TEST)));
        Set<String> known = Set.of("java-core::a::MAIN::p.Target", "java-core::b::MAIN::p.Target",
                "java-core::a::TEST::p.Target", "other::a::MAIN::p.Target");
        var prepared = GraphIdentityRewriter.prepare(known);
        for (String provider : List.of("java-core", "other")) {
            var fresh = example(provider); var shared = example(provider);
            GraphIdentityRewriter.rewrite(fresh, resolver, known);
            GraphIdentityRewriter.rewrite(shared, resolver, prepared);
            assertEquals(fresh.edges.getFirst().targetId, shared.edges.getFirst().targetId);
            assertEquals(fresh.edges.getFirst().isExternal, shared.edges.getFirst().isExternal);
        }
        assertEquals(new SourceIdentity("a", SourceScope.TEST), resolver.resolve("a/Use.java"));
        assertEquals(SourceScope.TEST, resolver.compilationScope(root.resolve("a/Use.java")));
    }
    private static ExtractionResult example(String provider) {
        var r = new ExtractionResult(); var e = new Edge(); e.providerId = provider;
        e.sourceFile = "a/Use.java"; e.targetId = "p.Target"; r.edges.add(e); return r;
    }

    @Test void utf8EncoderPreservesCanonicalRequestBytes() {
        var value=Json.object("unicode","😀中\n\uD800x\uDC00","nested",List.of(1,true));
        assertArrayEquals(Json.write(value).getBytes(StandardCharsets.UTF_8),Json.encode(value));
        assertThrows(IllegalArgumentException.class,()->Json.encode(Double.NaN));
    }
    @Test void borrowedRequestEncodingSurvivesReentryAndFailure() throws Exception {
        var value=Json.object("ascii","a".repeat(100000),"unicode","中😀");
        byte[] expected=Json.encode(value);
        Json.encodeTo(value,(bytes,offset,length)-> {
            Json.encodeTo(Json.object("nested",true),(a,b,c)->assertEquals("{\"nested\":true}",new String(a,b,c,StandardCharsets.UTF_8)));
            assertArrayEquals(expected,Arrays.copyOfRange(bytes,offset,offset+length));
        });
        assertThrows(IllegalStateException.class,()->Json.encodeTo(value,(a,b,c)->{throw new IllegalStateException("fixture");}));
        assertArrayEquals(expected,Json.encode(value));
    }
}
