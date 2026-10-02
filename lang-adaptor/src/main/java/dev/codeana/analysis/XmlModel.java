package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.Utf8Offsets;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded XML syntax over sealed UTF-8 input. No schema/entity/network or business loading. */
final class XmlModel {
    static final int MAX_NODES = 10000, MAX_DEPTH = 128, MAX_TEXT = 65536;
    static final long MAX_BYTES = 8L * 1024 * 1024;
    static final class Node {
        final String namespace, name;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<Node> children = new ArrayList<>();
        final int ordinal, start, line, column;
        int end, endLine, endColumn;
        final StringBuilder text = new StringBuilder();
        Node parent;
        Node(String namespace, String name, int ordinal, int start, int line, int column) {
            this.namespace = namespace; this.name = name; this.ordinal = ordinal;
            this.start = start; this.line = line; this.column = column;
        }
        String attr(String name) { return attributes.getOrDefault(name, ""); }
        Map<String, Object> span() { return Json.object("start", start, "end", end); }
        Map<String, Object> position() { return Json.object("line", line, "column", column,
                "end_line", endLine, "end_column", endColumn, "locator", "SAX tag-end locations"); }
    }
    record Token(String name, boolean start, int begin, int end) {}
    record Document(Node root, List<Node> nodes) {}

