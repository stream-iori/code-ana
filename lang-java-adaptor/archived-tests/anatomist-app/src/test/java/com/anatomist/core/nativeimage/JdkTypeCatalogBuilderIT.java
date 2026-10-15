package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Integration test: build a catalog from the running JDK's jrt-fs and verify it captures the
 * expected core types.
 *
 * <p>Builder must complete in seconds (one-shot Maven exec target). Output is a {@link
 * JdkTypeCatalog} that round-trips losslessly through the binary format.
 */
class JdkTypeCatalogBuilderIT {

    @Test
    void buildFromJrtFs_capturesCoreTypes() {
        JdkTypeCatalog cat = new JdkTypeCatalogBuilder().buildFromCurrentJdk();
        assertTrue(cat.jdkRelease() >= 21, "expected JDK ≥ 21; got " + cat.jdkRelease());
        assertTrue(cat.size() > 5_000, "expected thousands of JDK types; got " + cat.size());

        JdkType str = cat.find("java.lang.String");
        assertNotNull(str, "java.lang.String missing");
        assertEquals("java.lang.Object", str.superFqn);
        assertTrue(
                str.interfaceFqns.contains("java.io.Serializable"),
                "String should declare Serializable: " + str.interfaceFqns);
        assertTrue(str.interfaceFqns.contains("java.lang.CharSequence"));
        // String.length()I
        assertTrue(
                str.methods.stream()
                        .anyMatch(m -> m.name.equals("length") && m.descriptor.equals("()I")),
                "String.length()I not found");
        // overloaded substring
        long substringCount = str.methods.stream().filter(m -> m.name.equals("substring")).count();
        assertTrue(substringCount >= 2, "expected >=2 substring overloads; got " + substringCount);
    }

    @Test
    void buildFromJdkHome_readsInstalledJmods() {
        Path home = Path.of(System.getProperty("java.home"));
        JdkTypeCatalog cat = new JdkTypeCatalogBuilder().buildFromJdkHome(home);
        assertEquals(JdkTypeCatalogBuilder.releaseOf(home), cat.jdkRelease());
        assertNotNull(cat.find("java.lang.String"));
        assertTrue(cat.size() > 5_000, "expected thousands of JDK types; got " + cat.size());
    }

    @Test
    void buildFromJrtFs_interfaceHasNullSuper() {
        JdkType list = new JdkTypeCatalogBuilder().buildFromCurrentJdk().find("java.util.List");
        assertNotNull(list);
        assertEquals(0, list.flags & JdkType.FLAG_CLASS);
        assertNotEquals(0, list.flags & JdkType.FLAG_INTERFACE);
        // Interfaces have no super (or implicit Object). We store null.
        assertNull(list.superFqn);
    }

    @Test
    void buildFromJrtFs_enumDetected() {
        JdkType e =
                new JdkTypeCatalogBuilder()
                        .buildFromCurrentJdk()
                        .find("java.lang.annotation.RetentionPolicy");
        assertNotNull(e);
        assertNotEquals(0, e.flags & JdkType.FLAG_ENUM, "RetentionPolicy is an enum");
    }

    @Test
    void buildFromJrtFs_varargsMethodDetected() {
        JdkType path = new JdkTypeCatalogBuilder().buildFromCurrentJdk().find("java.nio.file.Path");
        assertNotNull(path);
        assertTrue(
                path.methods.stream()
                        .anyMatch(
                                method ->
                                        method.name.equals("of")
                                                && method.descriptor.equals(
                                                        "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;")
                                                && (method.flags & JdkType.FLAG_VARARGS) != 0));
    }
}
