package com.anatomist.extract;

import com.anatomist.core.java.*;
import com.anatomist.model.Edge;

import java.nio.file.Path;
import java.util.*;

/** Original source coordinates and lexical branch context shared by detached-model facts. */
final class JavaSiteFacts {
    private final JavaModel model;

    JavaSiteFacts(JavaModel model) {
        this.model = model;
    }

    String control(Path path, JavaNode site) {
        List<String> segments = new ArrayList<>();
        JavaSyntax syntax = model.syntax(path);
        JavaNode child = site;
        for (JavaNode parent = syntax.parent(site).orElse(null);
                parent != null;
                parent = syntax.parent(parent).orElse(null)) {
            if (Set.of(
                            "METHOD",
                            "CLASS",
                            "INTERFACE",
                            "ENUM",
                            "ANNOTATION_TYPE",
                            "RECORD",
                            "LAMBDA_EXPRESSION",
                            "MEMBER_REFERENCE")
                    .contains(parent.kind())) break;
            String segment = segment(parent, child);
            if (segment != null)
                segments.add(segment + "@L" + model.source(path).position(parent.start()).line());
            child = parent;
        }
        Collections.reverse(segments);
        return segments.isEmpty() ? null : String.join(">", segments);
    }

    private static String segment(JavaNode parent, JavaNode child) {
        return switch (parent.kind()) {
            case "IF" ->
                    same(parent.tree("getThenStatement"), child)
                            ? "if-then"
                            : same(parent.tree("getElseStatement"), child) ? "if-else" : null;
            case "CONDITIONAL_EXPRESSION" ->
                    same(parent.tree("getTrueExpression"), child)
                            ? "ternary-then"
                            : same(parent.tree("getFalseExpression"), child)
                                    ? "ternary-else"
                                    : null;
            case "FOR_LOOP" -> "for";
            case "ENHANCED_FOR_LOOP" -> "foreach";
            case "WHILE_LOOP" -> "while";
            case "DO_WHILE_LOOP" -> "do";
            case "CATCH" -> "catch";
            case "TRY" ->
                    same(parent.tree("getFinallyBlock"), child)
                            ? "finally"
                            : same(parent.tree("getBlock"), child) ? "try" : null;
            case "SYNCHRONIZED" -> "synchronized";
            case "CASE" ->
                    parent.values("getExpressions").isEmpty()
                                    && parent.tree("getExpression") == null
                                    && parent.trees("getLabels").stream()
                                            .allMatch(
                                                    label ->
                                                            label.kind()
                                                                    .equals("DEFAULT_CASE_LABEL"))
                            ? "default"
                            : "case";
            default -> null;
        };
    }

    private static boolean same(JavaNode first, JavaNode second) {
        return first != null && first.id() == second.id();
    }

    void coordinates(Edge edge, Path path, SourceSpan span) {
        JavaSource.Position begin = model.source(path).position(span.start()),
                end = model.source(path).position(Math.max(span.start(), span.end() - 1));
        edge.beginLine = begin.line();
        edge.beginColumn = begin.column();
        edge.endLine = end.line();
        edge.endColumn = end.column();
        edge.sourceLocation = "L" + begin.line();
        edge.sourceOrdinal = 0;
    }
}
