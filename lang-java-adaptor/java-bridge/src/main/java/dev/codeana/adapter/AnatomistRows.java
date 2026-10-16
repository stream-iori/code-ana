package dev.codeana.adapter;

import com.anatomist.core.*;
import com.anatomist.model.*;
import com.anatomist.provider.FactDefaults;

import dev.codeana.common.GraphArchive;
import dev.codeana.common.Json;
import dev.codeana.graph.GraphRecords;

import java.io.*;
import java.util.*;

/** Anatomist dff2e395 staging bind order, transported without JDBC or Fact IR. */
public final class AnatomistRows {
    private final GraphArchive output;
    private final CapturedProject project;
    private final SourceIdentityResolver identities;
    private final ExtractionResult batch = new ExtractionResult();
    private int files;
    private final List<IndexDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Map<String, Object>> replacements = new LinkedHashMap<>();
    private Map<String, SourceIdentity> resourceIdentities = Map.of();

    public AnatomistRows(CapturedProject project, GraphArchive output) {
        this.project = project;
        this.output = output;
        List<SourceRoot> roots = new ArrayList<>(project.roots());
        for (var context : project.contexts)
            for (var resource : context.resources())
                roots.add(new SourceRoot(resource.file(), context.module(), context.scope()));
        identities = SourceIdentityResolver.fromRoots(project.root, roots);
    }

    private SourceIdentity identity(String source) {
        SourceIdentity resource = source == null ? null : resourceIdentities.get(source);
        return resource == null ? identities.resolve(source) : resource;
    }

    public void extraction(ExtractionResult result, CapturedProject.Context context) {
        Map<String, SourceIdentity> identities = new HashMap<>();
        for (var source : context.resources())
            identities.put(source.path(), new SourceIdentity(context.module(), context.scope()));
        resourceIdentities = identities;
        try {
            extraction(result);
        } finally {
            resourceIdentities = Map.of();
        }
    }

    private final class Values {
        final GraphRecords.Row.Builder row;

        Values(GraphRecords.Table table) {
            row = GraphRecords.Row.newBuilder().setTable(table);
        }

        void put(int i, GraphRecords.Cell value) {
            while (row.getCellsCount() < i) row.addCells(GraphRecords.Cell.getDefaultInstance());
            row.setCells(i - 1, value);
        }

        void setString(int i, String value) {
            put(
                    i,
                    value == null
                            ? GraphRecords.Cell.getDefaultInstance()
                            : GraphRecords.Cell.newBuilder().setText(value).build());
        }

        void setInt(int i, int value) {
            setLong(i, value);
        }

        void setLong(int i, long value) {
            put(i, GraphRecords.Cell.newBuilder().setInteger(value).build());
        }

        void setNull(int i, int ignored) {
            put(i, GraphRecords.Cell.getDefaultInstance());
        }

        void addBatch() throws IOException {
            output.add(row.build());
        }
    }

