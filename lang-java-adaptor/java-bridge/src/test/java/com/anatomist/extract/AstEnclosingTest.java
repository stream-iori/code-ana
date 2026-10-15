package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.core.java.JavaIdentities;
import com.anatomist.core.java.JavaModel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class AstEnclosingTest {
    @Test
    void repeatedChildrenReuseCallableId(@TempDir Path directory) throws Exception {
        var context =
                JavaFactsTestSupport.analyze(
                        directory,
                        "A.java",
                        "package p; class A { void run() { first(); second(); } void first() {} void second() {} }");
        var model = new JavaModel(context);
        var identities = new JavaIdentities(model);
        var path = context.units().getFirst().path();
        var calls =
                model.syntax(path).nodes().stream()
                        .filter(
                                node ->
                                        node.kind().equals("METHOD_INVOCATION")
                                                && node.span().present())
                        .filter(
                                node ->
                                        context.units().getFirst().bindings().get(node.id()) != null
                                                && !context.units()
                                                        .getFirst()
                                                        .bindings()
                                                        .get(node.id())
                                                        .name()
                                                        .equals("<init>"))
                        .toList();
        String first = identities.owner(path, calls.getFirst()).orElseThrow();
        assertEquals("p.A#run()", first);
        assertSame(first, identities.owner(path, calls.getLast()).orElseThrow());
        model.close();
        assertThrows(IllegalStateException.class, () -> identities.owner(path, calls.getFirst()));
    }
}
