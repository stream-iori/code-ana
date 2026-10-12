package com.anatomist.core;

import com.anatomist.model.Annotation;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rewrites provider-owned symbols into provider/module/scope-qualified storage ids. */
public final class GraphIdentityRewriter {

    private GraphIdentityRewriter() {}

    public static void rewrite(
            ExtractionResult result,
            SourceIdentityResolver identities,
            Set<String> survivingNodeIds) {
        com.anatomist.provider.FactDefaults.normalize(result);
        Map<String, Map<String, Candidate>> candidates = new HashMap<>();
        if (survivingNodeIds != null) {
            for (String key : survivingNodeIds) {
                if (!NodeKeyFactory.isKey(key)) continue;
                String symbol = NodeKeyFactory.symbolId(key);
                addCandidate(
                        candidates,
                        NodeKeyFactory.providerId(key),
                        symbol,
                        new Candidate(key, NodeKeyFactory.identity(key), null));
            }
        }

        for (Node node : result.nodes) {
            String symbol =
                    node.symbolId != null
                            ? node.symbolId
                            : NodeKeyFactory.isKey(node.id)
                                    ? NodeKeyFactory.symbolId(node.id)
                                    : node.id;
            SourceIdentity identity = identities.resolve(node.sourceFile);
            node.symbolId = symbol;
            node.module = identity.module();
            node.scope = identity.scope().name();
            node.id = NodeKeyFactory.key(node.providerId, identity, symbol);
            addCandidate(
                    candidates,
                    node.providerId,
                    symbol,
                    new Candidate(node.id, identity, node.sourceFile));
        }

        for (int i = 0; i < result.unresolvedCallSites.size(); i++) {
            var site = result.unresolvedCallSites.get(i);
            if (NodeKeyFactory.isKey(site.callerId())) continue;
            Candidate resolved =
                    select(
                            candidates.get(candidateKey("java-core", site.callerId())),
                            identities.resolve(site.sourceFile()),
                            site.sourceFile());
            if (resolved != null)
                result.unresolvedCallSites.set(i, site.withCaller(resolved.key()));
        }

        for (Annotation annotation : result.annotations) {
            if (NodeKeyFactory.isKey(annotation.nodeId)) continue;
            SourceIdentity identity = identities.resolve(annotation.sourceFile);
            Candidate resolved =
                    select(
                            candidates.get(candidateKey(annotation.providerId, annotation.nodeId)),
                            identity,
                            annotation.sourceFile);
            if (resolved != null) {
                annotation.nodeId = resolved.key();
                if (annotation.sourceFile == null) annotation.sourceFile = resolved.sourceFile();
            }
        }

        for (Edge edge : result.edges) {
            SourceIdentity sourceIdentity = identities.resolve(edge.sourceFile);
            if (!NodeKeyFactory.isKey(edge.sourceId)) {
                Candidate resolved =
                        select(
                                candidates.get(candidateKey(edge.providerId, edge.sourceId)),
                                sourceIdentity,
                                edge.sourceFile);
                if (resolved != null) {
                    edge.sourceId = resolved.key();
                    if (edge.sourceFile == null) edge.sourceFile = resolved.sourceFile();
                }
            }
            if (!edge.isExternal && !NodeKeyFactory.isKey(edge.targetId)) {
                Candidate resolved =
                        select(
                                candidates.get(candidateKey(edge.providerId, edge.targetId)),
                                sourceIdentity,
                                null);
                if (resolved != null) edge.targetId = resolved.key();
            } else if (edge.isExternal && edge.externalTargetFqn != null) {
                String targetProvider =
                        edge.externalTargetProviderId == null
                                ? edge.providerId
                                : edge.externalTargetProviderId;
                Candidate resolved =
                        select(
                                candidates.get(
                                        candidateKey(targetProvider, edge.externalTargetFqn)),
                                sourceIdentity,
                                null);
                if (resolved != null) {
                    edge.targetId = resolved.key();
                    edge.externalTargetFqn = null;
                    edge.isExternal = false;
                    edge.resolution = null;
                }
            }
            if (!edge.isExternal && edge.targetId != null && !NodeKeyFactory.isKey(edge.targetId)) {
                // A project symbol exists in more than one identity and cannot be selected safely.
                edge.externalTargetFqn = edge.targetId;
                edge.externalTargetSymbol = edge.targetId;
                edge.externalTargetProviderId = edge.providerId;
                edge.externalTargetLanguage = edge.language;
                edge.targetId = null;
                edge.isExternal = true;
                edge.confidence = GraphConstants.Confidence.AMBIGUOUS;
                edge.resolution = GraphConstants.Resolution.SOURCE_FALLBACK;
            }
        }
    }

    private static void addCandidate(
            Map<String, Map<String, Candidate>> candidates,
            String providerId,
            String symbol,
            Candidate candidate) {
        Map<String, Candidate> byKey =
                candidates.computeIfAbsent(
                        candidateKey(providerId, symbol), ignored -> new LinkedHashMap<>());
        byKey.merge(
                candidate.key(),
                candidate,
                (existing, replacement) ->
                        existing.sourceFile() == null && replacement.sourceFile() != null
                                ? replacement
                                : existing);
    }

    private static String candidateKey(String providerId, String symbol) {
        String provider =
                providerId == null || providerId.isBlank()
                        ? NodeKeyFactory.DEFAULT_PROVIDER
                        : providerId;
        return provider + '\0' + symbol;
    }

    private static Candidate select(
            Map<String, Candidate> candidates, SourceIdentity source, String sourceFile) {
        if (candidates == null || candidates.isEmpty()) return null;
        Collection<Candidate> distinct = candidates.values();
        if (sourceFile != null) {
            List<Candidate> sameFile =
                    distinct.stream().filter(c -> sourceFile.equals(c.sourceFile())).toList();
            if (sameFile.size() == 1) return sameFile.get(0);
        }
        List<Candidate> exact = distinct.stream().filter(c -> c.identity().equals(source)).toList();
        if (exact.size() == 1) return exact.get(0);
        List<Candidate> moduleMain =
                distinct.stream()
                        .filter(
                                c ->
                                        c.identity().module().equals(source.module())
                                                && c.identity().scope() == SourceScope.MAIN)
                        .toList();
        if (moduleMain.size() == 1) return moduleMain.get(0);
        List<Candidate> uniqueMain =
                distinct.stream().filter(c -> c.identity().scope() == SourceScope.MAIN).toList();
        if (uniqueMain.size() == 1) return uniqueMain.get(0);
        return distinct.size() == 1 ? distinct.iterator().next() : null;
    }

    private record Candidate(String key, SourceIdentity identity, String sourceFile) {}
}
