package com.anatomist.framework.spring;

import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.core.SpringBeanParser.XmlConfigNode;
import com.anatomist.framework.DefaultProjectFactView;
import com.anatomist.framework.ProjectFactView;
import com.anatomist.json.Json;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.model.ProducerIds;
import com.anatomist.model.SymbolRef;
import com.anatomist.model.SymbolResolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps parsed Spring beans into graph nodes/edges. Unlike the Java source {@link Extractor}s this
 * runs as a separate pass (no {@code CompilationUnit}): it joins each bean's {@code class} FQN and
 * {@code ref} bean names against the set of node ids already known (this batch's Java CLASS nodes ∪
 * the DB), so internal vs. external edges obey the {@code edges} CHECK constraint.
 *
 * <p>Emitted graph:
 *
 * <ul>
 *   <li>one {@code BEAN} node per concrete bean (id {@code bean:<name>@<relXml>});
 *   <li>{@code DEFINED_BY}: BEAN → its class (internal if the class id is known, else external);
 *   <li>P0/P1 XML config tree nodes for property/constructor-arg/map/list/entry/
 *       ref/value/null/idref.
 *   <li>{@code XML_REFERS_TO}: XML_REF/XML_IDREF → referenced BEAN when known.
 *   <li>{@code WIRES}: ownerClass → refClass when the ref can be resolved.
 * </ul>
 */
public final class XmlBeanGraphExtractor {

    private final String scope;

    public XmlBeanGraphExtractor() {
        this(GraphConstants.Scope.MAIN);
    }

    public XmlBeanGraphExtractor(String scope) {
        this.scope = scope != null ? scope : GraphConstants.Scope.MAIN;
    }

    public void extract(
            List<ParsedBean> beans,
            Set<String> knownIds,
            String sourceFile,
            ExtractionResult result) {
        extractWithResolvedBeans(beans, knownIds, Map.of(), sourceFile, result);
    }

