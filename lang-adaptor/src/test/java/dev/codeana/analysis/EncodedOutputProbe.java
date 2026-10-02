package dev.codeana.analysis;

import dev.codeana.common.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Compiled with SDK8; already encoded output retains strict record validation. */
public final class EncodedOutputProbe {
    private interface Checked { void run() throws Exception; }
    private static void reject(Checked run) throws Exception {
        try {run.run();throw new AssertionError("INVALID_ENCODED_RECORD_ACCEPTED");}
        catch(IOException | UncheckedIOException | IllegalArgumentException expected) {}
    }
    private static Path frame(Path root,String name,byte[] bytes) throws Exception {
        Path path=root.resolve(name);
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(RecordArchive.MAGIC);out.writeInt(bytes.length);out.write(bytes);
        }
        return path;
    }
    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]);
        Object row=Json.object("text","汉😀\n\"\\", "integer",new BigInteger("18446744073709551616"),
                "decimal",new BigDecimal("1.25E+30"),"values",Arrays.asList(true,false,null));
        try(RecordArchive records=RecordArchive.create(root.resolve("encoded-good.bin"),4096,2)) {
            records.add(row);records.add(row);records.seal();
            Path out=root.resolve("encoded-good.json");
            Json.writeTo(out,Json.object("facts",records),4096);
            byte[] expected=Json.write(Json.object("facts",Arrays.asList(row,row))).getBytes(StandardCharsets.UTF_8);
            if(!Arrays.equals(expected,Files.readAllBytes(out)))throw new AssertionError("ENCODED_VALUES_CHANGED");
            reject(() -> Json.writeTo(root.resolve("encoded-over-budget.json"),records,1));
            Thread.currentThread().interrupt();
            try {reject(() -> Json.writeTo(root.resolve("encoded-cancel.json"),records,4096));}
            finally {Thread.interrupted();}
        }
        List<byte[]> invalid=Arrays.asList(
                "{\"hidden\":{\"a\":1,\"a\":2}}".getBytes(StandardCharsets.UTF_8),
                "{\"hidden\":01}".getBytes(StandardCharsets.UTF_8),
                "{\"hidden\":1e}".getBytes(StandardCharsets.UTF_8),
                new byte[]{'"',(byte)0xc0,(byte)0xaf,'"'},
                "true trailing".getBytes(StandardCharsets.UTF_8));
        int index=0;
        for(byte[] bytes:invalid) {
            Path path=frame(root,"encoded-invalid-"+index+".bin",bytes);
            final Path output=root.resolve("encoded-invalid-"+(index++)+".json");
            try(RecordArchive records=RecordArchive.open(path,4096,1,Json.sha(path))) {
                reject(() -> Json.writeTo(output,records,4096));
            }
        }
        byte[] escaped="{\"value\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8);
        Path surrogate=frame(root,"encoded-surrogate.bin",escaped);
        try(RecordArchive records=RecordArchive.open(surrogate,4096,1,Json.sha(surrogate))) {
            Path output=root.resolve("encoded-surrogate.json");
            Json.writeTo(output,records,4096);
            byte[] expected=Json.write(Arrays.asList(Json.parse(escaped))).getBytes(StandardCharsets.UTF_8);
            if(!Arrays.equals(expected,Files.readAllBytes(output)))throw new AssertionError("SURROGATE_NORMALIZATION_CHANGED");
        }
        StringBuilder deep=new StringBuilder();
        for(int i=0;i<Json.MAX_DEPTH;i++)deep.append('[');
        deep.append('0');for(int i=0;i<Json.MAX_DEPTH;i++)deep.append(']');
        Path path=frame(root,"encoded-depth.bin",deep.toString().getBytes(StandardCharsets.UTF_8));
        try(RecordArchive records=RecordArchive.open(path,4096,1,Json.sha(path))) {
            // The row is legal by itself; embedding it in the result must still enforce depth.
            Json.parse(deep.toString().getBytes(StandardCharsets.UTF_8));
            reject(() -> Json.writeTo(root.resolve("encoded-depth.json"),Json.object("facts",records),4096));
        }
        try(RecordArchive records=RecordArchive.createFacts(root.resolve("identity-unicode.bin"),4096,2,4)) {
            records.add(Json.object("id","bad\ud800","kind","declaration","owner",Json.object("world","bad\ud800"),"attributes",Json.object()));
            records.add(Json.object("id","汉😀","kind","declaration","owner",Json.object("world","汉😀"),"attributes",Json.object()));
            if(!records.mightHaveId(0,Collections.singleton(FactHeaders.identityHash("bad?")))
                    || !records.factWorld(0).equals("bad?")
                    || !records.mightHaveId(1,Collections.singleton(FactHeaders.identityHash("汉😀")))
                    || !records.factWorld(1).equals("汉😀"))throw new AssertionError("ENCODED_IDENTITY_MISMATCH");
        }
        System.out.println("encoded output: exact Unicode/numeric bytes; untrusted duplicate/UTF8/numeric/depth, cancel/budget and identity guards passed");
    }
}
