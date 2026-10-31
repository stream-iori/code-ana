package com.anatomist.json;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;

/** Streaming JSON I/O with explicit, reflection-free semantic codecs. */
public final class Json {
    private static final int WRITE_DEPTH = 256;
    private static final JsonFactory OUTPUT = JsonFactory.builder()
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(WRITE_DEPTH).build())
            .disable(com.fasterxml.jackson.core.json.JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .build();
    private Json() {}

    public static String writeCompact(Object value) { return write(value, false, false); }
    public static String writePretty(Object value) { return write(value, true, false); }
    public static String writeCanonical(Object value) { return write(value, true, true); }

    public static String writeCompact(Object value, com.anatomist.core.AnalysisContext budget) { return write(value, false, false, budget); }
    public static String writeCanonical(Object value, com.anatomist.core.AnalysisContext budget) { return write(value, true, true, budget); }
    private static String write(Object value, boolean pretty, boolean canonical) { return write(value, pretty, canonical, null); }
    private static String write(Object value, boolean pretty, boolean canonical, com.anatomist.core.AnalysisContext budget) {
        StringWriter out = new StringWriter();
        try (JsonGenerator generator = OUTPUT.createGenerator(out)) {
            if (pretty) {
                DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
                printer.indentObjectsWith(new DefaultIndenter("  ", "\n"));
                generator.setPrettyPrinter(printer);
            }
            emit(generator, value, canonical, new IdentityHashMap<>(), 0, budget, false);
        } catch (IOException failure) {
            com.anatomist.core.HardResourceLimit.rethrow(failure);
            throw new IllegalArgumentException("JSON output failed", failure);
        }
        return out.toString();
    }

    private static void emit(JsonGenerator g, Object value, boolean canonical,
                             IdentityHashMap<Object, Boolean> active, int depth, com.anatomist.core.AnalysisContext budget, boolean transport) throws IOException {
        if (budget != null) {
            budget.depth(depth + 1);
            budget.charge(1, 1, value instanceof CharSequence text ? 64L + (transport?12L:6L) * text.length() : transport?128:64);
        }
        if (value == null) { g.writeNull(); return; }
        if (value instanceof Boolean b) { g.writeBoolean(b); return; }
        if (value instanceof CharSequence text) { g.writeString(text.toString()); return; }
        if (value instanceof Number n) {
            if(transport) {
                if(n instanceof Double doubleNumber && !Double.isFinite(doubleNumber) || n instanceof Float floatNumber && !Float.isFinite(floatNumber))throw new IllegalArgumentException("JSON_NON_FINITE");
                g.writeNumber(n.toString());
            } else if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (!Double.isFinite(d)) throw new IllegalArgumentException("non-finite JSON number");
                g.writeNumber(d);
            } else { g.writeNumber(n.toString()); }
            return;
        }
        if (depth >= WRITE_DEPTH) throw new com.anatomist.core.HardResourceLimit("JSON nesting exceeds " + WRITE_DEPTH);
        if (active.put(value, Boolean.TRUE) != null) throw new com.anatomist.core.HardResourceLimit("cyclic JSON object");
        try {
            if (value instanceof Map<?, ?> map) {
                if(transport)for(Object key:map.keySet())if(!(key instanceof String))throw new IllegalArgumentException("JSON_OBJECT_KEY");
                g.writeStartObject();
                if (canonical) {
                    if (budget != null) budget.charge(map.size(), map.size(), (transport?128L:64L) * map.size());
                    Map<String, Object> sorted = new TreeMap<>();
                    for (var entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
                    for (var entry : sorted.entrySet()) {
                        g.writeFieldName(entry.getKey());
                        emit(g, entry.getValue(), true, active, depth + 1, budget, transport);
                    }
                } else {
                    for (var entry : map.entrySet()) {
                        g.writeFieldName(String.valueOf(entry.getKey()));
                        emit(g, entry.getValue(), false, active, depth + 1, budget, transport);
                    }
                }
                g.writeEndObject();
            } else if (value instanceof Iterable<?> items) {
                g.writeStartArray();
                for (Object item : items) emit(g, item, canonical, active, depth + 1, budget, transport);
                g.writeEndArray();
            } else {
                if(transport)throw new IllegalArgumentException("JSON_TYPE: "+value.getClass());
                JsonCodec<Object> codec = JsonCodecRegistry.lookup(value.getClass());
                if (codec == null) throw new IllegalArgumentException("no codec for type " + value.getClass());
                // Legacy canonicalization sorts the supplied tree, not a codec's generated tree.
                emit(g, codec.toTree(value,budget), false, active, depth + 1, budget, transport);
            }
        } finally { active.remove(value); }
    }

    public static Object parseTree(String json) { return parseTree(json, 512); }
    public static Object parseTree(String json, int maxDepth) { return parseTree(json, maxDepth, null); }
    public static Object parseTree(String json, int maxDepth, com.anatomist.core.AnalysisContext budget) {
        if (budget != null) budget.charge(1, 1, 2L * json.length());
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be >= 1");
        JsonFactory input = JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(maxDepth).build()).build();
        try (JsonParser parser = input.createParser(json)) {
            if (parser.nextToken() == null) throw new IllegalArgumentException("empty JSON");
            Object value = read(parser, budget, 1);
            if (parser.nextToken() != null) throw new IllegalArgumentException("trailing JSON content");
            return value;
        } catch (IOException failure) {
            com.anatomist.core.HardResourceLimit.rethrow(failure); throw new IllegalArgumentException("invalid JSON", failure); }
    }

    private static final JsonFactory TRANSPORT_INPUT=JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(256).maxNumberLength(1024)
                    .maxStringLength(64*1024*1024).maxNameLength(64*1024*1024).maxTokenCount(3000000).build()).build();
    private static final class TransportRead {int nodes;}
    public static Object parseTransport(String text) {
        try(JsonParser parser=TRANSPORT_INPUT.createParser(text)) {
            if(parser.nextToken()==null)throw new IllegalArgumentException("empty JSON");
            Object value=read(parser,null,1,new TransportRead());
            if(parser.nextToken()!=null)throw new IllegalArgumentException("JSON_TRAILING_DATA");
            return value;
        }catch(IOException failure){com.anatomist.core.HardResourceLimit.rethrow(failure);throw new IllegalArgumentException("invalid JSON",failure);}
    }
    public static void writeTransport(java.io.Writer output,Object value) throws IOException {
        try(JsonGenerator generator=OUTPUT.createGenerator(output)) {
            emit(generator,value,true,new IdentityHashMap<>(),0,null,true);
        }
    }
    /** Admit both the measuring traversal and the final transport traversal. The
     * caller freezes its cumulative receipt only after this method returns. */
    public static void admitTransport(Object value,com.anatomist.core.AnalysisContext budget) {
        budget.charge(1,1,65536+4096);
        try(JsonGenerator generator=OUTPUT.createGenerator(java.io.Writer.nullWriter())) {
            emit(generator,value,true,new IdentityHashMap<>(),0,budget,true);
        }catch(IOException failure){com.anatomist.core.HardResourceLimit.rethrow(failure);throw new IllegalArgumentException("JSON output failed",failure);}
    }
    private static Object read(JsonParser p,com.anatomist.core.AnalysisContext budget,int depth) throws IOException {
        return read(p,budget,depth,null);
    }
    private static Object read(JsonParser p, com.anatomist.core.AnalysisContext budget, int depth,TransportRead transport) throws IOException {
        if(transport!=null && ++transport.nodes>1000000)throw new com.anatomist.core.HardResourceLimit("JSON_ALLOCATION_BUDGET");
        if (budget != null) {
            budget.depth(depth);
            budget.charge(1, 1, p.currentToken() == JsonToken.VALUE_STRING ? 32L + 3L * p.getTextLength() : 64);
        }
        return switch (p.currentToken()) {
            case START_OBJECT -> {
                Map<String, Object> map = new LinkedHashMap<>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    if (p.currentToken() != JsonToken.FIELD_NAME) throw new IllegalArgumentException("expected JSON field");
                    String name = p.currentName();
                    if (p.nextToken() == null) throw new IllegalArgumentException("truncated JSON object");
                    if (budget != null) budget.charge(1, 1, 64L + 3L * name.length());
                    map.put(name, read(p, budget, depth + 1,transport));
                }
                yield map;
            }
            case START_ARRAY -> {
                List<Object> list = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) {
                    if (p.currentToken() == null) throw new IllegalArgumentException("truncated JSON array");
                    list.add(read(p, budget, depth + 1,transport));
                }
                yield list;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> transport!=null && p.getNumberType()==JsonParser.NumberType.BIG_INTEGER ? p.getBigIntegerValue() : p.getLongValue();
            case VALUE_NUMBER_FLOAT -> {
                if(transport!=null)yield p.getDecimalValue();
                double value = p.getDoubleValue();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite JSON number");
                yield value;
            }
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("unexpected JSON token " + p.currentToken());
        };
    }
}
