package com.anatomist.extract;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.model.BeanRefTarget;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pins the XML→graph mapping. Given parsed beans, the set of node ids already known (Java CLASS
 * nodes from this batch ∪ DB), and the relative xml path, the extractor emits: one BEAN node per
 * bean, a DEFINED_BY edge BEAN→class (internal when the class is known, external otherwise), and
 * CLASS→CLASS WIRES edges for each resolvable bean ref. WIRES is only emitted when the owner bean's
 * class is a known internal node.
 */
class XmlBeanExtractorTest {

    private static final String XML = "service/src/main/resources/applicationContext.xml";

    private static List<ParsedBean> parse(String xml) {
        return new SpringBeanParser().parse(new StringReader(xml));
    }

    private static Edge edge(ExtractionResult r, String relation, String src) {
        return r.edges.stream()
                .filter(e -> e.relation.equals(relation) && src.equals(e.sourceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + relation + " edge from " + src));
    }

    @Test
    void emitsBeanNodeWithStableId() {
        ParsedBean b =
                parse("<beans><bean id='orderSvc' class='com.example.OrderService'/></beans>")
                        .get(0);
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b), Set.of("com.example.OrderService"), XML, r);

        Node n = r.nodes.stream().filter(x -> "BEAN".equals(x.kind)).findFirst().orElseThrow();
        assertEquals("bean:orderSvc@" + XML, n.id);
        assertEquals("BEAN", n.kind);
        assertEquals("orderSvc", n.label);
        assertEquals(XML, n.sourceFile);
        assertEquals("L1", n.sourceLocation);
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                x ->
                                        "ARTIFACT".equals(x.kind)
                                                && x.id.equals(
                                                        XmlBeanExtractor.artifactNodeId(XML))));
        assertNotNull(n.beginColumn);
        assertNotNull(n.endColumn);
    }

    @Test
    void emitsAbstractParentAndFactoryFactsWithoutFakeTypeBinding() {
        List<ParsedBean> beans =
                parse(
                        "<beans>"
                                + "<bean id='template' abstract='true'/>"
                                + "<bean id='factory' class='p.Factory'/>"
                                + "<bean id='made' parent='template' factory-bean='factory' factory-method='create'/>"
                                + "</beans>");
        ExtractionResult result = new ExtractionResult();
        new XmlBeanExtractor().extract(beans, Set.of("p.Factory"), XML, result);
        String made = XmlBeanExtractor.beanNodeId("made", XML);
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        made.equals(edge.sourceId)
                                                && "PARENT_BEAN".equals(edge.relation)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        made.equals(edge.sourceId)
                                                && "FACTORY_BEAN".equals(edge.relation)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        made.equals(edge.sourceId)
                                                && "DEFINED_BY".equals(edge.relation)
                                                && edge.isExternal
                                                && "p.Factory#create()"
                                                        .equals(edge.externalTargetFqn)));
        assertTrue(
                result.edges.stream()
                        .anyMatch(
                                edge ->
                                        "BINDS_TO".equals(edge.relation)
                                                && edge.sourceId.equals(
                                                        made + "/callable-ref:factory")
                                                && edge.isExternal));
    }

    @Test
    void definedByInternalWhenClassKnown() {
        ParsedBean b =
                parse("<beans><bean id='svc' class='com.example.OrderService'/></beans>").get(0);
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b), Set.of("com.example.OrderService"), XML, r);

        Edge e = edge(r, "DEFINED_BY", "bean:svc@" + XML);
        assertFalse(e.isExternal);
        assertEquals("com.example.OrderService", e.targetId);
        assertNull(e.externalTargetFqn);
    }

    @Test
    void definedByExternalWhenClassUnknown() {
        ParsedBean b =
                parse(
                                "<beans><bean id='ds' class='org.apache.commons.dbcp.BasicDataSource'/></beans>")
                        .get(0);
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b), Set.of(), XML, r);

        Edge e = edge(r, "DEFINED_BY", "bean:ds@" + XML);
        assertTrue(e.isExternal);
        assertEquals("org.apache.commons.dbcp.BasicDataSource", e.externalTargetFqn);
        assertNull(e.targetId);
    }

    @Test
    void wiresClassToClassInternal() {
        List<ParsedBean> beans =
                parse(
                        "<beans>"
                                + "<bean id='svc' class='com.example.OrderService'><property name='repo' ref='repo'/></bean>"
                                + "<bean id='repo' class='com.example.RepoImpl'/>"
                                + "</beans>");
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor()
                .extract(beans, Set.of("com.example.OrderService", "com.example.RepoImpl"), XML, r);

        Edge w = edge(r, "WIRES", "com.example.OrderService");
        assertFalse(w.isExternal);
        assertEquals("com.example.RepoImpl", w.targetId);
        assertNotNull(w.beginLine);
        assertEquals(w.sourceLocation, "L" + w.beginLine);
    }

    @Test
    void wiresExternalWhenTargetClassUnknown() {
        List<ParsedBean> beans =
                parse(
                        "<beans>"
                                + "<bean id='svc' class='com.example.OrderService'><property name='ds' ref='ds'/></bean>"
                                + "<bean id='ds' class='org.third.DataSource'/>"
                                + "</beans>");
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(beans, Set.of("com.example.OrderService"), XML, r);

        Edge w = edge(r, "WIRES", "com.example.OrderService");
        assertTrue(w.isExternal);
        assertEquals("org.third.DataSource", w.externalTargetFqn);
    }

    @Test
    void noWiresWhenOwnerClassUnknown() {
        // Owner bean's class is third-party → no internal CLASS source node to anchor WIRES.
        List<ParsedBean> beans =
                parse(
                        "<beans>"
                                + "<bean id='svc' class='org.third.Svc'><property name='repo' ref='repo'/></bean>"
                                + "<bean id='repo' class='com.example.RepoImpl'/>"
                                + "</beans>");
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(beans, Set.of("com.example.RepoImpl"), XML, r);

        assertTrue(
                r.edges.stream().noneMatch(e -> "WIRES".equals(e.relation)),
                "owner class unknown → no WIRES");
    }

    @Test
    void noWiresWhenRefBeanUnresolvable() {
        // ref points to a bean name not declared anywhere → cannot map to a class.
        ParsedBean svc =
                parse(
                                "<beans><bean id='svc' class='com.example.OrderService'>"
                                        + "<property name='ghost' ref='ghost'/></bean></beans>")
                        .get(0);
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(svc), Set.of("com.example.OrderService"), XML, r);

        assertTrue(r.edges.stream().noneMatch(e -> "WIRES".equals(e.relation)));
        // DEFINED_BY still emitted for the bean itself.
        assertTrue(r.edges.stream().anyMatch(e -> "DEFINED_BY".equals(e.relation)));
    }

    @Test
    void resolvesRefToBeanFromAnotherXmlFile() {
        ParsedBean svc =
                parse(
                                "<beans><bean id='svc' class='com.example.OrderService'>"
                                        + "<property name='repo' ref='repo'/></bean></beans>")
                        .get(0);
        String repoXml = "repo/src/main/resources/repo.xml";
        String repoBeanId = XmlBeanExtractor.beanNodeId("repo", repoXml);

        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor()
                .extractWithResolvedBeans(
                        List.of(svc),
                        Set.of("com.example.OrderService", "com.example.RepoImpl", repoBeanId),
                        Map.of("bean:repo", new BeanRefTarget(repoBeanId, "com.example.RepoImpl")),
                        XML,
                        r);

        Edge ref =
                r.edges.stream()
                        .filter(e -> "XML_REFERS_TO".equals(e.relation))
                        .findFirst()
                        .orElseThrow();
        assertFalse(ref.isExternal);
        assertEquals(repoBeanId, ref.targetId);

        Edge w = edge(r, "WIRES", "com.example.OrderService");
        assertFalse(w.isExternal);
        assertEquals("com.example.RepoImpl", w.targetId);
    }

    @Test
    void emitsStructuredXmlConfigGraphWithoutLegacyRefsMetadata() {
        List<ParsedBean> beans =
                parse(
                        "<beans>"
                                + "<bean id='registry' class='com.example.FilterRegistry'>"
                                + "  <property name='filters'><map><entry key='DEFAULT'><list>"
                                + "    <ref bean='pre'/><value>x</value><null/>"
                                + "  </list></entry></map></property>"
                                + "</bean>"
                                + "<bean id='pre' class='com.example.PreFilter'/>"
                                + "</beans>");
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor()
                .extract(
                        beans,
                        Set.of("com.example.FilterRegistry", "com.example.PreFilter"),
                        XML,
                        r);

        Node bean =
                r.nodes.stream()
                        .filter(n -> "BEAN".equals(n.kind) && n.label.equals("registry"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(bean.metadata.contains("\"refs\""), bean.metadata);
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "XML_PROPERTY".equals(n.kind)
                                                && n.metadata.contains("\"name\":\"filters\"")),
                "property missing: " + r.nodes);
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "XML_ENTRY".equals(n.kind)
                                                && n.metadata.contains("\"key\":\"DEFAULT\"")),
                "entry missing: " + r.nodes);
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "XML_REF".equals(n.kind)
                                                && n.metadata.contains("\"bean\":\"pre\"")),
                "ref missing: " + r.nodes);
        assertTrue(
                r.nodes.stream()
                        .anyMatch(
                                n ->
                                        "XML_VALUE".equals(n.kind)
                                                && n.metadata.contains("\"value\":\"x\"")),
                "value missing: " + r.nodes);
        assertTrue(
                r.nodes.stream().anyMatch(n -> "XML_NULL".equals(n.kind)),
                "null missing: " + r.nodes);
        assertTrue(r.edges.stream().anyMatch(e -> "CONFIGURES".equals(e.relation)));
        assertTrue(r.edges.stream().anyMatch(e -> "XML_CONTAINS".equals(e.relation)));
        assertTrue(
                r.edges.stream()
                        .anyMatch(
                                e ->
                                        "XML_REFERS_TO".equals(e.relation)
                                                && e.targetId != null
                                                && e.targetId.startsWith("bean:pre@")));
    }
}
