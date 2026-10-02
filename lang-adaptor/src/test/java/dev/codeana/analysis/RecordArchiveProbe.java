package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.RecordArchive;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.Utf8Offsets;
import java.nio.file.*;
import java.util.*;

/** Standalone archive fault checks, run under the SDKMAN build JDK. */
public final class RecordArchiveProbe {
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("invalid archive was accepted");
    }
    public static void main(String[] args) throws Exception {
        numericCodec();
        unicodePositions();
        binaryTypes();
        Path root = Path.of(args[0]);
        Object row = Json.object("id", "中文😀", "value", List.of(1, true, "line\nvalue"));
        Path archive = root.resolve("records.bin");
        try (RecordArchive records = RecordArchive.create(archive, 10240, 2)) {
            records.add(row); records.add(row);
            rejected(() -> records.add(row));
            records.seal(); rejected(() -> records.add(row));
            if (records.size() != 2 || !records.get(0).equals(records.get(1))) throw new AssertionError("multiplicity");
        }
        String hash = Json.sha(archive);
        try (RecordArchive records = RecordArchive.open(archive, 10240, 2, hash)) {
            Path json = root.resolve("result.json");
            long bytes = Json.writeTo(json, records, 10240);
            if (bytes != Files.size(json) || !Json.parse(Json.read(json)).equals(records)) throw new AssertionError("UTF8 streaming");
            rejected(() -> Json.writeTo(root.resolve("limited.json"), records, 1));
        }
        rejected(() -> RecordArchive.open(archive, 10240, 1, hash));
        rejected(() -> RecordArchive.open(archive, 5, 2, hash));
        byte[] original = Files.readAllBytes(archive);
        original[0] = 0; Files.write(archive, original);
        rejected(() -> RecordArchive.open(archive, 10240, 2, hash));
        rejected(() -> RecordArchive.open(archive, 10240, 2, Json.sha(archive)));
        original[0] = 0x43;
        Files.write(archive, Arrays.copyOf(original, original.length - 1));
        rejected(() -> RecordArchive.open(archive, 10240, 2, Json.sha(archive)));
        Object type=Json.object("version",2,"kind","ARRAY","component",Json.object("version",2,"kind","DECLARED",
                "display","example.AVeryLongTypeNameToVerifyInterning","element","example.AVeryLongTypeNameToVerifyInterning"));
        Map<String,Object> model=Json.object("version",6,"context","fixture");
        for(String section:List.of("sources","declarations","observations","syntax","external","annotation_types","diagnostics"))
            model.put(section,new ArrayList<Object>());
        for(int i=0;i<100;i++) Json.list(model.get("declarations")).add(Json.object("id",i,"type",type,
                "literal",Json.object("$type",7,"$object","literal")));
        for(int i=0;i<100;i++) Json.list(model.get("syntax")).add(Json.object("id",i,"source","A.java",
                "roles",Json.object("children",List.of(i+1)),"type",type));
        Path packed=root.resolve("model.bin");
        long size=ModelArchive.write(packed,model,1024*1024);
        Map<String,Object> decoded=ModelArchive.read(packed,Json.sha(packed),1024*1024,"fixture");
        if(!Json.write(model).equals(Json.write(decoded))) throw new AssertionError("model roundtrip");
        List<Object> declarations=Json.list(decoded.get("declarations"));
        Object shared=Json.map(declarations.get(0)).get("type");
        if(!(shared instanceof ModelArchive.ImmutableType)) throw new AssertionError("type tree lost immutable sharing marker");
        if(dev.codeana.analysis.extension.ExtensionContext.freeze(shared)!=shared) throw new AssertionError("callback copied shared types");
        rejected(() -> Json.map(Json.map(shared).get("component")).put("element","forged"));
        Map<String,Object> editable=dev.codeana.analysis.extension.ExtensionContext.mutableMap(Json.map(shared));
        Json.map(editable.get("component")).put("element","edited");
        if(Json.map(Json.map(shared).get("component")).get("element").equals("edited")) throw new AssertionError("mutable copy changed shared type");
        if(Json.map(declarations.get(0)).get("type")!=Json.map(declarations.get(1)).get("type"))
            throw new AssertionError("type trees are not shared");
        if(size>=Json.write(model).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            throw new AssertionError("type table did not reduce encoding");
        ModelArchive.OpenModel view=ModelArchive.open(packed,Json.sha(packed),1024*1024,"fixture");
        List<Object> syntax=Json.list(view.model().get("syntax"));
        if (!(syntax instanceof ModelArchive.SyntaxRows) || !Json.write(model).equals(Json.write(view.model())))
            throw new AssertionError("lazy model is not equivalent");
        if(dev.codeana.analysis.extension.ExtensionContext.freezeMap(view.model()).get("syntax")!=syntax)
            throw new AssertionError("immutable callback copied archive syntax");
        rejected(() -> Json.map(syntax.get(0)).put("id",999));
        rejected(() -> Json.list(Json.map(Json.map(syntax.get(0)).get("roles")).get("children")).clear());
        Json.list(view.model().get("declarations")).add(Json.object("id",101));
        if(syntax.size()!=100) throw new AssertionError("declaration edits changed syntax");
        view.close(); rejected(() -> syntax.get(0));
        // A streaming compiler must preserve row order, last-row augmentation and type/literal data.
        Path streamed=root.resolve("streamed-model.bin");
        for(int i=0;i<200;i++) Json.list(model.get("declarations")).add(Json.object("id",100+i,
                "type",Json.object("version",2,"kind","DECLARED","element","fixture.Type"+i,
                        "arguments",List.of(type))));
        Json.list(model.get("declarations")).add(Json.object("id",300,"type",type));
        Map<String,Object> streaming=new LinkedHashMap<>(model);
        ModelArchive.RowSpool spool=new ModelArchive.RowSpool(root,"fixture");
        try(spool) {
            for(Object value:Json.list(model.get("declarations"))) spool.add(value);
            Map<String,Object> last=Json.map(spool.get(spool.size()-1));
            last.put("lambda",Json.object("sam","fixture.Fn"));
            streaming.put("declarations",spool);
            ModelArchive.write(streamed,streaming,1024*1024);
            Path expected=root.resolve("expected-model.bin");
            ModelArchive.write(expected,model,1024*1024);
            if(!Arrays.equals(Files.readAllBytes(streamed),Files.readAllBytes(expected)))
                throw new AssertionError("compiler spool changed the model archive");
        }
        rejected(() -> spool.get(0)); rejected(() -> spool.add(row));
        try(var files=Files.list(root)) {
            if(files.anyMatch(path -> path.getFileName().toString().startsWith("model-rows-")))
                throw new AssertionError("compiler spool leaked");
        }
        rejected(() -> ModelArchive.read(packed,Json.sha(packed),1024*1024,"wrong-context"));
        try(RecordArchive records=RecordArchive.create(root.resolve("bad-model.bin"),10240,3)) {
            records.add(Json.object("kind","header","version",6,"context","fixture","sections",List.of(
                    "sources","declarations","observations","syntax","external","annotation_types","diagnostics")));
            records.add(Json.object("kind","type","id",2,"value",Json.object("kind","VOID","version",2)));
            records.add(Json.object("kind","end","counts",Map.of(),"types",1)); records.seal();
        }
        Path bad=root.resolve("bad-model.bin");
        rejected(() -> ModelArchive.read(bad,Json.sha(bad),10240,"fixture"));
        System.out.println("archive Unicode/multiplicity/budgets and model6 shared types/escaped values/section identity: passed");
    }

    private static void numericCodec() throws Exception {
        for (String token : List.of("0", "-0", "9223372036854775807", "9223372036854775808",
                "-9223372036854775809", "0.125", "-2.5E-20", "1e+200", "1E-200")) {
            Object value = Json.parse(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.math.BigDecimal expected = new java.math.BigDecimal(token);
            Object decoded = Json.parse(Json.write(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (expected.compareTo(new java.math.BigDecimal(decoded.toString())) != 0)
                throw new AssertionError("numeric precision: " + token);
        }
        for (String token : List.of("01", "-01", "1.", "1e", "1e+", "+1", ".1", "NaN", "Infinity", "1".repeat(1025)))
            rejected(() -> Json.parse(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (Object value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
            rejected(() -> Json.write(value));
        rejected(() -> Json.number(Json.object("limit", new java.math.BigDecimal("1.5")), "limit"));
        rejected(() -> Json.number(Json.object("limit", new java.math.BigInteger("9223372036854775808")), "limit"));
        if (Json.number(Json.object("limit", new java.math.BigDecimal("5e6")), "limit") != 5000000)
            throw new AssertionError("exact integer configuration");
        byte[] abc = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String known = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        if (!known.equals(Json.sha(abc))) throw new AssertionError("SHA-256 hex encoding");
        Path file = Files.createTempFile("codeana-numeric-sha-", ".txt");
        try { Files.write(file, abc); if (!known.equals(Json.sha(file))) throw new AssertionError("streamed SHA-256"); }
        finally { Files.deleteIfExists(file); }
        System.out.println("exact decimal/exponent/wide integer, numeric grammar/budgets/non-finite and SHA vectors: passed");
    }

    private static void unicodePositions() {
        String[] atoms = {"a", "\n", "\r", "中文", "😀", "é", "\uFEFF", "𐐀", "\t", "€"};
        Random random = new Random(718);
        for (int trial = 0; trial < 300; trial++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 100; i++) text.append(atoms[random.nextInt(atoms.length)]);
            String value = text.toString();
            Utf8Offsets offsets = new Utf8Offsets(value);
            for (int pos = 0; pos <= value.length(); pos++) {
                long expected = pos > 0 && pos < value.length()
                        && Character.isLowSurrogate(value.charAt(pos)) ? -1
                        : value.substring(0, pos).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (offsets.offset(pos) != expected) throw new AssertionError("Unicode offset: " + trial + "@" + pos);
            }
        }
        Utf8Offsets ascii = new Utf8Offsets("a".repeat(1000000));
        if (ascii.indexBytes() != 0 || ascii.offset(1000000) != 1000000
                || ascii.offset(-1) != -1 || ascii.offset(1000001) != -1)
            throw new AssertionError("ASCII allocation / bounds");
        System.out.println("300 randomized Unicode mappings, surrogate boundaries and zero ASCII index: passed");
    }

    private static void binaryTypes() throws Exception {
        Map<String, Object> inner = BinaryTypes.field("Llib/Outer<TT;>.Inner<+Ljava/lang/Number;>;", "Llib/Outer$Inner;");
        if (!"lib.Outer$Inner".equals(inner.get("element"))
                || !"lib.Outer".equals(Json.map(inner.get("enclosing")).get("element")))
            throw new AssertionError("nested binary identity");
        Map<String, Object> parameter = Json.map(Json.list(inner.get("arguments")).getFirst());
        if (!"java.lang.Number".equals(Json.map(parameter.get("extends")).get("element")))
            throw new AssertionError("binary wildcard");
        Map<String, Object> method = BinaryTypes.method("<T:Ljava/lang/Number;:Ljava/lang/Comparable<TT;>;>([TT;)TT;^Ljava/io/IOException;", null);
        if (Json.list(Json.map(Json.list(method.get("type_parameters")).getFirst()).get("bounds")).size() != 2
                || !"TYPEVAR".equals(Json.map(method.get("return")).get("kind"))
                || !"ARRAY".equals(Json.map(Json.list(method.get("parameters")).getFirst()).get("kind")))
            throw new AssertionError("binary formal / array / return model");
        rejected(() -> BinaryTypes.field("Lbroken", null));
        rejected(() -> BinaryTypes.field("Iextra", null));
        rejected(() -> BinaryTypes.field("[".repeat(40) + "I", null));
        System.out.println("binary generic bounds/nested identity/wildcards/arrays and malformed signature budgets: passed");
    }
}
