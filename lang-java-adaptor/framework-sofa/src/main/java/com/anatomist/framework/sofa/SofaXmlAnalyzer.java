package com.anatomist.framework.sofa;

import com.anatomist.framework.*;
import com.anatomist.core.XmlResourceBudget;
import com.anatomist.core.HardResourceLimit;
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
            var budget=new XmlResourceBudget(context.analysisContext());
            budget.bytes(2048);
            var handler = new Handler(resource.sourceFile(),budget);
            try {
                long size=Files.size(resource.path());
                if(size>16L*1024*1024) {
                    var analysis=budget.analysisContext();
                    if(analysis!=null)analysis.deny("payload_bytes",Math.min(16L*1024*1024,analysis.limits().maxPayloadBytes()),0,size);
                    throw new HardResourceLimit("SOFA_XML_SIZE_LIMIT_EXCEEDED");
                }
                budget.bytes(8192+8L*size);
                String document = Files.readString(resource.path());
                if (!document.contains(NAMESPACE)) continue;
                var parser = com.anatomist.core.OfflineXml.parser();
                parser.getXMLReader()
                        .setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                parser.parse(new InputSource(new java.io.StringReader(document)), handler);
                result.addFacts(handler.result);
            } catch (Exception failure) {
                if(failure.getMessage()!=null && failure.getMessage().contains("JAXP00010006"))budget.platformDepthExceeded(handler.maximumDepth);
                HardResourceLimit.rethrow(failure);
                // Never publish a partially parsed SOFA document as complete evidence.
                throw new IllegalStateException(
                        "SOFA_XML_PARSE_FAILED: " + resource.sourceFile(), failure);
            }
        }
    }

    private static final class Handler extends DefaultHandler {
        final ExtractionResult result;
        final XmlResourceBudget budget;
        final String file;
        Locator locator;
        int depth, ordinal, maximumDepth;
        final Map<String, Integer> occurrences = new HashMap<>();
        Registration current;

        Handler(String file,XmlResourceBudget budget) {
            this.file = file;this.budget=budget;
            this.result=new ExtractionResult(budget.analysisContext());
        }

        @Override
        public void setDocumentLocator(Locator value) {
            locator = value;
        }

        @Override
        public void startElement(String uri, String local, String qualified, Attributes attrs) {
            depth++;
            budget.parsedNode(depth);maximumDepth=Math.max(maximumDepth,depth);
            if (depth > 256
                    || ordinal > 100_000
                    || current != null && current.children.size() > 100_000)
                throw new HardResourceLimit("SOFA_XML_ELEMENT_LIMIT_EXCEEDED");
            if (!NAMESPACE.equals(uri)) return;
            if (current == null
                    && Set.of("service", "reference", "publisher", "consumer").contains(local)) {
                budget.bytes(512);
                current =
                        new Registration(
                                local,
                                attributes(attrs),
                                locator.getLineNumber(),
                                depth,
                                ordinal++);
            } else if (current != null) {
                budget.bytes(512);
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
            budget.emittedNode(1);budget.bytes(2048);
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
            budget.bytes(256L+6L*(value.element.length()+name.length()+contract.length()+unique.length()));
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

        @Override public void characters(char[] chars,int start,int length) {budget.characters(chars,start,length);}
        private Map<String, String> attributes(Attributes attrs) {
            var result = new LinkedHashMap<String, String>();
            for (int i = 0; i < attrs.getLength(); i++) {
                budget.bytes(128);budget.text(attrs.getLocalName(i));budget.text(attrs.getValue(i));
                result.put(attrs.getLocalName(i), attrs.getValue(i));
            }
            return Collections.unmodifiableMap(result);
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