    public void extract(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, String> existingBeanClass,
            String sourceFile,
            ExtractionResult result) {
        Map<String, BeanRefTarget> existingBeans = new HashMap<>();
        for (Map.Entry<String, String> e : existingBeanClass.entrySet()) {
            existingBeans.put(e.getKey(), new BeanRefTarget(e.getKey(), e.getValue()));
        }
        extractWithResolvedBeans(beans, knownIds, existingBeans, sourceFile, result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ExtractionResult result) {
        int inferredEndLine =
                Math.max(1, beans.stream().mapToInt(ParsedBean::endLine).max().orElse(1));
        extractWithResolvedBeans(
                beans,
                knownIds,
                existingBeans,
                sourceFile,
                new ArtifactRange(inferredEndLine, 1),
                result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ArtifactRange artifactRange,
            ExtractionResult result) {
        extractWithResolvedBeans(
                beans,
                knownIds,
                existingBeans,
                sourceFile,
                artifactRange,
                new DefaultProjectFactView(knownIds, existingBeans),
                result);
    }

    public void extractWithResolvedBeans(
            List<ParsedBean> beans,
            Set<String> knownIds,
            Map<String, BeanRefTarget> existingBeans,
            String sourceFile,
            ArtifactRange artifactRange,
            ProjectFactView facts,
            ExtractionResult result) {
        // bean name → its declared class FQN, for resolving refs to classes.
        Map<String, String> beanClass = new HashMap<>();
        for (ParsedBean b : beans) {
            if (b.className() != null && b.factoryMethod() == null) {
                beanClass.putIfAbsent(b.name(), b.className());
            }
        }
        resolveFactoryProducts(beans, beanClass, existingBeans, facts, sourceFile);

        String artifactId = artifactNodeId(sourceFile);
        Node artifact = new Node();
        artifact.id = artifactId;
        artifact.label = sourceFile.substring(sourceFile.lastIndexOf('/') + 1);
        artifact.kind = GraphConstants.Kind.ARTIFACT;
        artifact.qualifiedName = sourceFile;
        artifact.sourceFile = sourceFile;
        artifact.sourceLocation = "L1";
        artifact.beginLine = 1;
        artifact.beginColumn = 1;
        artifact.endLine = artifactRange.endLine();
        artifact.endColumn = artifactRange.endColumn();
        artifact.sourceOrdinal = 0;
        artifact.scope = scope;
        artifact.producerId = ProducerIds.SPRING_XML;
        var artifactMetadata = new LinkedHashMap<String, Object>();
        artifactMetadata.put("format", "xml");
        artifactMetadata.put("mechanism", "spring.xml.beans");
        artifact.metadata = metadataJson(artifactMetadata);
        result.nodes.add(artifact);

        for (ParsedBean b : beans) {
            String beanId = beanNodeId(b.name(), sourceFile);
            String cls = b.className();
            String productClass = beanClass.get(b.name());
            boolean classKnown = cls != null && knownIds.contains(cls);

            Node n = new Node();
            n.id = beanId;
            n.label = b.name();
            n.kind = GraphConstants.Kind.BEAN;
            n.qualifiedName = b.name();
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + b.line();
            n.beginLine = b.line();
            n.beginColumn = b.column();
            n.endLine = b.endLine();
            n.endColumn = b.endColumn();
            n.sourceOrdinal = b.ordinal();
            n.scope = scope;
            n.producerId = ProducerIds.SPRING_XML;
            Map<String, Object> beanMetadata = new LinkedHashMap<>();
            beanMetadata.put("source", "xml");
            beanMetadata.putAll(b.attributes());
            if (cls != null) beanMetadata.put("className", cls);
            if (productClass != null) beanMetadata.put("productClass", productClass);
            beanMetadata.put("abstract", b.abstractBean());
            if (b.parent() != null) beanMetadata.put("parent", b.parent());
            if (b.factoryBean() != null) beanMetadata.put("factoryBean", b.factoryBean());
            if (b.factoryMethod() != null) beanMetadata.put("factoryMethod", b.factoryMethod());
            if (b.initMethod() != null) beanMetadata.put("initMethod", b.initMethod());
            if (b.destroyMethod() != null) beanMetadata.put("destroyMethod", b.destroyMethod());
            if (b.nestedIn() != null) beanMetadata.put("nestedIn", b.nestedIn());
            n.metadata = metadataJson(beanMetadata);
            result.nodes.add(n);

            Edge artifactContains =
                    baseEdge(GraphConstants.Relation.XML_CONTAINS, sourceFile, b.line());
            artifactContains.sourceId = artifactId;
            artifactContains.targetId = beanId;
            artifactContains.isExternal = false;
            artifactContains.sourceOrdinal = b.ordinal();
            result.edges.add(artifactContains);

            // DEFINED_BY: BEAN → class.
            if (cls != null && b.factoryMethod() == null) {
                Edge def = baseEdge(GraphConstants.Relation.DEFINED_BY, sourceFile, b.line());
                def.sourceId = beanId;
                if (classKnown) {
                    def.targetId = cls;
                    def.isExternal = false;
                } else {
                    def.externalTargetFqn = cls;
                    def.isExternal = true;
                    def.resolution = GraphConstants.Resolution.XML;
                }
                result.edges.add(def);
            }
            emitBeanReference(
                    beanId,
                    b.parent(),
                    GraphConstants.Relation.PARENT_BEAN,
                    sourceFile,
                    b.line(),
                    beanClass,
                    existingBeans,
                    result);
            emitBeanReference(
                    beanId,
                    b.factoryBean(),
                    GraphConstants.Relation.FACTORY_BEAN,
                    sourceFile,
                    b.line(),
                    beanClass,
                    existingBeans,
                    result);

            List<XmlConfigNode> constructorArgs =
                    b.children().stream()
                            .filter(child -> "constructor-arg".equals(child.kind))
                            .toList();
            List<String> argumentNodeIds = constructorArgumentNodeIds(beanId, b.children());
            if (b.factoryMethod() != null) {
                String factoryOwner =
                        b.factoryBean() == null
                                ? cls
                                : beanProductClass(b.factoryBean(), beanClass, existingBeans);
                SymbolRef reference =
                        symbolRef(
                                factoryOwner,
                                b.factoryMethod(),
                                "method",
                                constructorArgs,
                                b.factoryBean() == null,
                                "factory",
                                "spring.xml.factory-method",
                                sourceFile,
                                beanClass,
                                existingBeans);
                emitCallableRef(
                        beanId,
                        beanId,
                        "factory",
                        reference,
                        b.line(),
                        sourceFile,
                        argumentNodeIds,
                        true,
                        facts,
                        result);
            } else if (cls != null) {
                String simple = simpleName(cls);
                SymbolRef reference =
                        symbolRef(
                                cls,
                                simple,
                                "constructor",
                                constructorArgs,
                                null,
                                "constructor",
                                "spring.xml.constructor",
                                sourceFile,
                                beanClass,
                                existingBeans);
                emitCallableRef(
                        beanId,
                        beanId,
                        "constructor",
                        reference,
                        b.line(),
                        sourceFile,
                        argumentNodeIds,
                        false,
                        facts,
                        result);
            }
            if (productClass != null && b.initMethod() != null) {
                emitCallableRef(
                        beanId,
                        beanId,
                        "init",
                        new SymbolRef(
                                "jvm",
                                "java",
                                "method",
                                productClass,
                                b.initMethod(),
                                0,
                                List.of(),
                                false,
                                "init",
                                "spring.xml.init-method",
                                sourceFile),
                        b.line(),
                        sourceFile,
                        List.of(),
                        false,
                        facts,
                        result);
            }
            if (productClass != null && b.destroyMethod() != null) {
                emitCallableRef(
                        beanId,
                        beanId,
                        "destroy",
                        new SymbolRef(
                                "jvm",
                                "java",
                                "method",
                                productClass,
                                b.destroyMethod(),
                                0,
                                List.of(),
                                false,
                                "destroy",
                                "spring.xml.destroy-method",
                                sourceFile),
                        b.line(),
                        sourceFile,
                        List.of(),
                        false,
                        facts,
                        result);
            }

            List<XmlConfigNode> refs = new ArrayList<>();
            emitConfigTree(
                    beanId,
                    beanId,
                    b.children(),
                    sourceFile,
                    result,
                    beanClass,
                    existingBeans,
                    knownIds,
                    refs,
                    productClass,
                    facts);
            if (cls != null)
                emitWires(
                        cls,
                        classKnown,
                        refs,
                        beanClass,
                        existingBeans,
                        knownIds,
                        sourceFile,
                        result);
        }
    }

    /** One-past-end position of the complete resource snapshot. */
    public record ArtifactRange(int endLine, int endColumn) {
        public ArtifactRange {
            if (endLine < 1 || endColumn < 1) {
                throw new IllegalArgumentException("artifact range must be positive");
            }
        }
    }

    public static String artifactNodeId(String sourceFile) {
        return "artifact:xml:" + sourceFile;
    }

    public static String beanNodeId(String beanName, String sourceFile) {
        return "bean:" + beanName + "@" + sourceFile;
    }

    private static Edge baseEdge(String relation, String sourceFile, int line) {
        Edge e = new Edge();
        e.relation = relation;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + line;
        if (line > 0) e.beginLine = line;
        e.producerId = ProducerIds.SPRING_XML;
        return e;
    }

    private void emitConfigTree(
            String beanId,
            String parentId,
            List<XmlConfigNode> children,
            String sourceFile,
            ExtractionResult result,
            Map<String, String> xmlBeanClass,
            Map<String, BeanRefTarget> existingBeans,
            Set<String> knownIds,
            List<XmlConfigNode> refs,
            String ownerClass,
            ProjectFactView facts) {
        for (int i = 0; i < children.size(); i++) {
            XmlConfigNode child = children.get(i);
            String id = configNodeId(parentId, child, i);
            Node n = new Node();
            n.id = id;
            n.label = labelOf(child);
            n.kind = kindOf(child.kind);
            n.qualifiedName = id;
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + child.line;
            n.beginLine = child.line;
            n.beginColumn = Math.max(1, child.column);
            n.endLine = child.endLine > 0 ? child.endLine : child.line;
            n.endColumn = child.endColumn > 0 ? child.endColumn : Math.max(1, child.column);
            n.sourceOrdinal = child.ordinal;
            n.scope = scope;
            n.producerId = ProducerIds.SPRING_XML;
            n.metadata = metadataJson(metadataOf(child));
            result.nodes.add(n);

            Edge contains =
                    baseEdge(
                            parentId.equals(beanId)
                                    ? GraphConstants.Relation.CONFIGURES
                                    : GraphConstants.Relation.XML_CONTAINS,
                            sourceFile,
                            child.line);
            contains.sourceId = parentId;
            contains.targetId = id;
            contains.isExternal = false;
            result.edges.add(contains);

            if (("ref".equals(child.kind) || "idref".equals(child.kind)) && child.bean != null) {
                refs.add(child);
                result.edges.add(
                        refEdge(id, child, sourceFile, xmlBeanClass, existingBeans, knownIds));
            }
            if ("property".equals(child.kind)
                    && child.name != null
                    && !child.name.isBlank()
                    && ownerClass != null) {
                String setter =
                        "set"
                                + Character.toUpperCase(child.name.charAt(0))
                                + child.name.substring(1);
                SymbolRef reference =
                        symbolRef(
                                ownerClass,
                                setter,
                                "method",
                                List.of(child),
                                false,
                                "setter",
                                "spring.xml.property",
                                sourceFile,
                                xmlBeanClass,
                                existingBeans);
                emitCallableBinding(
                        id, beanId, reference, child.line, sourceFile, false, facts, result);
            }
            emitConfigTree(
                    beanId,
                    id,
                    child.children,
                    sourceFile,
                    result,
                    xmlBeanClass,
                    existingBeans,
                    knownIds,
                    refs,
                    ownerClass,
                    facts);
        }
    }

    private static void emitWires(
            String ownerClass,
            boolean ownerKnown,
            List<XmlConfigNode> refs,
            Map<String, String> xmlBeanClass,
            Map<String, BeanRefTarget> existingBeans,
            Set<String> knownIds,
            String sourceFile,
            ExtractionResult result) {
        if (!ownerKnown) return;
        for (XmlConfigNode ref : refs) {
            String targetClass = xmlBeanClass.get(ref.bean);
            BeanRefTarget target =
                    targetClass == null ? existingBeans.get("bean:" + ref.bean) : null;
            if (targetClass == null && target != null) targetClass = target.className();
            if (targetClass == null) continue;
            Edge w = baseEdge(GraphConstants.Relation.WIRES, sourceFile, ref.line);
            w.sourceId = ownerClass;
            if (knownIds.contains(targetClass)) {
                w.targetId = targetClass;
                w.isExternal = false;
            } else {
                w.externalTargetFqn = targetClass;
                w.isExternal = true;
                w.resolution = GraphConstants.Resolution.XML;
            }
            result.edges.add(w);
        }
    }

    private static Edge refEdge(
            String sourceId,
            XmlConfigNode ref,
            String sourceFile,
            Map<String, String> xmlBeanClass,
            Map<String, BeanRefTarget> existingBeans,
            Set<String> knownIds) {
        Edge e = baseEdge(GraphConstants.Relation.XML_REFERS_TO, sourceFile, ref.line);
        e.sourceId = sourceId;
        String xmlTarget =
                xmlBeanClass.containsKey(ref.bean) ? beanNodeId(ref.bean, sourceFile) : null;
        BeanRefTarget existingTarget = existingBeans.get("bean:" + ref.bean);
        if (xmlTarget != null) {
            e.targetId = xmlTarget;
            e.isExternal = false;
        } else if (existingTarget != null && knownIds.contains(existingTarget.beanId())) {
            e.targetId = existingTarget.beanId();
            e.isExternal = false;
        } else {
            e.externalTargetFqn = ref.bean;
            e.isExternal = true;
            e.resolution = GraphConstants.Resolution.XML;
        }
        return e;
    }

    private static String configNodeId(String parentId, XmlConfigNode n, int ordinal) {
        String segment =
                switch (n.kind) {
                    case "property" -> "property:" + safe(n.name);
                    case "constructor-arg" -> "constructor-arg:" + n.index;
                    case "entry" ->
                            "entry:" + safe(n.key != null ? n.key : String.valueOf(n.index));
                    case "ref" -> "ref:" + safe(n.bean);
                    case "idref" -> "idref:" + safe(n.bean);
                    case "value" -> "value:" + ordinal;
                    case "null" -> "null:" + ordinal;
                    default -> n.kind;
                };
        return parentId + "/" + segment + "@I" + ordinal;
    }

    private static List<String> constructorArgumentNodeIds(
            String beanId, List<XmlConfigNode> children) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            XmlConfigNode child = children.get(i);
            if ("constructor-arg".equals(child.kind)) ids.add(configNodeId(beanId, child, i));
        }
        return List.copyOf(ids);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "_";
        return value.replace('/', '_').replace(' ', '_');
    }