    public void row(GraphRecords.Table table, Object... values) {
        try {
            Values row = new Values(table);
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                if (value instanceof Number n) row.setLong(i + 1, n.longValue());
                else row.setString(i + 1, value == null ? null : value.toString());
            }
            row.addBatch();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void unit(com.anatomist.extract.JavaModelExtractor.UnitFacts unit) {
        var source = project.source(unit.source());
        for (var diagnostic : unit.diagnostics()) diagnostic(diagnostic);
        row(
                GraphRecords.Table.FILE_CACHE,
                "java-core",
                source.path(),
                source.hash(),
                33,
                unit.facts().nodes.size(),
                unit.facts().edges.size(),
                java.nio.file.Path.of(source.file().toString()).toFile().length(),
                unit.contractHash());
        batch.addFacts(unit.facts());
        if (++files >= 32 || batch.factCount() >= 10000) flush();
    }

    public void flush() {
        if (batch.factCount() > 0) {
            extraction(batch);
            batch.clearFacts();
        }
        files = 0;
    }

    public void extraction(ExtractionResult result) {
        FactDefaults.normalize(result);
        Map<String, Declaration> ranges = new HashMap<>();
        Map<String, String> sourceFiles = new HashMap<>();
        for (Declaration d : result.declarations)
            if (d.symbolId != null && d.beginLine != null)
                ranges.putIfAbsent(d.symbolId + "\n" + d.sourceFile, d);
        for (Node n : result.nodes) {
            String symbol = symbolOf(n);
            Declaration d = ranges.get(symbol + "\n" + n.sourceFile);
            if (d != null && n.beginLine == null) {
                n.beginLine = d.beginLine;
                n.beginColumn = d.beginColumn;
                n.endLine = d.endLine;
                n.endColumn = d.endColumn;
                n.sourceOrdinal = 0;
            }
            if (symbol != null && n.sourceFile != null)
                sourceFiles.putIfAbsent(symbol, n.sourceFile);
            if (n.id != null && n.sourceFile != null) sourceFiles.putIfAbsent(n.id, n.sourceFile);
        }
        try {
            for (Node n : result.nodes) bindNode(new Values(GraphRecords.Table.NODE), n, false);
            for (Edge e : result.edges) bindEdge(new Values(GraphRecords.Table.EDGE), e, false);
            for (Declaration d : result.declarations)
                bindDeclaration(new Values(GraphRecords.Table.DECLARATION), d);
            for (Annotation a : result.annotations)
                bindAnnotation(new Values(GraphRecords.Table.ANNOTATION), a, false);
            for (AnnotationMetaRelation a : result.annotationMetaRelations)
                bindAnnotationMeta(new Values(GraphRecords.Table.ANNOTATION_META), a);
            for (SemanticAnnotation a : result.semanticAnnotations)
                bindSemantic(
                        new Values(GraphRecords.Table.SEMANTIC_ANNOTATION),
                        a,
                        a.sourceFile == null ? sourceFiles.get(a.nodeId) : a.sourceFile,
                        false);
            for (UnresolvedCallSite site : result.unresolvedCallSites) {
                SourceIdentity identity = identity(site.sourceFile());
                row(
                        GraphRecords.Table.UNRESOLVED_CALL,
                        site.callerId(),
                        site.sourceFile(),
                        site.beginLine(),
                        site.beginColumn(),
                        site.endLine(),
                        site.endColumn(),
                        site.ordinal(),
                        site.syntaxTarget(),
                        site.receiverStaticType(),
                        site.dispatchKind(),
                        site.context(),
                        site.metadata(),
                        site.producerId(),
                        identity.module(),
                        identity.scope().name(),
                        NodeKeyFactory.isKey(site.callerId()) ? site.callerId() : null);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void diagnostic(IndexDiagnostic d) {
        diagnostics.add(d);
    }

    private void storedDiagnostic(IndexDiagnostic d) {
        row(
                GraphRecords.Table.DIAGNOSTIC,
                d.severity(),
                d.code(),
                d.phase(),
                d.sourceFile(),
                d.module(),
                d.scope(),
                d.symbol(),
                d.language(),
                d.providerId(),
                d.providerReason(),
                d.reasonCode(),
                d.sampleDetails(),
                d.count(),
                d.sample());
    }

    public void finish() {
        long unresolved = 0;
        for (var c : AnalysisCoverage.summarize(diagnostics)) {
            row(
                    GraphRecords.Table.COVERAGE,
                    c.sourceFile(),
                    c.module(),
                    c.scope(),
                    c.language(),
                    c.providerId(),
                    c.capability(),
                    c.status(),
                    c.occurrences(),
                    c.groups(),
                    c.codes(),
                    c.codeCounts(),
                    c.detailsTruncated() ? 1 : 0);
            if (c.capability().equals("AGGREGATE"))
                for (var count :
                        Json.map(com.anatomist.json.Json.parseTree(c.codeCounts())).entrySet())
                    if (ResolutionDiagnostics.isReasonCode(count.getKey()))
                        unresolved += ((Number) count.getValue()).longValue();
        }
        IndexDiagnosticRetention.retain(diagnostics).forEach(this::storedDiagnostic);
        if (unresolved > 0)
            storedDiagnostic(
                    new IndexDiagnostic(
                            "info",
                            "UNRESOLVED_SYMBOLS",
                            "RESOLUTION",
                            null,
                            null,
                            null,
                            null,
                            unresolved,
                            "Aggregate of lossless file resolution coverage."));
        for (var provider :
                com.anatomist.provider.LanguageProviderRegistry.builtIns().providers()) {
            var d = provider.descriptor();
            var limitations = new TreeMap<String, List<String>>();
            d.limitations()
                    .forEach(
                            (key, value) -> limitations.put(key, value.stream().sorted().toList()));
            String operations =
                    com.anatomist.json.Json.writeCompact(d.operations().stream().sorted().toList());
            String limits = com.anatomist.json.Json.writeCompact(limitations);
            String extensions =
                    com.anatomist.json.Json.writeCompact(
                            d.fileExtensions().stream().sorted().toList());
            String hash =
                    "sha256:"
                            + Json.sha(
                                    String.join(
                                                    "\n",
                                                    d.providerId(),
                                                    d.language(),
                                                    d.version(),
                                                    extensions,
                                                    operations,
                                                    limits)
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            row(
                    GraphRecords.Table.PROVIDER,
                    d.providerId(),
                    d.language(),
                    d.version(),
                    operations,
                    limits,
                    hash);
        }
    }

    public void provenance(String adapter) {
        row(GraphRecords.Table.PROJECT_META, "adapter", adapter);
    }

    public List<Map<String, Object>> owners() {
        return List.copyOf(replacements.values());
    }

    public void replace(CapturedProject.Source s) {
        replacements.put(
                s.world() + "\n" + s.path(),
                Json.object(
                        "world",
                        s.world(),
                        "producer",
                        "javac-java8-v2",
                        "kind",
                        "source",
                        "id",
                        s.path()));
    }

    public void replaceDeleted(String path, String world) {
        replacements.put(
                world + "\n" + path,
                Json.object(
                        "world",
                        world,
                        "producer",
                        "javac-java8-v2",
                        "kind",
                        "source",
                        "id",
                        path));
    }

    public Map<String, Object> statistics() {
        return Json.object("graph_records", output.size(), "graph_bytes", output.bytes());
    }

    private void bindNode(Values statement, Node node, boolean normalized) throws IOException {
        String symbol = symbolOf(node);
        SourceIdentity identity =
                normalized ? NodeKeyFactory.identity(node.id) : identity(node.sourceFile);
        String id = normalized ? node.id : NodeKeyFactory.key(node.providerId, identity, symbol);
        int i = 1;
        statement.setString(i++, id);
        statement.setString(i++, symbol);
        statement.setString(i++, node.domain);
        statement.setString(i++, node.language);
        statement.setString(i++, node.providerId);
        statement.setString(i++, node.entityKind);
        statement.setString(i++, node.languageKind);
        statement.setString(i++, node.label);
        statement.setString(i++, node.kind);
        statement.setString(i++, node.qualifiedName);
        statement.setString(i++, node.pkg);
        statement.setString(i++, node.namespace);
        statement.setString(i++, node.sourceFile == null ? "" : node.sourceFile);
        statement.setString(i++, node.sourceLocation);
        setNullableInt(statement, i++, node.beginLine);
        setNullableInt(statement, i++, node.beginColumn);
        setNullableInt(statement, i++, node.endLine);
        setNullableInt(statement, i++, node.endColumn);
        setNullableInt(statement, i++, node.sourceOrdinal);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setString(i++, node.metadata);
        statement.setString(i++, methodArityKey(symbol));
        statement.setString(i++, producer(node.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindEdge(Values statement, Edge edge, boolean normalized) throws IOException {
        SourceIdentity identity = identity(edge.sourceFile);
        com.anatomist.model.EdgeTarget target = edge.target();
        String internalTarget =
                switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal internal -> internal.nodeId();
                    case com.anatomist.model.EdgeTarget.External ignored -> null;
                };
        String externalTarget =
                switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal ignored -> null;
                    case com.anatomist.model.EdgeTarget.External external -> external.fqn();
                };
        String resolution =
                switch (target) {
                    case com.anatomist.model.EdgeTarget.Internal ignored -> null;
                    case com.anatomist.model.EdgeTarget.External external -> external.resolution();
                };
        boolean external = target instanceof com.anatomist.model.EdgeTarget.External;
        boolean sourceKey = normalized || NodeKeyFactory.isKey(edge.sourceId);
        boolean targetKey = normalized || NodeKeyFactory.isKey(internalTarget);
        int i = 1;
        statement.setString(i++, edge.sourceId);
        statement.setString(i++, internalTarget);
        statement.setString(i++, externalTarget);
        statement.setString(i++, edge.externalTargetSymbol);
        statement.setString(i++, edge.externalTargetLanguage);
        statement.setString(i++, edge.externalTargetProviderId);
        statement.setString(i++, edge.relation);
        statement.setString(i++, edge.semantic);
        statement.setString(i++, edge.mechanism);
        statement.setString(i++, edge.language);
        statement.setString(i++, edge.providerId);
        statement.setString(i++, edge.callKind);
        statement.setString(
                i++,
                edge.confidence == null ? GraphConstants.Confidence.EXTRACTED : edge.confidence);
        statement.setString(i++, resolution);
        statement.setString(i++, edge.context);
        statement.setInt(i++, external ? 1 : 0);
        statement.setString(i++, edge.sourceFile);
        statement.setString(i++, edge.sourceLocation);
        setNullableInt(statement, i++, edge.beginLine);
        setNullableInt(statement, i++, edge.beginColumn);
        setNullableInt(statement, i++, edge.endLine);
        setNullableInt(statement, i++, edge.endColumn);
        setNullableInt(statement, i++, edge.sourceOrdinal);
        statement.setString(i++, edge.syntaxTarget);
        statement.setString(i++, edge.receiverStaticType);
        statement.setString(i++, edge.metadata);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setInt(i++, sourceKey ? 1 : 0);
        statement.setInt(i++, targetKey ? 1 : 0);
        statement.setString(i++, sourceKey ? edge.sourceId : null);
        statement.setString(i++, !external && targetKey ? internalTarget : null);
        statement.setString(i++, methodArityKey(externalTarget));
        statement.setString(i++, producer(edge.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindAnnotation(Values statement, Annotation annotation, boolean normalized)
            throws IOException {
        SourceIdentity identity = identity(annotation.sourceFile);
        boolean key = normalized || NodeKeyFactory.isKey(annotation.nodeId);
        int i = 1;
        statement.setString(i++, annotation.nodeId);
        statement.setString(i++, annotation.annotationFqn);
        statement.setString(
                i++, annotation.rawName == null ? annotation.annotationFqn : annotation.rawName);
        statement.setString(i++, annotation.attributes);
        statement.setString(i++, annotation.targetKind == null ? "entity" : annotation.targetKind);
        statement.setString(i++, annotation.targetPath);
        statement.setString(i++, annotation.language == null ? "java" : annotation.language);
        statement.setString(
                i++, annotation.providerId == null ? "java-core" : annotation.providerId);
        statement.setString(
                i++, annotation.mechanism == null ? "java.annotation" : annotation.mechanism);
        statement.setString(
                i++, annotation.resolutionStatus == null ? "exact" : annotation.resolutionStatus);
        statement.setString(i++, annotation.sourceFile);
        statement.setString(i++, annotation.sourceLocation);
        setNullableInt(statement, i++, annotation.beginLine);
        setNullableInt(statement, i++, annotation.beginColumn);
        setNullableInt(statement, i++, annotation.endLine);
        setNullableInt(statement, i++, annotation.endColumn);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setInt(i++, key ? 1 : 0);
        statement.setString(i++, key ? annotation.nodeId : null);
        statement.setString(i, producer(annotation.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private static void bindAnnotationMeta(Values statement, AnnotationMetaRelation relation)
            throws IOException {
        int i = 1;
        statement.setString(i++, relation.annotationFqn);
        statement.setString(i++, relation.metaAnnotationFqn);
        statement.setString(
                i++, relation.rawName == null ? relation.metaAnnotationFqn : relation.rawName);
        statement.setString(i++, relation.language == null ? "java" : relation.language);
        statement.setString(i++, relation.providerId == null ? "java-core" : relation.providerId);
        statement.setString(
                i++, relation.mechanism == null ? "java.annotation.meta" : relation.mechanism);
        statement.setString(
                i++, relation.resolutionStatus == null ? "exact" : relation.resolutionStatus);
        statement.setString(i++, relation.sourceFile);
        statement.setString(i++, relation.sourceLocation);
        statement.setString(i, producer(relation.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private void bindSemantic(
            Values statement, SemanticAnnotation semantic, String sourceFile, boolean normalized)
            throws IOException {
        SourceIdentity identity = identity(sourceFile);
        boolean key = normalized || NodeKeyFactory.isKey(semantic.nodeId);
        int i = 1;
        statement.setString(i++, semantic.nodeId);
        statement.setString(i++, semantic.category);
        statement.setString(i++, semantic.businessLabel);
        statement.setString(i++, semantic.businessDescription);
        statement.setString(i++, semantic.domainContext);
        statement.setString(i++, semantic.source);
        statement.setString(i++, semantic.confidence);
        statement.setString(i++, sourceFile);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setInt(i++, key ? 1 : 0);
        statement.setString(i++, key ? semantic.nodeId : null);
        statement.setString(i++, producer(semantic.producerId, ProducerIds.JAVA_SEMANTICS));
        statement.addBatch();
    }

    private void bindDeclaration(Values statement, Declaration declaration) throws IOException {
        SourceIdentity identity = identity(declaration.sourceFile);
        int i = 1;
        statement.setString(i++, declaration.symbolId);
        statement.setString(i++, declaration.domain);
        statement.setString(i++, declaration.language);
        statement.setString(i++, declaration.providerId);
        statement.setString(i++, declaration.entityKind);
        statement.setString(i++, declaration.languageKind);
        statement.setString(i++, declaration.qualifiedName);
        statement.setString(i++, declaration.label);
        statement.setString(i++, declaration.kind);
        statement.setString(i++, declaration.declarationKind);
        statement.setString(i++, declaration.typeKind);
        statement.setString(i++, declaration.visibility);
        statement.setString(i++, com.anatomist.json.Json.writeCompact(declaration.modifiers));
        statement.setString(
                i++, com.anatomist.json.Json.writeCompact(declaration.declaredModifiers));
        statement.setString(
                i++, com.anatomist.json.Json.writeCompact(declaration.implicitModifiers));
        statement.setString(i++, declaration.declaringType);
        statement.setString(i++, declaration.namespace);
        statement.setString(i++, declaration.sourceFile);
        statement.setString(i++, declaration.sourceLocation);
        setNullableInt(statement, i++, declaration.beginLine);
        setNullableInt(statement, i++, declaration.beginColumn);
        setNullableInt(statement, i++, declaration.endLine);
        setNullableInt(statement, i++, declaration.endColumn);
        statement.setString(i++, identity.module());
        statement.setString(i++, identity.scope().name());
        statement.setInt(i++, declaration.nestingDepth);
        statement.setInt(i++, declaration.directMember ? 1 : 0);
        statement.setInt(i++, declaration.synthetic ? 1 : 0);
        statement.setInt(i++, declaration.bindingResolved ? 1 : 0);
        statement.setString(i, producer(declaration.producerId, ProducerIds.JAVA_CORE));
        statement.addBatch();
    }

    private static String symbolOf(Node node) {
        if (node.symbolId != null) return node.symbolId;
        return NodeKeyFactory.isKey(node.id) ? NodeKeyFactory.symbolId(node.id) : node.id;
    }

    private static String producer(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String methodArityKey(String methodId) {
        if (methodId == null) return null;
        int open = methodId.indexOf('(');
        int hash = methodId.lastIndexOf('#', open >= 0 ? open : methodId.length());
        int close = methodId.lastIndexOf(')');
        if (hash < 0 || open < hash || close < open) return null;
        String params = methodId.substring(open + 1, close).trim();
        int arity = params.isEmpty() ? 0 : params.split(",", -1).length;
        return methodId.substring(0, open) + "/" + arity;
    }

    private static void setNullableInt(Values statement, int index, Integer value)
            throws IOException {
        if (value == null) statement.setNull(index, 0);
        else statement.setInt(index, value);
    }
}
