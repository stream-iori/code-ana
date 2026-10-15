package com.anatomist.extract;

import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Detached-model declaration pass. Does not construct or resolve a compatibility AST. */
public final class JavaDeclarationFacts {
    private static final Set<String> TYPES =
            Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD");
    private static final Set<String> VALUES = Set.of("FIELD", "ENUM_CONSTANT", "RECORD_COMPONENT");
    private static final List<String> MODIFIERS =
            List.of(
                    "public",
                    "protected",
                    "private",
                    "abstract",
                    "static",
                    "final",
                    "transient",
                    "volatile",
                    "synchronized",
                    "native",
                    "strictfp",
                    "default",
                    "sealed",
                    "non-sealed");
    private final JavaModel model;
    private final JavaIdentities identities;
    private final Path project;
    private final String module;
    private final String scope;

    public JavaDeclarationFacts(JavaModel model, Path project, String module, String scope) {
        this.model = Objects.requireNonNull(model);
        identities = new JavaIdentities(model);
        this.project = project.toAbsolutePath().normalize();
        this.module = module;
        this.scope = scope;
    }

    public void extract(Path path, ExtractionResult result) {
        JavaUnit unit = model.unit(path);
        // Verify the compiler snapshot before any fact can enter a staging batch.
        model.source(path);
        for (JavaModel.DeclarationRef reference : model.declarations(path)) {
            JavaDeclaration value = model.declaration(reference);
            String kind = value.symbol().kind();
            if (!(TYPES.contains(kind)
                    || VALUES.contains(kind)
                    || kind.equals("METHOD")
                    || kind.equals("CONSTRUCTOR"))) continue;
            if (kind.equals("FIELD") && recordBackingField(reference)) continue;
            emit(reference, unit, result);
        }
        for (JavaNode node : model.syntax(path).nodes())
            if (identities.isInitializer(path, node)) initializer(unit, node, result);
    }

    private void initializer(JavaUnit unit, JavaNode syntax, ExtractionResult result) {
        String owner = identities.owner(unit.path(), syntax).orElseThrow();
        boolean isStatic = syntax.text("isStatic").equals("true");
        var node = new Node();
        node.id = identities.initializer(unit.path(), syntax);
        node.qualifiedName = node.id;
        node.label = isStatic ? "static initializer" : "initializer";
        node.kind = "SOURCE_REGION";
        node.sourceFile = project.relativize(unit.path()).toString().replace('\\', '/');
        node.module = module;
        node.scope = scope;
        node.producerId = ProducerIds.JAVA_CORE;
        node.pkg =
                unit.tree().tree("getPackageName") == null
                        ? null
                        : model.source(unit.path())
                                .slice(unit.tree().tree("getPackageName").span());
        coordinates(node, unit.path(), syntax.span());
        node.metadata = Json.writeCompact(Map.of("bindingResolved", true));
        result.nodes.add(node);
        var declaration = new Declaration();
        declaration.symbolId = node.id;
        declaration.qualifiedName = node.id;
        declaration.label = node.label;
        declaration.kind = node.kind;
        declaration.declarationKind = "initializer";
        declaration.declaringType = owner;
        declaration.sourceFile = node.sourceFile;
        declaration.sourceLocation = node.sourceLocation;
        declaration.beginLine = node.beginLine;
        declaration.beginColumn = node.beginColumn;
        declaration.endLine = node.endLine;
        declaration.endColumn = node.endColumn;
        declaration.module = module;
        declaration.scope = scope;
        declaration.bindingResolved = true;
        declaration.modifiers = isStatic ? List.of("static") : List.of();
        declaration.declaredModifiers = declaration.modifiers;
        declaration.visibility = "package";
        declaration.producerId = ProducerIds.JAVA_CORE;
        for (var parent = model.syntax(unit.path()).parent(syntax).orElse(null);
                parent != null;
                parent = model.syntax(unit.path()).parent(parent).orElse(null))
            if (TYPES.contains(parent.kind())) declaration.nestingDepth++;
        declaration.directMember = declaration.nestingDepth == 1;
        result.declarations.add(declaration);
        var edge = Edge.reference(owner, node.id, false);
        edge.relation = GraphConstants.Relation.CONTAINS;
        edge.sourceFile = node.sourceFile;
        edge.sourceLocation = node.sourceLocation;
        edge.producerId = node.producerId;
        result.edges.add(edge);
    }

    private boolean recordBackingField(JavaModel.DeclarationRef reference) {
        JavaDeclaration value = model.declaration(reference);
        return model.parent(reference)
                .map(model::declaration)
                .map(
                        owner ->
                                owner.members().stream()
                                        .anyMatch(
                                                member ->
                                                        member.symbol()
                                                                        .kind()
                                                                        .equals("RECORD_COMPONENT")
                                                                && member.symbol()
                                                                        .name()
                                                                        .equals(
                                                                                value.symbol()
                                                                                        .name())))
                .orElse(false);
    }

