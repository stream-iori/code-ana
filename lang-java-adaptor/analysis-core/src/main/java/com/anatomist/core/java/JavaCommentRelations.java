package com.anatomist.core.java;

import java.nio.file.Path;
import java.util.*;

/** Language-side scope/statement association. Display windows never participate in ownership. */
public final class JavaCommentRelations {
    public enum Relation { DOCUMENTS, ENCLOSING_CALLABLE, LEADING_STATEMENT, TRAILING_STATEMENT }
    public enum Basis { LANGUAGE_RULE, HEURISTIC }
    public record Link(Relation relation, JavaModel.DeclarationRef declaration, SourceSpan span, Basis basis) {}
    private static final Set<String> TYPES = Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private static final Set<String> DECLARATIONS = Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD", "METHOD", "VARIABLE");
    private final JavaModel model;
    private final Path path;
    private final JavaSource source;
    private final JavaSyntax syntax;
    private final Map<Integer, List<JavaNode>> statements = new HashMap<>();
    private final Interval root;
    private final Set<JavaComment> owned;
    private record Entry(JavaNode node, boolean execution, boolean barrier, boolean block) {}
    private record Interval(Entry value, Interval left, Interval right, long maxEnd) {}

    JavaCommentRelations(JavaModel model, Path path) {
        this.model = model;
        this.path = path;
        source = model.source(path);
        syntax = model.syntax(path);
        var comments = model.comments(path);
        charge(comments.size(), comments.size(), 128L * comments.size());
        owned = Collections.newSetFromMap(new IdentityHashMap<JavaComment, Boolean>());
        owned.addAll(comments);
        var entries = new ArrayList<Entry>();
        for (JavaNode node : syntax.nodes()) {
            charge(1, 0, 0);
            if (!node.span().present()) continue;
            boolean block = node.kind().equals("BLOCK");
            boolean barrier = TYPES.contains(node.kind());
            boolean execution = node.kind().equals("METHOD") || node.kind().equals("LAMBDA_EXPRESSION")
                    || block && syntax.parent(node).map(parent -> TYPES.contains(parent.kind())).orElse(false);
            if (block || barrier || execution) {
                charge(1, 1, 128);
                entries.add(new Entry(node, execution, barrier, block));
            }
            if (block) {
                var values = node.values("getStatements");
                charge(1L + values.size(), values.size(), 64L + 32L * values.size());
                var children = new ArrayList<JavaNode>();
                for (Object value : values)
                    if (value instanceof JavaNode child && child.span().present()) children.add(child);
                sort(children, Comparator.comparingLong(JavaNode::start).thenComparingLong(JavaNode::end).thenComparingInt(JavaNode::id));
                statements.put(node.id(), List.copyOf(children));
            }
        }
        sort(entries, Comparator.comparingLong((Entry entry) -> entry.node().start())
                .thenComparingLong(entry -> entry.node().end()).thenComparingInt(entry -> entry.node().id()));
        root = tree(entries, 0, entries.size(), 0);
    }

    List<Link> links(JavaComment comment) {
        charge(1, 1, 256);
        if (!owned.contains(comment)) throw new IllegalArgumentException("comment is outside its source unit");
        var result = new LinkedHashSet<Link>();
        for (int id : comment.documentOwners()) {
            charge(1, 0, 0);
            JavaNode owner = syntax.node(id).orElseThrow(() -> new IllegalArgumentException("comment document owner is outside its source unit"));
            if (!DECLARATIONS.contains(owner.kind())) throw new IllegalArgumentException("comment document owner is not a declaration");
            addDeclarationLinks(result, Relation.DOCUMENTS, owner);
        }
        var selection = new Selection();
        containing(root, comment.span(), selection);
        Entry scope = selection.scope;
        if (scope != null && scope.execution())
            addDeclarationLinks(result, Relation.ENCLOSING_CALLABLE, scope.node());
        Entry block = selection.block;
        if (block != null && (scope == null || contains(scope.node().span(), block.node().span()))) {
            List<JavaNode> children = statements.get(block.node().id());
            int left = 0, right = children.size();
            while (left < right) {
                charge(1, 0, 0);
                int mid = (left + right) >>> 1;
                if (children.get(mid).start() < comment.span().end()) left = mid + 1;
                else right = mid;
            }
            if (left < children.size()) {
                JavaNode next = children.get(left);
                int gap = DECLARATIONS.contains(next.kind()) ? -1 : whitespaceLines(comment.span().end(), next.start());
                if (gap >= 0 && gap <= 1)
                    add(result, new Link(Relation.LEADING_STATEMENT, null, next.span(), Basis.HEURISTIC));
            }
            // The nearest preceding sibling blocks a trailing association even if that
            // sibling is a declaration. Never skip across another statement/declaration.
            left = 0; right = children.size();
            while (left < right) {
                charge(1, 0, 0);
                int mid = (left + right) >>> 1;
                if (children.get(mid).end() <= comment.span().start()) left = mid + 1;
                else right = mid;
            }
            if (left > 0) {
                JavaNode previous = children.get(left - 1);
                int gap = DECLARATIONS.contains(previous.kind()) ? -1 : whitespaceLines(previous.end(), comment.span().start());
                if (gap == 0)
                    add(result, new Link(Relation.TRAILING_STATEMENT, null, previous.span(), Basis.HEURISTIC));
            }
        }
        charge(result.size(), 0, 32L + 16L * result.size());
        return List.copyOf(result);
    }

