package com.anatomist.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

/**
 * Pins the pure SAX parsing of Spring bean XML: bean name/class extraction and the ref edges
 * harvested from {@code property/@ref}, {@code constructor-arg/@ref} and the {@code ref} attribute
 * form. Root-element sniffing rejects non-Spring XML; malformed input yields an empty list rather
 * than throwing.
 */
class SpringBeanParserTest {

    private static List<SpringBeanParser.ParsedBean> parse(String xml) {
        return new SpringBeanParser().parse(new StringReader(xml));
    }

    @Test
    void externalDtdDoesNotPreventLocalExtraction(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("beans.xml");
        java.nio.file.Files.writeString(
                file,
                "<!DOCTYPE beans SYSTEM 'file:///missing-anatomist-test.dtd'><beans><bean id='x' class='p.X'/></beans>");
        assertTrue(SpringBeanParser.isSpringBeansFile(file));
        assertEquals("p.X", new SpringBeanParser().parse(file).getFirst().className());
    }

    @Test
    void rootProbeStopsBeforeReadingDocumentBody() {
        // Permit the parser's initial buffer, but fail deterministically if it scans the body.
        var reader =
                new java.io.Reader() {
                    private final String prefix = "<beans>" + " ".repeat(16384);
                    private int offset;

                    @Override
                    public int read(char[] target, int start, int length) {
                        if (offset == prefix.length())
                            fail("root discovery read the document body");
                        int count = Math.min(length, prefix.length() - offset);
                        prefix.getChars(offset, offset + count, target, start);
                        offset += count;
                        return count;
                    }

                    @Override
                    public void close() {}
                };
        assertTrue(SpringBeanParser.rootIsBeans(reader));
        assertFalse(SpringBeanParser.rootIsBeans(new StringReader("<project><broken")));
        assertTrue(SpringBeanParser.rootIsBeans(new StringReader("<beans><broken")));
    }

    @Test
    void externalDtdDefaultsAndEntitiesAreNotImported(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var dtd = dir.resolve("beans.dtd");
        java.nio.file.Files.writeString(dtd, "<!ATTLIST bean class CDATA 'external.Default'>");
        var beans =
                parse(
                        "<!DOCTYPE beans SYSTEM '"
                                + dtd.toUri()
                                + "'><beans><bean id='local'/></beans>");
        assertEquals(1, beans.size());
        assertNull(beans.getFirst().className());
        var secret = dir.resolve("entity.txt");
        java.nio.file.Files.writeString(secret, "external-secret");
        beans =
                parse(
                        "<!DOCTYPE beans [<!ENTITY external SYSTEM '"
                                + secret.toUri()
                                + "'>]><beans><bean id='x'><property name='v'><value>&external;</value>"
                                + "</property></bean></beans>");
        assertEquals(1, beans.size());
        assertNull(beans.getFirst().children().getFirst().children.getFirst().value);
    }

