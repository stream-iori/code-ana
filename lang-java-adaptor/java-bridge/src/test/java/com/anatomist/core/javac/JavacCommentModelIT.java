package com.anatomist.core.javac;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.java.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real compiler batch -> v51 decoder -> detached JavaModel, including failed bindings. */
class JavacCommentModelIT {
    private JavacBackend backend(Path root, int release) throws Exception {
        var config = new ProjectConfig();
        config.setJavaHome(Objects.requireNonNull(JavacBackendIT.home(release)).toString());
        return new JavacBackend(JavacToolchain.resolve(config.javacOptions(), release, null),
                List.of(), List.of(root), config.javacOptions());
    }

    @Test
    void plannedFixtureCrossesTheRealDetachedModelBoundary(@TempDir Path root) throws Exception {
        Path source = Files.copy(Path.of("fixtures/comment-indexing/Comments.java"), root.resolve("Comments.java"));
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend(root, 8).analyzeFiles(List.of(source), contexts::add).complete());
        var fixture = (Map<?, ?>) com.anatomist.json.Json.parseTree(Files.readString(Path.of("fixtures/comment-indexing/expected.json")));
        var expected = (List<?>) fixture.get("comments");
        try (var model = new JavaModel(contexts.getFirst())) {
            assertEquals(expected.size(), model.comments(source).size());
            var identities = new JavaIdentities(model);
            for (Object item : (List<?>) fixture.get("non_comments")) {
                String text = (String) ((Map<?, ?>) item).get("contains");
                assertTrue(model.comments(source).stream().noneMatch(comment -> comment.body().contains(text)));
            }
            int index = 0;
            for (var comment : model.comments(source)) {
                var golden = (Map<?, ?>) expected.get(index++);
                assertEquals(comment.kind() == JavaComment.Kind.DOC ? 1 : 0, comment.documentOwners().size());
                for (int owner : comment.documentOwners()) {
                    var node = model.syntax(source).node(owner).orElseThrow();
                    assertTrue(List.of("CLASS", "METHOD").contains(node.kind()));
                    assertFalse(model.declarationsAt(source, node.span()).isEmpty());
                }
                String raw = model.source(source).slice(comment.span());
                assertTrue(raw.startsWith("//") || raw.startsWith("/*"));
                var links = model.commentLinks(source, comment);
                long line = model.source(source).position(comment.span().start()).line();
                assertEquals(((Number) golden.get("line")).longValue(), line);
                assertEquals(golden.get("kind"), comment.kind().name().toLowerCase(Locale.ROOT));
                assertTrue(comment.body().contains((String) golden.get("contains")));
                for (var relation : List.of(JavaCommentRelations.Relation.DOCUMENTS, JavaCommentRelations.Relation.ENCLOSING_CALLABLE)) {
                    var actual = links.stream().filter(link -> link.relation() == relation && link.declaration() != null)
                            .map(link -> identities.declaration(link.declaration())).toList();
                    Object name = golden.get(relation.name().toLowerCase(Locale.ROOT));
                    assertEquals(name == null ? List.of() : List.of(name), actual, "line " + line);
                }
                for (var relation : List.of(JavaCommentRelations.Relation.LEADING_STATEMENT, JavaCommentRelations.Relation.TRAILING_STATEMENT)) {
                    var actual = links.stream().filter(link -> link.relation() == relation)
                            .map(link -> (long) model.source(source).position(link.span().start()).line()).toList();
                    Number expectedLine = (Number) golden.get(relation.name().toLowerCase(Locale.ROOT) + "_line");
                    assertEquals(expectedLine == null ? List.of() : List.of(expectedLine.longValue()), actual, "line " + line);
                }
                if (golden.get("enclosing_range_lines") instanceof List<?> range) {
                    var scope = links.stream().filter(link -> link.relation() == JavaCommentRelations.Relation.ENCLOSING_CALLABLE).findFirst().orElseThrow();
                    assertNull(scope.declaration());
                    assertEquals(((Number) range.getFirst()).intValue(), model.source(source).position(scope.span().start()).line());
                    assertEquals(((Number) range.getLast()).intValue(), model.source(source).position(scope.span().end()).line());
                }
                for (var link : links) assertEquals(
                        link.relation() == JavaCommentRelations.Relation.LEADING_STATEMENT || link.relation() == JavaCommentRelations.Relation.TRAILING_STATEMENT
                                ? JavaCommentRelations.Basis.HEURISTIC : JavaCommentRelations.Basis.LANGUAGE_RULE, link.basis());
            }
            assertThrows(UnsupportedOperationException.class, () -> model.comments(source).clear());
        }
    }

    @Test
    void compilerDocumentOwnersIncludeAnnotationsEmptyDocsAndSharedFieldDeclarations(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("Owners.java"),
                "/** 类型 */ @Deprecated class Owners { /** 字段 */ int a,b; /** 方法 */ @Deprecated void run(){} /**/ void empty(){} }");
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend(root, 8).analyzeFiles(List.of(source), contexts::add).complete());
        try (var model = new JavaModel(contexts.getFirst())) {
            assertEquals(List.of(1, 2, 1, 1), model.comments(source).stream().map(comment -> comment.documentOwners().size()).toList());
            var fields = model.commentLinks(source, model.comments(source).get(1)).stream()
                    .filter(link -> link.relation() == JavaCommentRelations.Relation.DOCUMENTS)
                    .map(link -> model.declaration(link.declaration()).symbol().name()).sorted().toList();
            assertEquals(List.of("a", "b"), fields);
        }
    }

    @Test
    void bindingErrorsDoNotDiscardLexicalComments(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("Broken.java"),
                "/** 保留类型文档 */ class Broken { Missing value; void run() { // 保留方法注释\n missing(); } } // 文件末尾");
        var contexts = new ArrayList<JavaAnalysisContext>();
        backend(root, 8).analyzeFiles(List.of(source), contexts::add);
        assertFalse(contexts.isEmpty());
        try (var model = new JavaModel(contexts.getFirst())) {
            assertEquals(3, model.comments(source).size());
            assertTrue(model.comments(source).getLast().body().contains("文件末尾"));
            assertTrue(model.comments(source).getFirst().kind() == JavaComment.Kind.DOC);
        }
    }

    @Test
    void commentEditsUpdateCapturedEvidenceWithoutChangingDeclarationContract(@TempDir Path root) throws Exception {
        Path source = root.resolve("Inventory.java");
        var backend = backend(root, 8);
        String contract = null, hash = null;
        for (String word : List.of("旧库存", "新库存")) {
            Files.writeString(source, "/** " + word + " */ class Inventory { int count() { return 1; } }");
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend.analyzeFiles(List.of(source), contexts::add).complete());
            JavaUnit unit = contexts.getFirst().units().getFirst();
            assertTrue(unit.comments().getFirst().body().contains(word));
            if (contract != null) {
                assertEquals(contract, JavaContract.fingerprint(unit));
                assertNotEquals(hash, unit.sourceHash());
            }
            contract = JavaContract.fingerprint(unit);
            hash = unit.sourceHash();
            assertTrue(JavaDeclarationView.of(unit).comments().isEmpty(), "NoCode dependencies omit comment bodies");
        }
    }

    @Test
    void UnicodeEscapesAndPositionsSurviveEachExistingCompilerTarget(@TempDir Path root) throws Exception {
        String u = "\\" + "u";
        String text = u + "002f" + u + "002f 库存😀" + u + "000a class Inventory {}";
        for (int release : List.of(8, 17, 25)) {
            Path project = Files.createDirectory(root.resolve("jdk" + release));
            Path source = Files.writeString(project.resolve("Inventory.java"), text);
            var contexts = new ArrayList<JavaAnalysisContext>();
            assertTrue(backend(project, release).analyzeFiles(List.of(source), contexts::add).complete());
            try (var model = new JavaModel(contexts.getFirst())) {
                var comment = model.comments(source).getFirst();
                assertEquals(new SourceSpan(0, text.indexOf(u + "000a")), comment.span());
                assertEquals(" 库存😀", comment.body());
                assertEquals(comment.body().length(), comment.segments().getLast().bodyEnd());
            }
        }
    }

    @Test
    void unicodeWhitespaceRespectsLogicalBlankLinesAndNeverCrossesDeclarations(@TempDir Path root) throws Exception {
        String newline = "\\" + "u000a";
        Path source = Files.writeString(root.resolve("Gaps.java"), "class Gaps { void run() {"
                + "// adjacent" + newline + "System.out.println();"
                + "// separated" + newline + newline + "System.out.println();"
                + "// declaration" + newline + "int x=1; } }");
        var contexts = new ArrayList<JavaAnalysisContext>();
        assertTrue(backend(root, 8).analyzeFiles(List.of(source), contexts::add).complete());
        try (var model = new JavaModel(contexts.getFirst())) {
            for (var comment : model.comments(source)) {
                var links = model.commentLinks(source, comment);
                assertEquals(comment.body().contains("adjacent") ? 1 : 0,
                        links.stream().filter(link -> link.relation() == JavaCommentRelations.Relation.LEADING_STATEMENT).count());
                assertEquals(1, links.stream().filter(link -> link.relation() == JavaCommentRelations.Relation.ENCLOSING_CALLABLE).count());
            }
        }
    }
}
