package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import java.io.IOException;

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
