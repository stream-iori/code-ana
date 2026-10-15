package com.anatomist.core.nativeimage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

class JdkTypeCatalogTest {

    @Test
    void roundTrip_singleType() throws Exception {
        JdkType t =
                new JdkType(
                        "java.lang.String",
                        "java.lang.Object",
                        List.of(
                                "java.io.Serializable",
                                "java.lang.Comparable",
                                "java.lang.CharSequence"),
                        JdkType.FLAG_CLASS | JdkType.FLAG_FINAL,
                        null,
                        List.of(
                                new JdkType.FieldEntry(
                                        "CASE_INSENSITIVE_ORDER",
                                        "Ljava/util/Comparator;",
                                        JdkType.FLAG_STATIC | JdkType.FLAG_FINAL)),
                        List.of(
                                new JdkType.MethodEntry("length", "()I", JdkType.FLAG_PUBLIC),
                                new JdkType.MethodEntry(
                                        "substring", "(I)Ljava/lang/String;", JdkType.FLAG_PUBLIC),
                                new JdkType.MethodEntry(
                                        "substring",
                                        "(II)Ljava/lang/String;",
                                        JdkType.FLAG_PUBLIC | JdkType.FLAG_VARARGS)));

        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        cat.add(t);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        JdkTypeCatalog reloaded =
                JdkTypeCatalog.readFrom(new ByteArrayInputStream(out.toByteArray()));

        assertEquals(21, reloaded.jdkRelease());
        assertEquals(1, reloaded.size());
        JdkType got = reloaded.find("java.lang.String");
        assertNotNull(got);
        assertEquals("java.lang.String", got.fqn);
        assertEquals("java.lang.Object", got.superFqn);
        assertEquals(3, got.interfaceFqns.size());
        assertTrue(got.interfaceFqns.contains("java.lang.Comparable"));
        assertEquals(1, got.fields.size());
        assertEquals(3, got.methods.size());
        long substringCount = got.methods.stream().filter(m -> m.name.equals("substring")).count();
        assertEquals(2, substringCount);
        assertTrue(
                got.methods.stream()
                        .anyMatch(
                                m ->
                                        m.name.equals("substring")
                                                && (m.flags & JdkType.FLAG_VARARGS) != 0));
    }

    @Test
    void roundTrip_manyTypes_sharesStringPool() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        for (int i = 0; i < 100; i++) {
            cat.add(
                    new JdkType(
                            "java.lang.X" + i,
                            "java.lang.Object",
                            List.of(),
                            JdkType.FLAG_CLASS,
                            null,
                            List.of(),
                            List.of(
                                    new JdkType.MethodEntry(
                                            "toString",
                                            "()Ljava/lang/String;",
                                            JdkType.FLAG_PUBLIC))));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        byte[] bytes = out.toByteArray();
        assertTrue(
                bytes.length < 5000, "expected pooled encoding < 5000 bytes; got " + bytes.length);

        JdkTypeCatalog re = JdkTypeCatalog.readFrom(new ByteArrayInputStream(bytes));
        assertEquals(100, re.size());
        assertEquals("java.lang.Object", re.find("java.lang.X42").superFqn);
    }

    @Test
    void readFrom_rejectsBadMagic() {
        ByteArrayInputStream in = new ByteArrayInputStream("WRONG".getBytes());
        assertThrows(IllegalArgumentException.class, () -> JdkTypeCatalog.readFrom(in));
    }

    @Test
    void writeAndReadBack_isDeterministic() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        cat.add(
                new JdkType(
                        "java.util.List",
                        null,
                        List.of("java.util.Collection"),
                        JdkType.FLAG_INTERFACE | JdkType.FLAG_PUBLIC,
                        null,
                        List.of(),
                        List.of(
                                new JdkType.MethodEntry(
                                        "of",
                                        "()Ljava/util/List;",
                                        JdkType.FLAG_STATIC | JdkType.FLAG_PUBLIC))));

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        cat.writeTo(out1);
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        cat.writeTo(out2);
        assertArrayEquals(out1.toByteArray(), out2.toByteArray());
    }

    @Test
    void find_returnsNullForUnknown() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalog(21);
        cat.add(new JdkType("java.lang.Object", null, List.of(), 0, null, List.of(), List.of()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        cat.writeTo(out);
        JdkTypeCatalog re = JdkTypeCatalog.readFrom(new ByteArrayInputStream(out.toByteArray()));
        assertNull(re.find("does.not.Exist"));
    }

    @Test
    void embeddedJdk8CatalogHasMatchingHeader() throws Exception {
        try (var in =
                JdkTypeCatalogTest.class.getResourceAsStream(
                        "/META-INF/anatomist/jdk8-types.bin")) {
            assertNotNull(in, "embedded JDK 8 catalog missing");
            JdkTypeCatalog catalog = JdkTypeCatalog.readFrom(in);
            assertEquals(8, catalog.jdkRelease());
            assertNotNull(catalog.find("java.lang.String"));
            for (String type :
                    List.of(
                            "java.util.Optional",
                            "javax.crypto.Cipher",
                            "javax.crypto.KeyGenerator",
                            "javax.crypto.spec.SecretKeySpec",
                            "javax.crypto.spec.IvParameterSpec")) {
                assertNotNull(catalog.find(type), type + " missing from embedded JDK 8 catalog");
            }
            assertTrue(
                    catalog.find("java.util.Optional").methods.stream()
                            .anyMatch(method -> "orElse".equals(method.name)));
            assertTrue(
                    catalog.find("javax.crypto.Cipher").methods.stream()
                            .anyMatch(method -> "getInstance".equals(method.name)));
        }
        assertNull(
                JdkTypeCatalogTest.class.getResource("/META-INF/anatomist/jdk17-types.bin"),
                "only the JDK 8 baseline catalog should ship by default");
    }
}