    private static String labelOf(XmlConfigNode n) {
        return switch (n.kind) {
            case "property" -> n.name == null ? "property" : n.name;
            case "constructor-arg" -> "constructor-arg[" + n.index + "]";
            case "entry" -> n.key == null ? "entry" : n.key;
            case "ref" -> n.bean == null ? "ref" : n.bean;
            case "idref" -> n.bean == null ? "idref" : n.bean;
            case "value" -> n.value == null ? "value" : n.value;
            default -> n.kind;
        };
    }

    private static String kindOf(String kind) {
        return switch (kind) {
            case "property" -> GraphConstants.Kind.XML_PROPERTY;
            case "constructor-arg" -> GraphConstants.Kind.XML_CONSTRUCTOR_ARG;
            case "map" -> GraphConstants.Kind.XML_MAP;
            case "list" -> GraphConstants.Kind.XML_LIST;
            case "entry" -> GraphConstants.Kind.XML_ENTRY;
            case "ref" -> GraphConstants.Kind.XML_REF;
            case "idref" -> GraphConstants.Kind.XML_IDREF;
            case "value" -> GraphConstants.Kind.XML_VALUE;
            case "null" -> GraphConstants.Kind.XML_NULL;
            case "bean" -> GraphConstants.Kind.BEAN;
            default -> "XML_" + kind.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        };
    }

