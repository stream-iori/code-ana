package com.anatomist.core.java;

import java.nio.file.Path;
import java.util.*;

/** Declaration identities use erased types; local entities use original source coordinates. */
public final class JavaIdentities {
    private static final Set<String> TYPES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private final JavaModel model;
    private final Map<JavaModel.DeclarationRef, String> ids = new HashMap<>();

    public JavaIdentities(JavaModel model) {
        this.model = Objects.requireNonNull(model);
    }

    public String declaration(JavaModel.DeclarationRef reference) {
        model.declaration(reference);
        return declaration(reference, 0);
    }

    private String declaration(JavaModel.DeclarationRef reference, int depth) {
        if (depth > 128) throw new IllegalArgumentException("identity nesting exceeds budget");
        String known = ids.get(reference);
        if (known != null) return known;
        JavaDeclaration value = model.declaration(reference);
        String id;
        if (TYPES.contains(value.symbol().kind())) id = type(reference, value, depth + 1);
        else {
            JavaModel.DeclarationRef parent =
                    model.parent(reference)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "declaration owner is unavailable"));
            String owner = declaration(parent, depth + 1);
            String kind = value.symbol().kind();
            if (kind.equals("METHOD") || kind.equals("CONSTRUCTOR")) {
                JavaType executable =
                        model.unit(reference.source()).types().get(value.symbol().declaredType());
                String name =
                        kind.equals("CONSTRUCTOR")
                                ? model.declaration(parent).symbol().name()
                                : value.symbol().name();
                if (name.isEmpty()) name = "$anon";
                // Java permits a method with the enclosing class's name. Preserve both
                // declarations instead of merging its identity with a constructor.
                if (kind.equals("CONSTRUCTOR")) {
                    String constructorName = name;
                    boolean collision = model.members(parent).stream()
                            .map(model::declaration)
                            .anyMatch(member -> member.symbol().kind().equals("METHOD")
                                    && member.symbol().name().equals(constructorName));
                    if (collision) name = "<init>";
                }
                List<String> parameters = parameters(reference, value, executable);
                if (value.symbol().variadic() && !parameters.isEmpty()) {
                    String last = parameters.getLast();
                    if (last.endsWith("[]"))
                        parameters.set(
                                parameters.size() - 1,
                                last.substring(0, last.length() - 2) + "...");
                }
                id = owner + "#" + name + "(" + String.join(",", parameters) + ")";
            } else if (Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT").contains(kind))
                id = owner + "#" + value.symbol().name();
            else
                throw new IllegalArgumentException(
                        "declaration does not have a graph identity: " + kind);
        }
        ids.put(reference, id);
        return id;
    }

    /**
     * Source spelling preserves identity when a dependency is absent, without resolving its type.
     */
    private List<String> parameters(
            JavaModel.DeclarationRef reference, JavaDeclaration declaration, JavaType executable) {
        List<JavaNode> syntax =
                declaration.origin() == JavaDeclaration.Origin.SOURCE
                        ? model.syntax(reference)
                                .map(node -> node.trees("getParameters"))
                                .orElse(List.of())
                        : List.of();
        var parameters = new ArrayList<String>(executable.parameters().size());
        for (int index = 0; index < executable.parameters().size(); index++) {
            int type = executable.parameters().get(index);
            String name = erased(reference.source(), type);
            if (syntax.size() == executable.parameters().size()
                    && errorComponent(reference.source(), type, 0)) {
                String spelling = typeSpelling(syntax.get(index).tree("getType"), 0);
                if (!spelling.isEmpty()) name = importedSpelling(reference.source(), spelling);
            }
            parameters.add(name);
        }
        return parameters;
    }

    private boolean errorComponent(Path path, int reference, int depth) {
        if (depth > 128) return false;
        JavaType type = model.unit(path).types().get(reference);
        return type.kind() == JavaType.Kind.ERROR
                || (type.kind() == JavaType.Kind.ARRAY
                        && errorComponent(path, type.component(), depth + 1));
    }

    private static String typeSpelling(JavaNode node, int depth) {
        if (node == null || depth > 128) return "";
        return switch (node.kind()) {
            case "IDENTIFIER" -> node.text("getName");
            case "MEMBER_SELECT" -> {
                String owner = typeSpelling(node.tree("getExpression"), depth + 1);
                yield owner.isEmpty() ? "" : owner + "." + node.text("getIdentifier");
            }
            case "PARAMETERIZED_TYPE" -> typeSpelling(node.tree("getType"), depth + 1);
            case "ANNOTATED_TYPE" -> typeSpelling(node.tree("getUnderlyingType"), depth + 1);
            case "ARRAY_TYPE" -> {
                String component = typeSpelling(node.tree("getType"), depth + 1);
                yield component.isEmpty() ? "" : component + "[]";
            }
            default -> "";
        };
    }

    private String importedSpelling(Path path, String spelling) {
        int boundary = spelling.indexOf('.');
        int array = spelling.indexOf('[');
        if (boundary < 0 || (array >= 0 && array < boundary)) boundary = array;
        String first = boundary < 0 ? spelling : spelling.substring(0, boundary);
        String qualified = null;
        for (JavaNode entry : model.unit(path).tree().trees("getImports")) {
            if (entry.text("isStatic").equals("true")) continue;
            String name = typeSpelling(entry.tree("getQualifiedIdentifier"), 0);
            if (!name.endsWith("." + first)) continue;
            if (qualified != null && !qualified.equals(name)) return "<unresolved>";
            qualified = name;
        }
        return qualified == null ? spelling : qualified + spelling.substring(first.length());
    }

    private String type(JavaModel.DeclarationRef reference, JavaDeclaration value, int depth) {
        if (!value.canonicalName().isEmpty()) return value.canonicalName();
        JavaNode syntax =
                model.syntax(reference)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "local type has no original syntax"));
        JavaSyntax tree = model.syntax(reference.source());
        JavaNode parent =
                tree.parent(syntax)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "local type owner is unavailable"));
        if (value.symbol().name().isEmpty() && parent.kind().equals("NEW_CLASS"))
            return callback(reference.source(), parent, "anon", depth + 1);
        String owner =
                owner(reference.source(), syntax, depth + 1)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "local type owner is unavailable"));
        if (TYPES.contains(parent.kind())) return owner + "." + value.symbol().name();
        JavaSource.Position position = model.source(reference.source()).position(syntax.start());
        return owner
                + "$local@L"
                + position.line()
                + "C"
                + position.column()
                + ":"
                + value.symbol().name();
    }

    public Optional<String> owner(Path path, JavaNode node) {
        return owner(path, node, 0);
    }

    private Optional<String> owner(Path path, JavaNode node, int depth) {
        if (depth > 128) throw new IllegalArgumentException("owner nesting exceeds budget");
        JavaSyntax tree = model.syntax(path);
        for (JavaNode parent = tree.parent(node).orElse(null);
                parent != null;
                parent = tree.parent(parent).orElse(null)) {
            if (parent.kind().equals("LAMBDA_EXPRESSION"))
                return Optional.of(callback(path, parent, "lambda", depth + 1));
            if (parent.kind().equals("MEMBER_REFERENCE"))
                return Optional.of(callback(path, parent, "methodref", depth + 1));
            if (isInitializer(path, parent)) return Optional.of(initializer(path, parent));
            for (JavaModel.DeclarationRef reference : model.declarationsAt(path, parent.span())) {
                JavaDeclaration declaration = model.declaration(reference);
                String kind = declaration.symbol().kind();
                if ((TYPES.contains(kind)
                                || Set.of(
                                                "METHOD",
                                                "CONSTRUCTOR",
                                                "FIELD",
                                                "ENUM_CONSTANT",
                                                "RECORD_COMPONENT")
                                        .contains(kind))
                        && JavaModel.matches(parent, declaration.symbol()))
                    return Optional.of(declaration(reference, depth + 1));
            }
        }
        return Optional.empty();
    }

    public boolean isInitializer(Path path, JavaNode node) {
        return node.kind().equals("BLOCK")
                && node.span().present()
                && model.syntax(path)
                        .parent(node)
                        .map(parent -> TYPES.contains(parent.kind()))
                        .orElse(false);
    }

    public String initializer(Path path, JavaNode node) {
        if (!isInitializer(path, node))
            throw new IllegalArgumentException("not a source initializer");
        String owner = owner(path, node).orElseThrow();
        var position = model.source(path).position(node.start());
        return owner
                + "#"
                + (node.text("isStatic").equals("true") ? "<clinit>" : "<initializer>")
                + "@(line "
                + position.line()
                + ",col "
                + position.column()
                + ")";
    }

    public String callback(Path path, JavaNode node, String kind) {
        return callback(path, node, kind, 0);
    }

    private String callback(Path path, JavaNode node, String kind, int depth) {
        String owner =
                owner(path, node, depth + 1)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "callback owner is unavailable"));
        JavaSource.Position position = model.source(path).position(node.start());
        return owner + "$" + kind + "@L" + position.line() + "C" + position.column();
    }

    public String erased(Path path, int reference) {
        String name = JavaTypeNames.erased(model.unit(path).types(), reference);
        if (name.endsWith("[]")) {
            JavaType type = model.unit(path).types().get(reference);
            if (type.kind() == JavaType.Kind.ARRAY) return erased(path, type.component()) + "[]";
        }
        return model.type(name) instanceof JavaModel.FoundType found
                ? declaration(found.declaration())
                : name;
    }
}
