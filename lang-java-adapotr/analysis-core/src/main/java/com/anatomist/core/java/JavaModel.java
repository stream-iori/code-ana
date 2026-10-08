package com.anatomist.core.java;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

/** Context-owned detached declarations, syntax and original source used by semantic consumers. */
public final class JavaModel implements AutoCloseable {
    public record DeclarationRef(Path source, int ordinal) {}

    public sealed interface TypeLookup permits FoundType, AmbiguousType, MissingType {}

    public record FoundType(DeclarationRef declaration) implements TypeLookup {}

    public record AmbiguousType(List<DeclarationRef> declarations) implements TypeLookup {
        public AmbiguousType {
            declarations = List.copyOf(declarations);
        }
    }

    public record MissingType(String name) implements TypeLookup {}

    private final Map<Path, JavaUnit> units = new LinkedHashMap<>();
    private final Map<Path, List<JavaDeclaration>> declarations = new LinkedHashMap<>();
    private final Map<String, List<DeclarationRef>> types = new LinkedHashMap<>();
    private final Map<DeclarationRef, List<DeclarationRef>> members = new HashMap<>();
    private final Map<DeclarationRef, DeclarationRef> parents = new HashMap<>();
    private final Map<Path, Map<SourceSpan, List<DeclarationRef>>> spans = new HashMap<>();
    private final Map<Path, JavaSource> sources = new HashMap<>();
    private final Map<Path, JavaSyntax> syntax = new HashMap<>();
    private boolean closed;
    private boolean modeledDeclarations;

    public boolean hasModeledDeclarations() {
        requireOpen();
        return modeledDeclarations;
    }

    public JavaModel(JavaAnalysisContext context) {
        for (JavaUnit unit : context.units()) {
            if (units.putIfAbsent(unit.path(), unit) != null)
                throw new IllegalArgumentException("duplicate unit in model");
            List<JavaDeclaration> inventory = new ArrayList<>();
            for (JavaDeclaration declaration : unit.declarationInventory())
                collect(unit.path(), declaration, inventory, null, 0);
            declarations.put(unit.path(), List.copyOf(inventory));
        }
        // javac inventories named nested types as unit roots. Reattach their explicit owners
        // for compact catalogues, which intentionally have no source syntax to infer ownership.
        for (var entry : declarations.entrySet()) {
            for (int ordinal = 0; ordinal < entry.getValue().size(); ordinal++) {
                var declaration = entry.getValue().get(ordinal);
                var reference = new DeclarationRef(entry.getKey(), ordinal);
                if (declaration.canonicalName().isEmpty()
                        || parents.containsKey(reference)
                        || declaration.symbol().owner().isEmpty()) continue;
                var lookup = type(declaration.symbol().owner());
                if (lookup instanceof FoundType found && !found.declaration().equals(reference)) {
                    parents.put(reference, found.declaration());
                    members.computeIfAbsent(found.declaration(), unused -> new ArrayList<>())
                            .add(reference);
                }
            }
        }
    }

    public JavaUnit unit(Path path) {
        requireOpen();
        JavaUnit unit = units.get(path);
        if (unit == null)
            throw new IllegalArgumentException("unit is outside the current context: " + path);
        return unit;
    }

    public JavaSource source(Path path) {
        requireOpen();
        return sources.computeIfAbsent(
                path,
                source -> {
                    try {
                        return JavaSource.read(unit(source));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
    }

    public JavaSyntax syntax(Path path) {
        requireOpen();
        return syntax.computeIfAbsent(path, source -> new JavaSyntax(unit(source).tree()));
    }

    public List<DeclarationRef> declarations(Path path) {
        unit(path);
        List<DeclarationRef> references = new ArrayList<>();
        for (int ordinal = 0; ordinal < declarations.get(path).size(); ordinal++)
            references.add(new DeclarationRef(path, ordinal));
        return List.copyOf(references);
    }

    public JavaDeclaration declaration(DeclarationRef reference) {
        unit(reference.source());
        return declarations.get(reference.source()).get(reference.ordinal());
    }

    public List<DeclarationRef> members(DeclarationRef reference) {
        declaration(reference);
        return List.copyOf(members.getOrDefault(reference, List.of()));
    }

    public Optional<DeclarationRef> parent(DeclarationRef reference) {
        declaration(reference);
        return Optional.ofNullable(parents.get(reference));
    }

    public List<DeclarationRef> declarationsAt(Path path, SourceSpan span) {
        unit(path);
        return List.copyOf(spans.getOrDefault(path, Map.of()).getOrDefault(span, List.of()));
    }

    public Optional<JavaNode> syntax(DeclarationRef reference) {
        JavaDeclaration declaration = declaration(reference);
        if (!declaration.span().present()) return Optional.empty();
        return syntax(reference.source()).nodes(declaration.span()).stream()
                .filter(node -> matches(node, declaration.symbol()))
                .findFirst();
    }

    public static boolean matches(JavaNode node, JavaSymbol symbol) {
        return switch (symbol.kind()) {
            case "CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD" ->
                    Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                                    .contains(node.kind())
                            && node.text("getSimpleName").equals(symbol.name());
            case "METHOD", "CONSTRUCTOR" -> node.kind().equals("METHOD");
            case "FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT", "PARAMETER" ->
                    node.kind().equals("VARIABLE") && node.text("getName").equals(symbol.name());
            case "TYPE_PARAMETER" ->
                    node.kind().equals("TYPE_PARAMETER")
                            && node.text("getName").equals(symbol.name());
            default -> false;
        };
    }

    /**
     * Duplicate source declarations remain ambiguous; classpath order cannot choose between them.
     */
    public TypeLookup type(String name) {
        requireOpen();
        List<DeclarationRef> candidates = types.getOrDefault(name, List.of());
        return switch (candidates.size()) {
            case 0 -> new MissingType(name);
            case 1 -> new FoundType(candidates.getFirst());
            default -> new AmbiguousType(candidates);
        };
    }

    private void collect(
            Path path,
            JavaDeclaration declaration,
            List<JavaDeclaration> inventory,
            DeclarationRef parent,
            int depth) {
        if (depth > 128 || inventory.size() >= 100_000)
            throw new IllegalArgumentException("declaration catalogue exceeds budget");
        DeclarationRef reference = new DeclarationRef(path, inventory.size());
        inventory.add(declaration);
        if (declaration.origin() == JavaDeclaration.Origin.MODEL) modeledDeclarations = true;
        if (parent != null) {
            parents.put(reference, parent);
            members.computeIfAbsent(parent, unused -> new ArrayList<>()).add(reference);
        }
        if (declaration.span().present())
            spans.computeIfAbsent(path, unused -> new HashMap<>())
                    .computeIfAbsent(declaration.span(), unused -> new ArrayList<>())
                    .add(reference);
        if (!declaration.canonicalName().isEmpty())
            types.computeIfAbsent(declaration.canonicalName(), unused -> new ArrayList<>())
                    .add(reference);
        if (!declaration.binaryName().isEmpty()
                && !declaration.binaryName().equals(declaration.canonicalName()))
            types.computeIfAbsent(declaration.binaryName(), unused -> new ArrayList<>())
                    .add(reference);
        for (JavaDeclaration member : declaration.members())
            collect(path, member, inventory, reference, depth + 1);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Java model is closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        units.clear();
        declarations.clear();
        types.clear();
        parents.clear();
        members.clear();
        spans.clear();
        sources.clear();
        syntax.clear();
    }
}