    private static Map<String, Object> metadataOf(XmlConfigNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xmlKind", n.kind);
        if (n.name != null) m.put("name", n.name);
        if (n.key != null) m.put("key", n.key);
        if (n.index != null) m.put("index", n.index);
        if (n.bean != null) m.put("bean", n.bean);
        if (n.value != null) m.put("value", n.value);
        if (n.type != null) m.put("type", n.type);
        m.put("ordinal", n.ordinal);
        return m;
    }

    private static void emitBeanReference(
            String sourceId,
            String name,
            String relation,
            String sourceFile,
            int line,
            Map<String, String> xmlBeanClass,
            Map<String, BeanRefTarget> existingBeans,
            ExtractionResult result) {
        if (name == null || name.isBlank()) return;
        Edge edge = baseEdge(relation, sourceFile, line);
        edge.sourceId = sourceId;
        String local = xmlBeanClass.containsKey(name) ? beanNodeId(name, sourceFile) : null;
        BeanRefTarget existing = existingBeans.get("bean:" + name);
        if (local != null) {
            edge.targetId = local;
            edge.isExternal = false;
        } else if (existing != null) {
            edge.targetId = existing.beanId();
            edge.isExternal = false;
        } else {
            edge.externalTargetFqn = name;
            edge.isExternal = true;
            edge.resolution = GraphConstants.Resolution.XML;
        }
        result.edges.add(edge);
    }

