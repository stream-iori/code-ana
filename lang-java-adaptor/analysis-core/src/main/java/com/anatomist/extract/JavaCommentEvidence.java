package com.anatomist.extract;

import com.anatomist.core.java.*;
import com.anatomist.model.ExtractionResult;
import java.nio.file.Path;
import java.util.*;

/** Frozen language evidence with original UTF-8 ranges, detached before the model closes. */
public record JavaCommentEvidence(String kind, Range range, String body, List<Segment> bodyMap,
                                  List<Link> links) {
    public record Range(long byteStart, long byteEnd, int beginLine, int beginColumn,
                        int endLine, int endColumn) {}
    public record Segment(long bodyStart, long bodyEnd, long sourceStart, long sourceEnd) {}
    public record Link(String relation, String declaration, Range range, String basis) {}
    public JavaCommentEvidence { bodyMap = List.copyOf(bodyMap); links = List.copyOf(links); }

    public static List<JavaCommentEvidence> freeze(JavaModel model, Path path, ExtractionResult facts) {
        var comments = model.comments(path);
        if (comments.isEmpty()) return List.of();
        var budget = model.analysisContext();
        var source = model.source(path);
        var offsets = new Utf8(source.text(), budget);
        var identities = new JavaIdentities(model);
        budget.charge(1L + facts.nodes.size(), facts.nodes.size(), 128L * facts.nodes.size());
        var published = new HashSet<String>();
        for (var node : facts.nodes) {
            if (node.sourceFile != null && !node.sourceFile.isEmpty())
                published.add(node.symbolId == null ? com.anatomist.core.NodeKeyFactory.symbolId(node.id) : node.symbolId);
        }
        var output = new ArrayList<JavaCommentEvidence>();
        for (var comment : comments) {
            budget.charge(1, 1, 256L + 3L * comment.body().length() + 96L * comment.segments().size());
            var bodyOffsets = new Utf8(comment.body(), budget);
            var segments = new ArrayList<Segment>();
            for (var segment : comment.segments()) {
                budget.charge(1, 1, 64);
                segments.add(new Segment(bodyOffsets.at(segment.bodyStart()), bodyOffsets.at(segment.bodyEnd()),
                        offsets.at(segment.sourceStart()), offsets.at(segment.sourceEnd())));
            }
            var links = new ArrayList<Link>();
            for (var link : model.commentLinks(path, comment)) {
                budget.charge(1, 1, 256);
                String declaration = link.declaration() == null ? null : identities.declaration(link.declaration());
                if (declaration != null && !published.contains(declaration)) declaration = null;
                links.add(new Link(link.relation().name().toLowerCase(Locale.ROOT), declaration,
                        range(source, offsets, link.span()), link.basis().name().toLowerCase(Locale.ROOT)));
            }
            output.add(new JavaCommentEvidence(comment.kind().name().toLowerCase(Locale.ROOT),
                    range(source, offsets, comment.span()), comment.body(), segments, links));
        }
        budget.charge(1, 1, 32L + 16L * output.size());
        return List.copyOf(output);
    }

    private static Range range(JavaSource source, Utf8 offsets, SourceSpan span) {
        var begin = source.position(span.start());
        var end = source.position(span.end());
        return new Range(offsets.at(span.start()), offsets.at(span.end()), begin.line(), begin.column(), end.line(), end.column());
    }

    /** One linear pass, no per-comment prefix encoding; surrogate interiors are never public offsets. */
    private static final class Utf8 {
        final long[] bytes;
        Utf8(String text, com.anatomist.core.AnalysisContext budget) {
            budget.charge(1, 1, 24L + 8L * (text.length() + 1L));
            bytes = new long[Math.addExact(text.length(), 1)];
            long offset = 0;
            for (int i = 0; i < text.length();) {
                budget.charge(1, 0, 0);
                bytes[i] = offset;
                char ch = text.charAt(i);
                int cp = text.codePointAt(i);
                int count = Character.charCount(cp);
                if (Character.isSurrogate(ch) && count == 1) throw new IllegalArgumentException("COMMENT_INVALID_UTF8");
                if (count == 2) bytes[i + 1] = -1;
                offset += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                i += count;
            }
            bytes[text.length()] = offset;
        }
        long at(long point) {
            if (point < 0 || point >= bytes.length || bytes[(int) point] < 0)
                throw new IllegalArgumentException("COMMENT_INVALID_UTF8_BOUNDARY");
            return bytes[(int) point];
        }
    }
}
