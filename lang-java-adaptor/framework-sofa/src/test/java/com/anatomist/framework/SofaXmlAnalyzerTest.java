package com.anatomist.framework;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.framework.sofa.SofaXmlAnalyzer;
import com.anatomist.model.ExtractionResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.Map;

class SofaXmlAnalyzerTest {
    @TempDir Path tmp;

    @Test
    void namespaceNotPrefixControlsRecognitionAndCommentsAreIgnored() throws Exception {
        var result =
                analyze(
                        """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:x="http://schema.alipay.com/sofa/schema/service"
                       xmlns:sofa="urn:not-sofa">
                    <x:reference id="real" interface="p.Api"/>
                    <x:reference id="real" interface="p.Api"/>
                    <sofa:reference id="fake" interface="p.Api"/>
                    <!-- <x:reference id="comment" interface="p.Api"/> -->
                </beans>
                """);
        assertEquals(2, result.nodes.size());
        assertEquals("real", result.nodes.getFirst().label);
        assertNotEquals(result.nodes.getFirst().id, result.nodes.getLast().id);
    }

    @Test
    void doctypeAndMalformedXmlNeverPublishPartialSofaFacts() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () ->
                        analyze(
                                """
                <!DOCTYPE beans [<!ENTITY unsafe SYSTEM "file:///does-not-exist">]>
                <beans xmlns="http://www.springframework.org/schema/beans" xmlns:s="http://schema.alipay.com/sofa/schema/service">
                    <s:reference id="unsafe" interface="&unsafe;"/>
                </beans>
                """));
        assertThrows(
                IllegalStateException.class,
                () ->
                        analyze(
                                """
                <beans xmlns="http://www.springframework.org/schema/beans" xmlns:s="http://schema.alipay.com/sofa/schema/service">
                    <s:reference id="partial" interface="p.Api"/>
                """));
    }

    @Test
    void eventRetainsOnlyItsActiveChannelInConfiguration() throws Exception {
        var result =
                analyze(
                        """
                <beans xmlns="http://www.springframework.org/schema/beans"
                       xmlns:s="http://schema.alipay.com/sofa/schema/service">
                    <s:consumer id="events" ref="handler">
                        <s:channel value="orders">
                            <s:event name="created"/>
                        </s:channel>
                    </s:consumer>
                </beans>
                """);
        assertEquals(1, result.nodes.size());
        var metadata =
                (Map<?, ?>) com.anatomist.json.Json.parseTree(result.nodes.getFirst().metadata);
        var children = (List<?>) metadata.get("configuration");
        assertEquals(2, children.size());
        assertFalse(((Map<?, ?>) children.getFirst()).containsKey("channel"));
        assertEquals("orders", ((Map<?, ?>) children.getLast()).get("channel"));
    }

    private ExtractionResult analyze(String xml) throws Exception {
        Path file = tmp.resolve("beans.xml");
        Files.writeString(file, xml);
        var result = new ExtractionResult();
        try {
            new SofaXmlAnalyzer()
                    .analyze(
                            new JavaFrameworkContext(
                                    tmp, List.of(), new com.anatomist.config.ProjectConfig(), true),
                            List.of(new ProjectResource(file, "beans.xml", "spring-xml")),
                            null,
                            result);
        } catch (RuntimeException failure) {
            assertTrue(result.nodes.isEmpty());
            throw failure;
        }
        return result;
    }
}