    private static String metadataJson(Map<String, ?> metadata) {
        return Json.writeCompact(metadata);
    }

    private void emitCallableRef(
            String beanId,
            String parentId,
            String role,
            SymbolRef reference,
            int line,
            String sourceFile,
            List<String> argumentNodeIds,
            boolean definesBean,
            ProjectFactView facts,
            ExtractionResult result) {
        String id = beanId + "/callable-ref:" + role;
        Node node = new Node();
        node.id = id;
        node.label = role + ":" + reference.memberName();
        node.kind = GraphConstants.Kind.XML_CALLABLE_REF;
        node.qualifiedName = id;
        node.sourceFile = sourceFile;
        node.sourceLocation = "L" + line;
        node.beginLine = line;
        node.beginColumn = 1;
        node.endLine = line;
        node.endColumn = 1;
        node.scope = scope;
        node.producerId = ProducerIds.SPRING_XML;
        SymbolResolution resolution = facts.resolve(reference);
        Map<String, Object> metadata = bindingMetadata(reference, resolution, 0);
        metadata.put("argumentNodeIds", argumentNodeIds);
        node.metadata = metadataJson(metadata);
        result.nodes.add(node);
        Edge configures = baseEdge(GraphConstants.Relation.CONFIGURES, sourceFile, line);
        configures.sourceId = parentId;
        configures.targetId = id;
        configures.isExternal = false;
        result.edges.add(configures);
        emitResolvedEdges(id, beanId, reference, resolution, line, sourceFile, definesBean, result);
    }