    private void emit(JavaModel.DeclarationRef reference, JavaUnit unit, ExtractionResult result) {
        JavaDeclaration value = model.declaration(reference);
        String id = identities.declaration(reference);
        String owner =
                model.parent(reference)
                        .map(identities::declaration)
                        .orElseGet(
                                () ->
                                        model.syntax(reference)
                                                .flatMap(
                                                        node -> identities.owner(unit.path(), node))
                                                .orElse(null));
        Node node = new Node();
        node.id = id;
        node.label = label(reference);
        node.kind = kind(value);
        node.qualifiedName =
                TYPES.contains(value.symbol().kind()) || VALUES.contains(value.symbol().kind())
                        ? id
                        : owner + "#" + node.label;
        node.pkg =
                unit.tree().tree("getPackageName") == null
                        ? null
                        : model.source(unit.path())
                                .slice(unit.tree().tree("getPackageName").span());
        node.sourceFile = project.relativize(unit.path()).toString().replace('\\', '/');
        node.module = module;
        node.scope = scope;
        node.producerId = ProducerIds.JAVA_CORE;
        JavaGeneratedOrigin generated = unit.extensions().generated().get(id);
        if (generated != null) node.producerId = generated.producerId();
        coordinates(node, unit.path(), value.span());
        node.metadata = Json.writeCompact(metadata(reference, unit, owner));
        result.nodes.add(node);
        result.declarations.add(declaration(reference, node, owner));
        if (owner != null) {
            Edge contains = Edge.reference(owner, id, false);
            contains.relation = GraphConstants.Relation.CONTAINS;
            contains.sourceFile = node.sourceFile;
            contains.sourceLocation = node.sourceLocation;
            contains.producerId = node.producerId;
            if (generated != null) contains.confidence = generated.confidence();
            result.edges.add(contains);
        }
    }

    private Declaration declaration(JavaModel.DeclarationRef reference, Node node, String owner) {
        JavaDeclaration value = model.declaration(reference);
        Declaration declaration = new Declaration();
        declaration.symbolId = node.id;
        declaration.qualifiedName = node.qualifiedName;
        declaration.label = node.label;
        declaration.kind = node.kind;
        String kind = value.symbol().kind();
        declaration.declarationKind = TYPES.contains(kind) ? "type" : kind.toLowerCase(Locale.ROOT);
        declaration.typeKind =
                TYPES.contains(kind)
                        ? (kind.equals("ANNOTATION_TYPE")
                                ? "annotation"
                                : kind.toLowerCase(Locale.ROOT))
                        : null;
        declaration.declaringType = owner;
        declaration.sourceFile = node.sourceFile;
        declaration.sourceLocation = node.sourceLocation;
        declaration.beginLine = node.beginLine;
        declaration.beginColumn = node.beginColumn;
        declaration.endLine = node.endLine;
        declaration.endColumn = node.endColumn;
        declaration.module = module;
        declaration.scope = scope;
        declaration.synthetic = value.origin() != JavaDeclaration.Origin.SOURCE;
        declaration.bindingResolved = bindingResolved(reference);
        declaration.modifiers = ordered(value.symbol().modifiers());
        List<String> declared =
                model.syntax(reference)
                        .map(syntax -> syntax.tree("getModifiers"))
                        .map(
                                modifiers ->
                                        modifiers.values("getFlags").stream()
                                                .map(
                                                        flag ->
                                                                flag.toString()
                                                                        .toLowerCase(Locale.ROOT))
                                                .toList())
                        .orElse(List.of());
        // javac inserts public/static/final into the enum constant's syntax flags.
        // None can be written on a constant; retain them only as implicit modifiers.
        declaration.declaredModifiers =
                kind.equals("ENUM_CONSTANT") ? List.of() : ordered(declared);
        declaration.implicitModifiers =
                declaration.modifiers.stream()
                        .filter(modifier -> !declaration.declaredModifiers.contains(modifier))
                        .toList();
        declaration.visibility =
                declaration.modifiers.stream()
                        .filter(
                                modifier ->
                                        Set.of("public", "protected", "private").contains(modifier))
                        .findFirst()
                        .orElse("package");
        declaration.nestingDepth = depth(reference);
        declaration.directMember = !TYPES.contains(kind) && declaration.nestingDepth == 1;
        declaration.producerId = node.producerId;
        return declaration;
    }

    private int depth(JavaModel.DeclarationRef reference) {
        int count = 0;
        JavaNode node = model.syntax(reference).orElse(null);
        if (node != null) {
            JavaSyntax syntax = model.syntax(reference.source());
            for (JavaNode parent = syntax.parent(node).orElse(null);
                    parent != null;
                    parent = syntax.parent(parent).orElse(null))
                if (TYPES.contains(parent.kind())) count++;
            return count;
        }
        return model.parent(reference).map(parent -> depth(parent) + 1).orElse(0);
    }

