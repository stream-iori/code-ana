package com.anatomist.extract;

import com.anatomist.core.asmsolver.JavaBinaryRepository;
import com.anatomist.core.java.*;
import com.anatomist.json.Json;
import com.anatomist.model.*;

import java.nio.file.Path;
import java.util.*;

/** Overrides confirmed by Elements.overrides, including instantiated generic inheritance. */
public final class JavaOverrideFacts {
    public record Gap(String owner, SourceSpan span, String reason) {}

    private final JavaModel model;
    private final JavaIdentities identities;
    private final Path project;
    private final JavaBinaryRepository binaries;

    public JavaOverrideFacts(JavaModel model, Path project) {
        this(model, project, null);
    }

    public JavaOverrideFacts(JavaModel model, Path project, JavaBinaryRepository binaries) {
        this.model = Objects.requireNonNull(model);
        this.identities = new JavaIdentities(model);
        this.project = project.toAbsolutePath().normalize();
        this.binaries = binaries;
    }

    public List<Gap> extract(Path path, ExtractionResult result) {
        List<Gap> gaps = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        var recovery = new JavaModeledCallRecovery(model, model.unit(path), binaries);
        for (var reference : model.declarations(path)) {
            var declaration = model.declaration(reference);
            if (!declaration.symbol().kind().equals("METHOD")) continue;
            String source = identities.declaration(reference);
            for (String reason : declaration.overrides().incompleteReasons())
                gaps.add(new Gap(source, declaration.span(), reason));
            for (var target : declaration.overrides().targets()) {
                var lookup = model.type(target.binaryOwner());
                Edge edge = new Edge();
                edge.sourceId = source;
                edge.relation = GraphConstants.Relation.OVERRIDES;
                edge.confidence = GraphConstants.Confidence.EXTRACTED;
                edge.producerId = ProducerIds.JAVA_SEMANTICS;
                edge.sourceFile = project.relativize(path).toString().replace('\\', '/');
                String targetId;
                if (lookup instanceof JavaModel.FoundType found) {
                    var members =
                            model.members(found.declaration()).stream()
                                    .filter(
                                            member -> {
                                                var symbol = model.declaration(member).symbol();
                                                return symbol.kind().equals("METHOD")
                                                        && symbol.name()
                                                                .equals(target.symbol().name())
                                                        && symbol.descriptor()
                                                                .equals(
                                                                        target.symbol()
                                                                                .descriptor());
                                            })
                                    .toList();
                    if (members.size() != 1) {
                        gaps.add(new Gap(source, declaration.span(), "override-member-ambiguous"));
                        continue;
                    }
                    targetId = identities.declaration(members.getFirst());
                    edge.targetId = targetId;
                } else if (lookup instanceof JavaModel.AmbiguousType) {
                    gaps.add(new Gap(source, declaration.span(), "override-owner-ambiguous"));
                    continue;
                } else {
                    targetId =
                            JavaTypeNames.binaryCallableId(
                                    model.unit(path).types(),
                                    target.symbol(),
                                    target.binaryOwner());
                    edge.externalTargetFqn = targetId;
                    edge.isExternal = true;
                    edge.resolution = GraphConstants.Resolution.CLASSPATH;
                }
                if (emitted.add(source + "\u0000" + targetId)) result.edges.add(edge);
            }
            if (declaration.origin() == JavaDeclaration.Origin.MODEL
                    || modeledAncestor(reference, declaration.symbol().name())) {
                var recovered = recovery.modeledOverrides(reference);
                for (String reason : recovered.incompleteReasons())
                    gaps.add(new Gap(source, declaration.span(), reason));
                for (var target : recovered.targets()) {
                    String targetId =
                            target.source() == null
                                    ? target.external()
                                    : identities.declaration(target.source());
                    if (!emitted.add(source + "\u0000" + targetId)) continue;
                    var edge = new Edge();
                    edge.sourceId = source;
                    edge.targetId = target.source() == null ? null : targetId;
                    edge.isExternal = target.source() == null;
                    edge.externalTargetFqn = edge.isExternal ? targetId : null;
                    edge.relation = GraphConstants.Relation.OVERRIDES;
                    edge.confidence = GraphConstants.Confidence.INFERRED;
                    edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
                    edge.producerId = ProducerIds.JAVA_SEMANTICS;
                    edge.sourceFile = project.relativize(path).toString().replace('\\', '/');
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("bindingResolved", false);
                    metadata.put("evidenceComplete", false);
                    metadata.put("modeledInput", true);
                    var origin = model.unit(path).extensions().generated().get(source);
                    if (origin != null) {
                        metadata.put("generator", origin.generator());
                        metadata.put("generatorMode", origin.generatorMode());
                        metadata.put("generatedFrom", origin.generatedFrom());
                        metadata.put("bodyAvailable", origin.bodyAvailable());
                    }
                    edge.metadata = Json.writeCompact(metadata);
                    result.edges.add(edge);
                }
            }
        }
        return List.copyOf(gaps);
    }

    private boolean modeledAncestor(JavaModel.DeclarationRef method, String name) {
        var owner = model.parent(method).orElse(null);
        if (owner == null || !model.hasModeledDeclarations()) return false;
        ArrayDeque<JavaModel.DeclarationRef> pending = new ArrayDeque<>();
        Set<JavaModel.DeclarationRef> visited = new HashSet<>();
        pending.add(owner);
        int work = 0;
        while (!pending.isEmpty()) {
            if (visited.size() >= 256 || ++work > 100_000) return false;
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (!current.equals(owner)) {
                for (var member : model.members(current)) {
                    if (++work > 100_000) return false;
                    var declaration = model.declaration(member);
                    if (declaration.origin() == JavaDeclaration.Origin.MODEL
                            && declaration.symbol().kind().equals("METHOD")
                            && declaration.symbol().name().equals(name)) return true;
                }
            }
            var declaration = model.declaration(current);
            var types = model.unit(current.source()).types();
            List<Integer> parents = new ArrayList<>(declaration.interfaces());
            if (declaration.superclass() >= 0) parents.add(declaration.superclass());
            for (int parent : parents)
                if (model.type(JavaTypeNames.erased(types, parent))
                        instanceof JavaModel.FoundType found) pending.add(found.declaration());
        }
        return false;
    }
}
