package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.JavaFactsTestSupport;
import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

class AnnotationExtractorTest {

    @Test
    void resolvesJdkAnnotationOnMethod(@TempDir Path directory) throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A extends Object {\n"
                        + "  @Override public String toString() { return \"a\"; }\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Optional<Annotation> override =
                r.annotations.stream()
                        .filter(a -> "java.lang.Override".equals(a.annotationFqn))
                        .findFirst();
        assertTrue(override.isPresent(), "expected @Override resolved; got " + r.annotations);
        assertTrue(override.get().nodeId.startsWith("pkg.A#toString("));
    }

    @Test
    void capturesAnnotationAttributes(@TempDir Path directory) throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  @SuppressWarnings(\"unchecked\") void m() {}\n"
                        + "  @SuppressWarnings({\"a\", \"b\"}) void n() {}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Annotation single =
                r.annotations.stream()
                        .filter(a -> a.nodeId.startsWith("pkg.A#m("))
                        .findFirst()
                        .orElseThrow();
        assertEquals("java.lang.SuppressWarnings", single.annotationFqn);
        assertTrue(single.attributes.contains("\"value\":\"unchecked\""), single.attributes);

        Annotation array =
                r.annotations.stream()
                        .filter(a -> a.nodeId.startsWith("pkg.A#n("))
                        .findFirst()
                        .orElseThrow();
        assertTrue(array.attributes.contains("[\"a\",\"b\"]"), array.attributes);
    }

    @Test
    void parameterAnnotationCarriesIndexAndName(@TempDir Path directory) throws Exception {
        String source =
                "package pkg;\n"
                        + "public class A {\n"
                        + "  void m(@Deprecated String s) {}\n"
                        + "}\n";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Annotation a =
                r.annotations.stream()
                        .filter(x -> "java.lang.Deprecated".equals(x.annotationFqn))
                        .findFirst()
                        .orElseThrow();
        assertTrue(a.attributes.contains("\"_param\":0"), a.attributes);
        assertTrue(a.attributes.contains("\"_name\":\"s\""), a.attributes);
    }

    @Test
    void preservesUnresolvedAnnotationAndStructuralTarget(@TempDir Path directory)
            throws Exception {
        String source = "package pkg; class A { @Missing String value; }";
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Annotation annotation =
                r.annotations.stream()
                        .filter(value -> "Missing".equals(value.rawName))
                        .findFirst()
                        .orElseThrow();
        assertNull(annotation.annotationFqn);
        assertEquals("unresolved", annotation.resolutionStatus);
        assertEquals("value", annotation.targetKind);
        assertNotNull(annotation.beginLine);
        assertNotNull(annotation.endColumn);
    }

    @Test
    void coversRecordComponentsAndEnumConstants(@TempDir Path directory) throws Exception {
        String source =
                """
                package pkg;
                @interface Mark {}
                record R(@Mark String name) {}
                enum E { @Mark ONE }
                """;
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        Annotation component =
                r.annotations.stream()
                        .filter(value -> "component[0]".equals(value.targetPath))
                        .findFirst()
                        .orElseThrow();
        assertEquals("pkg.R#name", component.nodeId);
        assertEquals("value", component.targetKind);
        Annotation constant =
                r.annotations.stream()
                        .filter(value -> "pkg.E#ONE".equals(value.nodeId))
                        .findFirst()
                        .orElseThrow();
        assertEquals("value", constant.targetKind);
    }

    @Test
    void recordsDirectMetaRelations(@TempDir Path directory) throws Exception {
        String source =
                """
                package pkg;
                @Deprecated @interface Composed {}
                @Composed class A {}
                """;
        ExtractionResult r = JavaFactsTestSupport.extract(directory, "A.java", source);

        assertTrue(
                r.annotationMetaRelations.stream()
                        .anyMatch(
                                value ->
                                        "pkg.Composed".equals(value.annotationFqn)
                                                && "java.lang.Deprecated"
                                                        .equals(value.metaAnnotationFqn)));
    }
}