    private static void emitCallableBinding(
            String sourceId,
            String beanId,
            SymbolRef reference,
            int line,
            String sourceFile,
            boolean definesBean,
            ProjectFactView facts,
            ExtractionResult result) {
        emitResolvedEdges(
                sourceId,
                beanId,
                reference,
                facts.resolve(reference),
                line,
                sourceFile,
                definesBean,
                result);
    }

    private static void emitResolvedEdges(
            String sourceId,
            String beanId,
            SymbolRef reference,
            SymbolResolution resolution,
            int line,
            String sourceFile,
            boolean definesBean,
            ExtractionResult result) {
        if (resolution.candidates().isEmpty()) {
            Edge edge = baseEdge(GraphConstants.Relation.BINDS_TO, sourceFile, line);
            edge.sourceId = sourceId;
            edge.externalTargetFqn = unresolvedTarget(reference);
            edge.isExternal = true;
            edge.resolution = GraphConstants.Resolution.XML;
            edge.confidence = GraphConstants.Confidence.CONFIGURED;
            edge.metadata = metadataJson(bindingMetadata(reference, resolution, 0));
            result.edges.add(edge);
            if (definesBean) result.edges.add(definedBy(beanId, edge, sourceFile, line));
            return;
        }
        for (int i = 0; i < resolution.candidates().size(); i++) {
            Edge edge = baseEdge(GraphConstants.Relation.BINDS_TO, sourceFile, line);
            edge.sourceId = sourceId;
            edge.targetId = resolution.candidates().get(i);
            edge.isExternal = false;
            edge.confidence =
                    SymbolResolution.AMBIGUOUS.equals(resolution.status())
                            ? GraphConstants.Confidence.AMBIGUOUS
                            : GraphConstants.Confidence.CONFIGURED;
            edge.metadata = metadataJson(bindingMetadata(reference, resolution, i));
            result.edges.add(edge);
            if (definesBean) result.edges.add(definedBy(beanId, edge, sourceFile, line));
        }
    }

    private static Edge definedBy(String beanId, Edge binding, String sourceFile, int line) {
        Edge edge = baseEdge(GraphConstants.Relation.DEFINED_BY, sourceFile, line);
        edge.sourceId = beanId;
        edge.targetId = binding.targetId;
        edge.externalTargetFqn = binding.externalTargetFqn;
        edge.isExternal = binding.isExternal;
        edge.resolution = binding.resolution;
        edge.confidence = binding.confidence;
        edge.metadata = binding.metadata;
        return edge;
    }