    static boolean mentionsNamespace(Map<String,Object> resource, Set<String> namespaces) throws Exception {
        Map<String,Object> unit=Json.map(resource.get("unit")); Path path=Path.of(Json.string(resource,"input"));
        if (!Json.sha(path).equals(unit.get("hash"))) throw new dev.codeana.analysis.extension.ExtensionPoint.GateFailure("XML_HASH_MISMATCH");
        if (Files.size(path)>MAX_BYTES) throw new SAXException("XML_BYTE_BUDGET");
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Json.read(path))).toString();
        return namespaces.stream().anyMatch(text::contains);
    }

    static Document read(Map<String, Object> resource) throws Exception {
        Map<String, Object> unit = Json.map(resource.get("unit"));
        Path path = Path.of(Json.string(resource, "input"));
        if (!Json.sha(path).equals(unit.get("hash")))
            throw new dev.codeana.analysis.extension.ExtensionPoint.GateFailure("XML_HASH_MISMATCH");
        if (Files.size(path) > MAX_BYTES) throw new SAXException("XML_BYTE_BUDGET");
        byte[] bytes = Json.read(path);
        if (!Json.sha(bytes).equals(unit.get("hash")))
            throw new dev.codeana.analysis.extension.ExtensionPoint.GateFailure("XML_HASH_MISMATCH");
        String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        Utf8Offsets offsets = new Utf8Offsets(text);
        List<Token> tokens = tokens(text, offsets);
        Handler handler = new Handler(tokens);
        SAXParserFactory factory = SAXParserFactory.newDefaultNSInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("XML_EXTERNAL_ENTITY"); });
        reader.setContentHandler(handler); reader.setErrorHandler(handler);
        reader.parse(new InputSource(new StringReader(text.startsWith("\ufeff") ? text.substring(1) : text)));
        if (handler.next != tokens.size() || !handler.stack.isEmpty()) throw new SAXException("XML_LOCATION_MISMATCH");
        return new Document(handler.root, List.copyOf(handler.nodes));
    }

    private static List<Token> tokens(String text, Utf8Offsets offsets) throws SAXException {
        List<Token> tokens = new ArrayList<>();
        int next = 0;
        while (next < text.length()) {
            int start = text.indexOf('<', next);
            if (start < 0) break;
            String terminator = text.startsWith("<!--", start) ? "-->"
                    : text.startsWith("<![CDATA[", start) ? "]]>": text.startsWith("<?", start) ? "?>" : null;
            if (terminator != null) {
                int end = text.indexOf(terminator, start + 2);
                if (end < 0) throw new SAXException("XML_UNTERMINATED_MARKUP");
                next = end + terminator.length(); continue;
            }
            if (text.startsWith("<!", start)) throw new SAXException("XML_DOCTYPE_OR_DECLARATION");
            boolean closing = text.startsWith("</", start);
            int nameStart = start + (closing ? 2 : 1), nameEnd = nameStart;
            while (nameEnd < text.length() && !Character.isWhitespace(text.charAt(nameEnd))
                    && text.charAt(nameEnd) != '/' && text.charAt(nameEnd) != '>') nameEnd++;
            String name = text.substring(nameStart, nameEnd);
            if (name.isEmpty() || name.length() > 256) throw new SAXException("XML_NAME_BUDGET");
            int end = nameEnd; char quote = 0;
            for (; end < text.length(); end++) {
                char c = text.charAt(end);
                if (quote != 0) { if (c == quote) quote = 0; }
                else if (c == '\'' || c == '"') quote = c;
                else if (c == '>') break;
            }
            if (end >= text.length()) throw new SAXException("XML_UNTERMINATED_TAG");
            int before = end - 1;
            while (before > start && Character.isWhitespace(text.charAt(before))) before--;
            tokens.add(new Token(name, !closing, Math.toIntExact(offsets.offset(start)), Math.toIntExact(offsets.offset(end+1))));
            if (!closing && text.charAt(before) == '/')
                tokens.add(new Token(name, false, Math.toIntExact(offsets.offset(start)), Math.toIntExact(offsets.offset(end+1))));
            if (tokens.size() > MAX_NODES*2) throw new SAXException("XML_NODE_BUDGET");
            next = end + 1;
        }
        return tokens;
    }

    private static final class Handler extends DefaultHandler {
        final List<Token> tokens;
        final List<Node> nodes = new ArrayList<>();
        final Deque<Node> stack = new ArrayDeque<>();
        int next; Locator locator; Node root;
        Handler(List<Token> tokens) { this.tokens = tokens; }
        public void setDocumentLocator(Locator locator) { this.locator = locator; }
        private Token token(String name, boolean start) throws SAXException {
            if (Thread.currentThread().isInterrupted()) throw new SAXException("CANCELLED");
            if (next >= tokens.size()) throw new SAXException("XML_LOCATION_MISMATCH");
            Token token = tokens.get(next++);
            if (!token.name().equals(name) || token.start() != start) throw new SAXException("XML_LOCATION_MISMATCH");
            return token;
        }
        public void startElement(String namespace, String local, String name, Attributes attributes) throws SAXException {
            Token token = token(name, true);
            if (stack.size() >= MAX_DEPTH || nodes.size() >= MAX_NODES || attributes.getLength() > 128)
                throw new SAXException("XML_NODE_OR_DEPTH_BUDGET");
            Node node = new Node(namespace, local.isEmpty() ? name : local, nodes.size()+1,
                    token.begin(), locator.getLineNumber(), locator.getColumnNumber());
            for (int i = 0; i < attributes.getLength(); i++) {
                if (attributes.getValue(i).length() > MAX_TEXT) throw new SAXException("XML_ATTRIBUTE_BUDGET");
                node.attributes.put(attributes.getQName(i), attributes.getValue(i));
            }
            if (stack.isEmpty()) root = node;
            else { node.parent = stack.peek(); node.parent.children.add(node); }
            nodes.add(node); stack.push(node);
        }
        public void endElement(String namespace, String local, String name) throws SAXException {
            Token token = token(name, false);
            Node node = stack.pop(); node.end = token.end();
            node.endLine = locator.getLineNumber(); node.endColumn = locator.getColumnNumber();
        }
        public void characters(char[] data, int start, int length) throws SAXException {
            if (stack.isEmpty()) return;
            Node node = stack.peek();
            if (length > MAX_TEXT-node.text.length()) throw new SAXException("XML_TEXT_BUDGET");
            node.text.append(data, start, length);
        }
        public void error(SAXParseException failure) throws SAXException { throw failure; }
        public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
    }
}