    @Test
    void parsesBeanNameAndClass() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean id='orderSvc' class='com.example.OrderService'/>"
                                + "</beans>");
        assertEquals(1, beans.size());
        SpringBeanParser.ParsedBean b = beans.get(0);
        assertEquals("orderSvc", b.name());
        assertEquals("com.example.OrderService", b.className());
        assertTrue(b.children().isEmpty());
    }

    @Test
    void beanNameFallsBackToNameAttrThenClass() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean name='byName' class='com.example.A'/>"
                                + "<bean class='com.example.B'/>"
                                + "</beans>");
        assertEquals("byName", beans.get(0).name());
        // No id/name → fall back to class FQN as the bean name.
        assertEquals("com.example.B", beans.get(1).name());
    }

    @Test
    void parsesPropertyAndConstructorArgRefs() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean id='svc' class='com.example.OrderService'>"
                                + "  <property name='repo' ref='orderRepo'/>"
                                + "  <constructor-arg ref='clock'/>"
                                + "</bean>"
                                + "<bean id='orderRepo' class='com.example.RepoImpl'/>"
                                + "<bean id='clock' class='com.example.Clock'/>"
                                + "</beans>");
        SpringBeanParser.ParsedBean svc =
                beans.stream().filter(b -> b.name().equals("svc")).findFirst().orElseThrow();
        assertEquals(2, svc.children().size());
        assertEquals("property", svc.children().get(0).kind);
        assertEquals("repo", svc.children().get(0).name);
        assertEquals("ref", svc.children().get(0).children.get(0).kind);
        assertEquals("orderRepo", svc.children().get(0).children.get(0).bean);
        assertEquals("constructor-arg", svc.children().get(1).kind);
        assertEquals(0, svc.children().get(1).index);
        assertEquals("clock", svc.children().get(1).children.get(0).bean);
    }

    @Test
    void parsesNestedMapListRefsWithKeyAndOrder() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean id='a' class='com.example.A'>"
                                + "  <property name='filters'>"
                                + "    <map><entry key='DEFAULT'><list>"
                                + "      <ref bean='first'/><ref bean='second'/>"
                                + "    </list></entry></map>"
                                + "  </property>"
                                + "</bean>"
                                + "</beans>");
        SpringBeanParser.XmlConfigNode property = beans.get(0).children().get(0);
        SpringBeanParser.XmlConfigNode map = property.children.get(0);
        SpringBeanParser.XmlConfigNode entry = map.children.get(0);
        SpringBeanParser.XmlConfigNode list = entry.children.get(0);
        assertEquals("filters", property.name);
        assertEquals("map", map.kind);
        assertEquals("DEFAULT", entry.key);
        assertEquals("list", list.kind);
        assertEquals("first", list.children.get(0).bean);
        assertEquals(0, list.children.get(0).index);
        assertEquals("second", list.children.get(1).bean);
        assertEquals(1, list.children.get(1).index);
    }

    @Test
    void parsesValueNullAndIdref() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean id='a' class='com.example.A'>"
                                + "  <property name='name'><value>alpha</value></property>"
                                + "  <property name='empty'><null/></property>"
                                + "  <constructor-arg><idref bean='other'/></constructor-arg>"
                                + "</bean>"
                                + "</beans>");
        assertEquals("alpha", beans.get(0).children().get(0).children.get(0).value);
        assertEquals("null", beans.get(0).children().get(1).children.get(0).kind);
        assertEquals("idref", beans.get(0).children().get(2).children.get(0).kind);
        assertEquals("other", beans.get(0).children().get(2).children.get(0).bean);
    }

    @Test
    void nonBeansRootYieldsEmpty() {
        // A pom.xml / arbitrary XML must be ignored by the sniff.
        assertTrue(parse("<project><modelVersion>4.0.0</modelVersion></project>").isEmpty());
        assertTrue(parse("<configuration><logger name='x'/></configuration>").isEmpty());
    }

    @Test
    void malformedXmlYieldsEmptyAndDoesNotThrow() {
        assertTrue(parse("<beans><bean id='x' class='C'></beans").isEmpty());
        assertTrue(parse("not xml at all").isEmpty());
    }

    @Test
    void abstractAndParentBeanFactsAreRetained() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        "<beans xmlns='http://www.springframework.org/schema/beans'>"
                                + "<bean id='tmpl' abstract='true'/>"
                                + "<bean id='real' parent='tmpl' class='com.example.Real' factory-method='create'/>"
                                + "</beans>");
        assertEquals(2, beans.size());
        assertTrue(beans.get(0).abstractBean());
        assertEquals("tmpl", beans.get(1).parent());
        assertEquals("create", beans.get(1).factoryMethod());
    }

    @Test
    void nestedBeanAndExactRangesAreRetained() {
        List<SpringBeanParser.ParsedBean> beans =
                parse(
                        """
                <beans xmlns='http://www.springframework.org/schema/beans'>
                  <bean id='outer' class='p.Outer'>
                    <property name='inner'><bean class='p.Inner'><property name='x' value='1'/></bean></property>
                  </bean>
                </beans>
                """);
        assertEquals(2, beans.size());
        SpringBeanParser.ParsedBean nested =
                beans.stream().filter(bean -> bean.nestedIn() != null).findFirst().orElseThrow();
        assertEquals("outer", nested.nestedIn());
        assertTrue(nested.endLine() >= nested.line());
        assertTrue(
                beans.stream()
                        .flatMap(bean -> bean.children().stream())
                        .anyMatch(node -> node.ordinal == 0));
    }
}