    private static Map<String, Object> bindingMetadata(
            SymbolRef reference, SymbolResolution resolution, int candidateIndex) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", reference.role());
        out.put("mechanism", reference.mechanism());
        out.put("symbolRef", symbolRefMap(reference));
        out.put("resolutionStatus", resolution.status());
        out.put("candidateCount", resolution.candidates().size());
        if (!resolution.candidates().isEmpty()) out.put("candidateIndex", candidateIndex);
        return out;
    }

    private static Map<String, Object> symbolRefMap(SymbolRef reference) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runtime", reference.runtime());
        out.put("languageHint", reference.languageHint());
        out.put("symbolKind", reference.symbolKind());
        out.put("owner", reference.owner());
        out.put("memberName", reference.memberName());
        out.put("arity", reference.arity());
        out.put("parameterTypeHints", reference.parameterTypeHints());
        out.put("staticRequirement", reference.staticRequirement());
        out.put("role", reference.role());
        out.put("mechanism", reference.mechanism());
        return out;
    }

    private static SymbolRef symbolRef(
            String owner,
            String member,
            String kind,
            List<XmlConfigNode> arguments,
            Boolean staticRequirement,
            String role,
            String mechanism,
            String sourceFile,
            Map<String, String> beanClass,
            Map<String, BeanRefTarget> existingBeans) {
        List<String> hints =
                arguments.stream()
                        .map(argument -> typeHint(argument, beanClass, existingBeans))
                        .toList();
        return new SymbolRef(
                "jvm",
                "java",
                kind,
                owner,
                member,
                arguments.size(),
                hints,
                staticRequirement,
                role,
                mechanism,
                sourceFile);
    }

    private static String typeHint(
            XmlConfigNode argument,
            Map<String, String> beanClass,
            Map<String, BeanRefTarget> existingBeans) {
        if (argument.type != null && !argument.type.isBlank()) return argument.type;
        for (XmlConfigNode child : argument.children) {
            if (!"ref".equals(child.kind) || child.bean == null) continue;
            String type = beanProductClass(child.bean, beanClass, existingBeans);
            if (type != null) return type;
        }
        return "";
    }

    private static String beanProductClass(
            String name, Map<String, String> beanClass, Map<String, BeanRefTarget> existingBeans) {
        String type = beanClass.get(name);
        if (type != null) return type;
        BeanRefTarget target = existingBeans.get("bean:" + name);
        return target == null ? null : target.className();
    }

    private static String unresolvedTarget(SymbolRef reference) {
        StringBuilder out = new StringBuilder();
        out.append(reference.owner() == null ? "?" : reference.owner())
                .append('#')
                .append(reference.memberName() == null ? "?" : reference.memberName())
                .append('(');
        for (int i = 0; i < (reference.arity() == null ? 0 : reference.arity()); i++) {
            if (i > 0) out.append(',');
            String hint =
                    i < reference.parameterTypeHints().size()
                            ? reference.parameterTypeHints().get(i)
                            : "";
            out.append(hint == null || hint.isBlank() ? "?" : hint);
        }
        return out.append(')').toString();
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        int dollar = fqn.lastIndexOf('$');
        return fqn.substring(Math.max(dot, dollar) + 1);
    }

    private static void resolveFactoryProducts(
            List<ParsedBean> beans,
            Map<String, String> beanClass,
            Map<String, BeanRefTarget> existingBeans,
            ProjectFactView facts,
            String sourceFile) {
        for (int pass = 0; pass <= beans.size(); pass++) {
            boolean changed = false;
            for (ParsedBean bean : beans) {
                if (bean.factoryMethod() == null || beanClass.containsKey(bean.name())) continue;
                String owner =
                        bean.factoryBean() == null
                                ? bean.className()
                                : beanProductClass(bean.factoryBean(), beanClass, existingBeans);
                if (owner == null) continue;
                List<XmlConfigNode> arguments =
                        bean.children().stream()
                                .filter(child -> "constructor-arg".equals(child.kind))
                                .toList();
                SymbolRef reference =
                        symbolRef(
                                owner,
                                bean.factoryMethod(),
                                "method",
                                arguments,
                                bean.factoryBean() == null,
                                "factory",
                                "spring.xml.factory-method",
                                sourceFile,
                                beanClass,
                                existingBeans);
                SymbolResolution resolution = facts.resolve(reference);
                Set<String> returnTypes =
                        resolution.candidates().stream()
                                .map(facts::returnType)
                                .filter(java.util.Objects::nonNull)
                                .collect(
                                        java.util.stream.Collectors.toCollection(
                                                java.util.LinkedHashSet::new));
                if (returnTypes.size() == 1) {
                    beanClass.put(bean.name(), returnTypes.iterator().next());
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    /** Resolve bean product types across all XML resources before per-resource graph emission. */
    public static Map<String, String> resolveProductClasses(
            List<ParsedBean> beans,
            Map<String, BeanRefTarget> existingBeans,
            ProjectFactView facts,
            String sourceFile) {
        Map<String, String> products = new HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        Map<String, Long> counts =
                beans.stream()
                        .collect(
                                java.util.stream.Collectors.groupingBy(
                                        ParsedBean::name,
                                        java.util.LinkedHashMap::new,
                                        java.util.stream.Collectors.counting()));
        counts.forEach(
                (name, count) -> {
                    if (count > 1) ambiguous.add(name);
                });
        for (ParsedBean bean : beans) {
            if (bean.factoryMethod() != null
                    || bean.className() == null
                    || ambiguous.contains(bean.name())) continue;
            String prior = products.putIfAbsent(bean.name(), bean.className());
            if (prior != null && !prior.equals(bean.className())) {
                products.remove(bean.name());
                ambiguous.add(bean.name());
            }
        }
        List<ParsedBean> unambiguous =
                beans.stream().filter(bean -> !ambiguous.contains(bean.name())).toList();
        resolveFactoryProducts(unambiguous, products, existingBeans, facts, sourceFile);
        return Map.copyOf(products);
    }
}
