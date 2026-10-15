package com.anatomist.framework.sofa;

import com.anatomist.framework.*;
import com.anatomist.model.ExtractionResult;

import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import java.nio.file.Files;
import java.util.*;

/** Reads the internal SOFA 4 service namespace, including MSGBROKER registrations. */
public final class SofaXmlAnalyzer implements ProjectResourceAnalyzer {
    public static final String NAMESPACE = "http://schema.alipay.com/sofa/schema/service";

    @Override
    public String id() {
        return "sofa-xml";
    }

    @Override
    public boolean enabled(JavaFrameworkContext context) {
        return context.springXml();
    }

    @Override
    public ResourceSelector selector() {
        return ResourceSelector.kind("spring-xml");
    }

    @Override
    public void analyze(
            JavaFrameworkContext context,
            List<ProjectResource> resources,
            ProjectFactView facts,
            ExtractionResult result) {
        for (var resource : resources) {
            var handler = new Handler(resource.sourceFile());
            try {
                if (Files.size(resource.path()) > 16 * 1024 * 1024)
                    throw new IllegalStateException("SOFA_XML_SIZE_LIMIT_EXCEEDED");
                String document = Files.readString(resource.path());
                if (!document.contains(NAMESPACE)) continue;
                var parser = com.anatomist.core.OfflineXml.parser();
                parser.getXMLReader()
                        .setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                parser.parse(new InputSource(new java.io.StringReader(document)), handler);
                result.addFacts(handler.result);
            } catch (Exception failure) {
                // Never publish a partially parsed SOFA document as complete evidence.
                throw new IllegalStateException(
                        "SOFA_XML_PARSE_FAILED: " + resource.sourceFile(), failure);
            }
        }
    }

    private static final class Handler extends DefaultHandler {
        final ExtractionResult result = new ExtractionResult();
        final String file;
        Locator locator;
        int depth, ordinal;
        final Map<String, Integer> occurrences = new HashMap<>();
        Registration current;

        Handler(String file) {
            this.file = file;
        }

        @Override
        public void setDocumentLocator(Locator value) {
            locator = value;
        }

        @Override
        public void startElement(String uri, String local, String qualified, Attributes attrs) {
            depth++;
            if (depth > 256
                    || ordinal > 100_000
                    || current != null && current.children.size() > 100_000)
                throw new IllegalStateException("SOFA_XML_ELEMENT_LIMIT_EXCEEDED");
            if (!NAMESPACE.equals(uri)) return;
            if (current == null
                    && Set.of("service", "reference", "publisher", "consumer").contains(local)) {
                current =
                        new Registration(
                                local,
                                attributes(attrs),
                                locator.getLineNumber(),
                                depth,
                                ordinal++);
            } else if (current != null) {
                current.children.add(
                        new ChildElement(
                                local,
                                attributes(attrs),
                                locator.getLineNumber(),
                                local.equals("event") ? current.channel : null));
                if (local.equals("channel")) current.channel = attrs.getValue("value");
                if (local.equals("listener")) current.listener = attrs.getValue("ref");
                if (local.startsWith("binding."))
                    current.bindings.add(local.substring("binding.".length()));
                if (local.equals("binding")) current.bindings.add("unknown");
            }
        }

        @Override
        public void endElement(String uri, String local, String qualified) {
            if (current != null && depth == current.depth && NAMESPACE.equals(uri)) {
                emit(current, locator.getLineNumber());
                current = null;
            }
            if (current != null && NAMESPACE.equals(uri) && local.equals("channel"))
                current.channel = null;
            depth--;
        }

        private void emit(Registration value, int end) {
            boolean message = Set.of("publisher", "consumer").contains(value.element);
            var data = new LinkedHashMap<String, Object>();
            data.put("framework", message ? "msgbroker" : "sofa");
            String role =
                    switch (value.element) {
                        case "service" -> "provider";
                        case "reference" -> "requirement";
                        default -> value.element;
                    };
            data.put("role", role);
            data.put("kind", role.equals("consumer") ? "handler" : "dependency");
            data.put("mechanism", (message ? "msgbroker" : "sofa") + ".xml." + value.element);
            data.put("attributes", value.attributes);
            data.put("configuration", value.children.stream().map(ChildElement::toMap).toList());
            data.put("namespace", NAMESPACE);
            data.put("contractBaseline", "internal-runtime-4.19.1-xsd");
            String contract = value.attributes.getOrDefault("interface", "");
            String unique = value.attributes.getOrDefault("unique-id", "");
            data.put("contract", contract);
            data.put("uniqueId", unique);
            data.put("group", value.attributes.getOrDefault("group", ""));
            var bindings =
                    value.bindings.isEmpty()
                            ? List.of(message ? "msg_broker" : "jvm")
                            : List.copyOf(value.bindings);
            data.put("bindings", bindings);
            data.put("transport", bindings.size() == 1 ? bindings.getFirst() : "multiple");
            boolean supported =
                    message
                            || (!contract.isBlank()
                                    && !contract.contains("${")
                                    && !unique.contains("${"));
            supported &=
                    !value.attributes.containsKey("parent")
                            && !"true".equals(value.attributes.get("abstract"));
            supported &=
                    bindings.stream()
                            .allMatch(
                                    Set.of("jvm", "tr", "ws", "tri", "rest", "msg_broker")
                                            ::contains);
            data.put("contractSupported", supported);
            String ref = message ? value.listener : value.attributes.get("ref");
            if (ref != null) data.put("targetBean", ref);
            String name =
                    value.attributes.getOrDefault(
                            "id",
                            value.element + ":" + value.attributes.getOrDefault("ref", contract));
            data.put("registrationName", name);
            String identity = value.element + ":" + name + ":" + contract + ":" + unique;
            int occurrence = occurrences.merge(identity, 1, Integer::sum) - 1;
            FrameworkRegistration.add(
                    result,
                    com.anatomist.framework.spring.XmlBeanGraphExtractor.artifactNodeId(file),
                    file,
                    value.line,
                    end,
                    identity + ":" + occurrence,
                    name,
                    data);
        }

        private static Map<String, String> attributes(Attributes attrs) {
            var result = new LinkedHashMap<String, String>();
            for (int i = 0; i < attrs.getLength(); i++)
                result.put(attrs.getLocalName(i), attrs.getValue(i));
            return Map.copyOf(result);
        }
    }

    private record ChildElement(
            String element, Map<String, String> attributes, int line, String channel) {
        Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("element", element);
            result.put("attributes", attributes);
            result.put("line", line);
            if (channel != null) result.put("channel", channel);
            return result;
        }
    }

    private static final class Registration {
        final String element;
        final Map<String, String> attributes;
        final int line, depth, ordinal;
        final List<ChildElement> children = new ArrayList<>();
        final List<String> bindings = new ArrayList<>();
        String listener, channel;

        Registration(
                String element, Map<String, String> attributes, int line, int depth, int ordinal) {
            this.element = element;
            this.attributes = attributes;
            this.line = line;
            this.depth = depth;
            this.ordinal = ordinal;
        }
    }
}
