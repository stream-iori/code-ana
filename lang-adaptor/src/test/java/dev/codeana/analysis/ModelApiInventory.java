package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Lossless position/slice digests keep real-project API comparisons within transport limits. */
public final class ModelApiInventory {
    private ModelApiInventory() {}
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