    private void addDeclarationLinks(Set<Link> result, Relation relation, JavaNode node) {
        boolean found = false;
        var references = model.declarationsAt(path, node.span());
        charge(references.size() + 1L, 0, 32L + 16L * references.size());
        for (var reference : references) {
            if (JavaModel.matches(node, model.declaration(reference).symbol())) {
                add(result, new Link(relation, reference, node.span(), Basis.LANGUAGE_RULE));
                found = true;
            }
        }
        if (!found) add(result, new Link(relation, null, node.span(), Basis.LANGUAGE_RULE));
    }

    private void add(Set<Link> result, Link link) {
        charge(1, 1, 128);
        result.add(link);
    }

    private int whitespaceLines(long start, long end) {
        if (start > end) return -1;
        int lines = 0;
        boolean cr = false;
        String raw = source.text();
        for (int i = Math.toIntExact(start); i < end;) {
            charge(1, 0, 0);
            char value = raw.charAt(i++);
            if (value == '\\') {
                int hex = i;
                while (hex < end && raw.charAt(hex) == 'u') { charge(1, 0, 0); hex++; }
                if (hex == i || hex + 4 > end) return -1;
                int decoded = 0;
                for (int p = hex; p < hex + 4; p++) {
                    charge(1, 0, 0);
                    int digit = Character.digit(raw.charAt(p), 16);
                    if (digit < 0) return -1;
                    decoded = decoded * 16 + digit;
                }
                value = (char) decoded;
                i = hex + 4;
            }
            if (value == '\r') { lines++; cr = true; }
            else if (value == '\n') { if (!cr) lines++; cr = false; }
            else if (value == ' ' || value == '\t' || value == '\f') cr = false;
            else return -1;
        }
        return lines;
    }

    private static boolean contains(SourceSpan outer, SourceSpan inner) {
        return outer.start() <= inner.start() && outer.end() >= inner.end();
    }

    private static boolean nearer(Entry candidate, Entry current) {
        return current == null || candidate.node().end() - candidate.node().start()
                < current.node().end() - current.node().start();
    }
    private static final class Selection { Entry scope, block; }
    private void containing(Interval interval, SourceSpan target, Selection selection) {
        if (interval == null || interval.maxEnd() < target.end()) return;
        charge(1, 0, 0);
        containing(interval.left(), target, selection);
        Entry entry = interval.value();
        if (entry.node().start() > target.start()) return;
        if (contains(entry.node().span(), target)) {
            if ((entry.execution() || entry.barrier()) && nearer(entry, selection.scope)) selection.scope = entry;
            if (entry.block() && nearer(entry, selection.block)) selection.block = entry;
        }
        containing(interval.right(), target, selection);
    }
    private Interval tree(List<Entry> entries, int begin, int end, int depth) {
        if (begin == end) return null;
        var analysis = model.analysisContext();
        if (analysis != null) analysis.depth(depth + 1);
        charge(1, 1, 96);
        int mid = (begin + end) >>> 1;
        Interval left = tree(entries, begin, mid, depth + 1), right = tree(entries, mid + 1, end, depth + 1);
        long maximum = entries.get(mid).node().end();
        if (left != null) maximum = Math.max(maximum, left.maxEnd());
        if (right != null) maximum = Math.max(maximum, right.maxEnd());
        return new Interval(entries.get(mid), left, right, maximum);
    }
    private <T> void sort(List<T> values, Comparator<T> order) {
        long n = values.size();
        charge(n * (n == 0 ? 1 : 64 - Long.numberOfLeadingZeros(n)), 0, 32L + 16L * n);
        values.sort(order);
    }
    private void charge(long work, long nodes, long bytes) {
        var analysis = model.analysisContext();
        if (analysis != null) analysis.charge(work, nodes, bytes);
    }
}
