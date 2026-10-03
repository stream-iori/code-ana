package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.util.*;

/** Lossless position/slice digests keep real-project API comparisons within transport limits. */
public final class ModelApiInventory {
    private ModelApiInventory() {}
    /** Test evidence only: complete inventories can exceed the product's single JSON frame. */
    public static void writeOutput(Object value,Path scratch) throws IOException {
        writeOutput(value,scratch,System.out,512L*1024*1024);
    }
    static void writeOutput(Object value,Path scratch,OutputStream output,long maxBytes) throws IOException {
        Files.createDirectories(scratch);
        Path directory=Files.createTempDirectory(scratch,"model-api-output-");
        Path file=directory.resolve("inventory.json");
        try {
            Json.writeTo(file,value,maxBytes);
            Files.copy(file,output);
            output.write('\n');
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
    /** Evidence rows are spooled individually; no whole inventory remains on the Java heap. */
    public static final class Output implements AutoCloseable {
        private static final List<String> SECTIONS=List.of("rows","syntax","sources","types");
        private final Path directory;
        private final Map<String,BufferedWriter> writers=new LinkedHashMap<>();
        private final Set<String> nonempty=new HashSet<>(),sources=new HashSet<>();
        private final OutputStream output;
        private final long limit;
        private long bytes=64;
        private boolean finished;
        public Output(Path scratch) throws IOException {this(scratch,System.out,512L*1024*1024);}
        Output(Path scratch,OutputStream output,long limit) throws IOException {
            Files.createDirectories(scratch);directory=Files.createTempDirectory(scratch,"model-api-records-");
            this.output=output;this.limit=limit;
            try {
                for(String section:SECTIONS) {
                    BufferedWriter writer=Files.newBufferedWriter(directory.resolve(section+".json"),StandardCharsets.UTF_8);
                    writers.put(section,writer);writer.write(section.equals("sources")?'{':'[');
                }
            } catch(IOException|RuntimeException failure) {close();throw failure;}
        }
        private void append(String section,String encoded) throws IOException {
            if(finished)throw new IllegalStateException("MODEL_API_OUTPUT_FINISHED");
            if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
            BufferedWriter writer=writers.get(section);
            if(writer==null)throw new IllegalArgumentException("MODEL_API_OUTPUT_SECTION");
            long size=encoded.getBytes(StandardCharsets.UTF_8).length+1L;
            if(size>limit-bytes)throw new IOException("JSON_BYTE_BUDGET");
            bytes+=size;if(!nonempty.add(section))writer.write(',');writer.write(encoded);
        }
        public void row(Object row) throws IOException {append("rows",Json.write(row));}
        public void syntax(Object row) throws IOException {append("syntax",Json.write(row));}
        public void type(Object row) throws IOException {append("types",Json.write(row));}
        public void source(String path,Object row) throws IOException {
            if(!sources.add(path))throw new IllegalArgumentException("MODEL_API_OUTPUT_DUPLICATE_SOURCE");
            append("sources",Json.write(path)+":"+Json.write(row));
        }
        public void finish() throws IOException {
            if(finished)throw new IllegalStateException("MODEL_API_OUTPUT_FINISHED");
            if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
            for(var entry:writers.entrySet()) {entry.getValue().write(entry.getKey().equals("sources")?'}':']');entry.getValue().close();}
            Path result=directory.resolve("inventory.json");
            try(OutputStream file=Files.newOutputStream(result)) {
                file.write('{');boolean first=true;
                for(String section:SECTIONS) {
                    if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
                    if(!first)file.write(',');first=false;
                    file.write((Json.write(section)+":").getBytes(StandardCharsets.UTF_8));
                    Files.copy(directory.resolve(section+".json"),file);
                }
                file.write(",\"closed\":true}\n".getBytes(StandardCharsets.UTF_8));
            }
            if(Files.size(result)>limit)throw new IOException("JSON_BYTE_BUDGET");
            Files.copy(result,output);finished=true;
        }
        @Override public void close() throws IOException {
            IOException failure=null;
            for(BufferedWriter writer:writers.values())try {writer.close();}catch(IOException error) {failure=error;}
            try(var files=Files.list(directory)) {for(Path path:files.toList())Files.deleteIfExists(path);}
            Files.deleteIfExists(directory);
            if(failure!=null)throw failure;
        }
    }
    public interface Position {int[] at(int offset) throws Exception;}
    public static Object source(byte[] bytes,String text,Position position) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        ByteBuffer pair=ByteBuffer.allocate(8);
        for(int offset=0;offset<=text.length();offset++) {
            int[] value=position.at(offset);pair.clear();pair.putInt(value[0]);pair.putInt(value[1]);
            digest.update(pair.array());
        }
        StringBuilder hash=new StringBuilder();
        for(byte value:digest.digest())hash.append(String.format("%02x",value&255));
        return Json.object("sha256",Json.sha(bytes),"utf16_length",text.length(),
                "text_sha256",Json.sha(text.getBytes(StandardCharsets.UTF_8)),
                "position_count",text.length()+1,"positions_sha256",hash.toString());
    }
    public static Object slice(String text) {
        return text==null?null:Json.object("utf16_length",text.length(),
                "sha256",Json.sha(text.getBytes(StandardCharsets.UTF_8)));
    }
}
