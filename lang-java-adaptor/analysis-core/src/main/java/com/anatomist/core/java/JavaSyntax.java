package com.anatomist.core.java;

import java.util.*;

/** Source-order tree catalogue with explicit parents; no reconstructed compatibility AST. */
public final class JavaSyntax {
    private final Map<Integer, JavaNode> nodes = new LinkedHashMap<>();
    private final Map<SourceSpan, List<JavaNode>> ranges = new HashMap<>();
    private final List<JavaNode> order;
    private final Map<Integer, Integer> parents = new HashMap<>();

    public JavaSyntax(JavaNode root) {
        ArrayDeque<Visit> pending = new ArrayDeque<>();
        pending.push(new Visit(Objects.requireNonNull(root), -1, 0));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            JavaNode node = visit.node();
            if (visit.depth() > 512 || nodes.size() >= 1_000_000)
                throw new IllegalArgumentException("syntax catalogue exceeds budget");
            JavaNode previous = nodes.putIfAbsent(node.id(), node);
            if (previous != null) {
                if (previous != node && !previous.equals(node))
                    throw new IllegalArgumentException("conflicting syntax identity");
                continue;
            }
            parents.put(node.id(), visit.parent());
            if (node.span().present())
                ranges.computeIfAbsent(node.span(), unused -> new ArrayList<>()).add(node);
            List<JavaNode> children = new ArrayList<>();
            for (Object value : node.fields().values()) {
                if (value instanceof JavaNode child) children.add(child);
                else if (value instanceof List<?> list)
                    for (Object item : list)
                        if (item instanceof JavaNode child) children.add(child);
            }
            for (int i = children.size() - 1; i >= 0; i--)
                pending.push(new Visit(children.get(i), node.id(), visit.depth() + 1));
        }
        order = List.copyOf(nodes.values());
    }

    public List<JavaNode> nodes() {
        return order;
    }

    public List<JavaNode> nodes(SourceSpan span) {
        return List.copyOf(ranges.getOrDefault(span, List.of()));
    }

    public Optional<JavaNode> node(int id) {
        return Optional.ofNullable(nodes.get(id));
    }

    public Optional<JavaNode> parent(JavaNode node) {
        return Optional.ofNullable(nodes.get(parents.getOrDefault(node.id(), -1)));
    }

    public Optional<JavaNode> enclosing(JavaNode node, Set<String> kinds) {
        for (JavaNode owner = parent(node).orElse(null);
                owner != null;
                owner = parent(owner).orElse(null))
            if (kinds.contains(owner.kind())) return Optional.of(owner);
        return Optional.empty();
    }

    private record Visit(JavaNode node, int parent, int depth) {}
}