    private static String hierarchyName(JavaUnit unit, int reference) {
        if (reference < 0) return null;
        var type = unit.types().get(reference);
        if (type.kind() != JavaType.Kind.DECLARED) return null;
        String name = type.name();
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    private Map<String, Object> metadata(
            JavaModel.DeclarationRef reference, JavaUnit unit, String owner) {
        JavaDeclaration value = model.declaration(reference);
        String kind = value.symbol().kind();
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (TYPES.contains(kind)) {
            metadata.put("isAbstract", value.symbol().modifiers().contains("abstract"));
            metadata.put("isInterface", kind.equals("INTERFACE") || kind.equals("ANNOTATION_TYPE"));
            if (!kind.equals("ENUM")) {
                String superclass = hierarchyName(unit, value.superclass());
                if (superclass != null && !superclass.equals("Object"))
                    metadata.put("superClass", superclass);
                List<String> interfaces =
                        value.interfaces().stream()
                                .map(type -> hierarchyName(unit, type))
                                .filter(java.util.Objects::nonNull)
                                .toList();
                if (!interfaces.isEmpty()) metadata.put("interfaces", interfaces);
            }
            if (kind(value).equals(GraphConstants.Kind.ANONYMOUS_CLASS)) {
                int base = value.superclass();
                if ((base < 0 || unit.types().get(base).name().equals("java.lang.Object"))
                        && !value.interfaces().isEmpty()) base = value.interfaces().getFirst();
                if (base >= 0 && unit.types().get(base).kind() == JavaType.Kind.DECLARED)
                    metadata.put("baseType", unit.types().get(base).display());
            }

            if (kind.equals("ENUM") && !value.symbol().name().isEmpty())
                metadata.put(
                        "constants",
                        value.members().stream()
                                .filter(member -> member.symbol().kind().equals("ENUM_CONSTANT"))
                                .map(member -> member.symbol().name())
                                .toList());
        } else if (VALUES.contains(kind)) {
            String fieldType = identities.erased(unit.path(), value.symbol().declaredType());
            if (fieldType.equals("<unresolved>")) {
                var written =
                        model.syntax(reference).map(node -> node.tree("getType")).orElse(null);
                if (written != null && written.span().present())
                    fieldType = model.source(unit.path()).slice(written.span());
            }
            metadata.put("type", fieldType);
            metadata.put("isStatic", value.symbol().modifiers().contains("static"));
            metadata.put(
                    "isFinal",
                    value.symbol().modifiers().contains("final")
                            || kind.equals("RECORD_COMPONENT"));
            if (kind.equals("RECORD_COMPONENT")) metadata.put("isRecordComponent", true);
        } else {
            JavaType method = unit.types().get(value.symbol().declaredType());
            List<JavaDeclaration> parameters =
                    value.members().stream()
                            .filter(member -> member.symbol().kind().equals("PARAMETER"))
                            .toList();
            List<Map<String, String>> descriptions = new ArrayList<>();
            for (int i = 0; i < method.parameters().size(); i++) {
                Map<String, String> description = new LinkedHashMap<>();
                description.put(
                        "name",
                        i < parameters.size() ? parameters.get(i).symbol().name() : "arg" + i);
                description.put("type", unit.types().get(method.parameters().get(i)).display());
                descriptions.add(description);
            }
            metadata.put("parameters", descriptions);
            metadata.put(
                    "returnType",
                    kind.equals("CONSTRUCTOR")
                            ? null
                            : unit.types().get(method.returns()).display());
            metadata.put("isStatic", value.symbol().modifiers().contains("static"));
            metadata.put("isAbstract", value.symbol().modifiers().contains("abstract"));
            metadata.put("isConstructor", kind.equals("CONSTRUCTOR"));
            if (model.parent(reference)
                    .map(model::declaration)
                    .map(parent -> parent.symbol().kind().equals("ANNOTATION_TYPE"))
                    .orElse(false)) metadata.put("isAnnotationMember", true);
            metadata.put("isAccessor", accessor(reference, method));
            metadata.put("modifiers", ordered(value.symbol().modifiers()));
            metadata.put(
                    "signature",
                    label(reference)
                            + "("
                            + String.join(
                                    ", ",
                                    descriptions.stream()
                                            .map(
                                                    parameter ->
                                                            parameter.get("type")
                                                                    + " "
                                                                    + parameter.get("name"))
                                            .toList())
                            + ")");
        }
        if (!bindingResolved(reference)) metadata.put("bindingResolved", false);
        if (!value.complete()) metadata.put("evidenceComplete", false);
        if (value.deprecated()) metadata.put("deprecated", true);
        if (value.origin() != JavaDeclaration.Origin.SOURCE) {
            metadata.put("isSynthetic", true);
            metadata.put("generator", "java");
            metadata.put("generatorMode", "signature");
            metadata.put("generatedFrom", owner);
            metadata.put("source_owner_id", owner);
            metadata.put("bodyAvailable", false);
        }
        String id = identities.declaration(reference);
        var generated = unit.extensions().generated().get(id);
        if (generated != null) {
            metadata.put("isSynthetic", true);
            metadata.put("generator", generated.generator());
            metadata.put("generatorMode", generated.generatorMode());
            metadata.put("generatedFrom", generated.generatedFrom());
            metadata.put("source_owner_id", owner);
            metadata.put("bodyAvailable", generated.bodyAvailable());
            metadata.put("confidence", generated.confidence());
        }
        metadata.putAll(unit.extensions().declarationMetadata().getOrDefault(id, Map.of()));
        return metadata;
    }

    private boolean bindingResolved(JavaModel.DeclarationRef reference) {
        var value = model.declaration(reference);
        if (value.origin() == JavaDeclaration.Origin.MODEL) return false;
        var types = model.unit(reference.source()).types();
        if (TYPES.contains(value.symbol().kind()))
            return types.get(value.symbol().declaredType()).kind() == JavaType.Kind.DECLARED;
        if (Set.of("METHOD", "CONSTRUCTOR").contains(value.symbol().kind())) {
            var executable = types.get(value.symbol().declaredType());
            return executable.kind() == JavaType.Kind.EXECUTABLE
                    && executable.parameters().stream()
                            .noneMatch(
                                    parameter ->
                                            JavaTypeNames.erased(types, parameter)
                                                    .contains("<unresolved>"));
        }
        return VALUES.contains(value.symbol().kind());
    }

    private boolean accessor(JavaModel.DeclarationRef reference, JavaType method) {
        var declaration = model.declaration(reference);
        if (!declaration.symbol().kind().equals("METHOD")) return false;
        String name = declaration.symbol().name();
        var types = model.unit(reference.source()).types();
        String returns = JavaTypeNames.erased(types, method.returns());
        int parameters = method.parameters().size();
        if (name.startsWith("get")
                && name.length() > 3
                && parameters == 0
                && !Set.of("void", "<unresolved>").contains(returns)) return true;
        if (name.startsWith("is")
                && name.length() > 2
                && parameters == 0
                && Set.of("boolean", "java.lang.Boolean").contains(returns)) return true;
        if (name.startsWith("set")
                && name.length() > 3
                && parameters == 1
                && returns.equals("void")) return true;
        return parameters == 0
                && model.parent(reference)
                        .map(model::declaration)
                        .filter(owner -> owner.symbol().kind().equals("RECORD"))
                        .map(
                                owner ->
                                        owner.members().stream()
                                                .anyMatch(
                                                        member ->
                                                                member.symbol()
                                                                                .kind()
                                                                                .equals(
                                                                                        "RECORD_COMPONENT")
                                                                        && member.symbol()
                                                                                .name()
                                                                                .equals(name)
                                                                        && JavaTypeNames.erased(
                                                                                        types,
                                                                                        member.symbol()
                                                                                                .declaredType())
                                                                                .equals(returns)))
                        .orElse(false);
    }

    private String label(JavaModel.DeclarationRef reference) {
        JavaDeclaration value = model.declaration(reference);
        if (value.symbol().kind().equals("CONSTRUCTOR"))
            return model.parent(reference).map(model::declaration).orElseThrow().symbol().name();
        return value.symbol().name().isEmpty() ? "$anon" : value.symbol().name();
    }

    private static String kind(JavaDeclaration value) {
        return switch (value.symbol().kind()) {
            case "ANNOTATION_TYPE" -> GraphConstants.Kind.ANNOTATION;
            case "RECORD_COMPONENT" -> GraphConstants.Kind.FIELD;
            case "CLASS", "ENUM" ->
                    value.symbol().name().isEmpty()
                            ? GraphConstants.Kind.ANONYMOUS_CLASS
                            : value.symbol().kind();
            default -> value.symbol().kind();
        };
    }

    private static List<String> ordered(List<String> modifiers) {
        return MODIFIERS.stream().filter(modifiers::contains).toList();
    }

    private void coordinates(Node node, Path path, SourceSpan span) {
        if (!span.present()) return;
        JavaSource.Position begin = model.source(path).position(span.start()),
                end = model.source(path).position(Math.max(span.start(), span.end() - 1));
        node.beginLine = begin.line();
        node.beginColumn = begin.column();
        node.endLine = end.line();
        node.endColumn = end.column();
        node.sourceLocation = "L" + begin.line();
        node.sourceOrdinal = 0;
    }
}
