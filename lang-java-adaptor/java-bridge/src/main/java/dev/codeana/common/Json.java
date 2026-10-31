package dev.codeana.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Canonical transport facade over the shared Jackson streaming boundary. */
public final class Json {
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    /** Container depth includes both a type node and its argument list. */
    public static final int MAX_DEPTH = 256;

    private Json() {}

    public static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("OBJECT_REQUIRED");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("ARRAY_REQUIRED");
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> value, String field) {
        Object v = value.get(field);
        if (!(v instanceof String)) throw new IllegalArgumentException("STRING_REQUIRED: " + field);
        return (String) v;
    }

    public static long number(Map<String, Object> value, String field) {
        Object v = value.get(field);
        if (!(v instanceof Number)) throw new IllegalArgumentException("NUMBER_REQUIRED: " + field);
        try {
            return new java.math.BigDecimal(v.toString()).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("INTEGER_REQUIRED: " + field, invalid);
        }
    }

    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(
                    () -> {
                        try {
                            return MessageDigest.getInstance("SHA-256");
                        } catch (java.security.NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                    });

    public static String sha(byte[] bytes) {
        return sha(bytes, 0, bytes.length);
    }

    public static String sha(byte[] bytes, int offset, int length) {
        MessageDigest digest = SHA256.get();
        digest.reset();
        digest.update(bytes, offset, length);
        return hex(digest.digest());
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 255;
            result[i * 2] = HEX[value >>> 4];
            result[i * 2 + 1] = HEX[value & 15];
        }
        return new String(result);
    }

    public static String key(String namespace, String... parts) {
        try (var timer = PerformanceTrace.start(PerformanceTrace.Metric.IDENTITY_KEY, 0, true)) {
            return namespace + "_" + sha(write(Arrays.asList(parts)).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static byte[] read(Path path) throws IOException {
        if(Files.size(path)>MAX_BYTES)throw new IOException("INPUT_BYTE_BUDGET");
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw new IOException("INPUT_BYTE_BUDGET");
            return bytes;
        }
    }
    public static Object parse(byte[] bytes) {
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("JSON_BYTE_BUDGET");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            return com.anatomist.json.Json.parseTransport(text);
        }catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("INVALID_UTF8",invalid);}
    }
    /** A synchronous consumer; bytes are owned by this call and safe across re-entry. */
    public interface EncodedSink {void accept(byte[] bytes,int offset,int length);}
    public static byte[] encode(Object value) {
        var result=new java.io.ByteArrayOutputStream();
        try(var bounded=bounded(result,MAX_BYTES);var writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(bounded,StandardCharsets.UTF_8),8192)) {
            com.anatomist.json.Json.writeTransport(writer,value);
        }catch(IOException failure){throw new IllegalArgumentException("JSON_BYTE_BUDGET",failure);}
        return result.toByteArray();
    }
    private static java.io.OutputStream bounded(java.io.OutputStream output,long maximum) {
        return new java.io.FilterOutputStream(output) {
            long used;
            private void take(int n)throws IOException {
                if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
                if(n>maximum-used)throw new IOException("JSON_BYTE_BUDGET");used+=n;
            }
            @Override public void write(int value)throws IOException {take(1);out.write(value);}
            @Override public void write(byte[] bytes,int offset,int length)throws IOException {take(length);out.write(bytes,offset,length);}
        };
    }
    public static void encodeTo(Object value,EncodedSink sink) {byte[] bytes=encode(value);sink.accept(bytes,0,bytes.length);}
    public static String write(Object value) {
        var result=new java.io.StringWriter();
        java.io.Writer bounded=new java.io.FilterWriter(result) {
            long used;
            private void take(int n) {if(n>MAX_BYTES-used)throw new IllegalArgumentException("JSON_BUDGET");used+=n;}
            @Override public void write(int c)throws IOException {take(1);out.write(c);}
            @Override public void write(char[] chars,int offset,int length)throws IOException {take(length);out.write(chars,offset,length);}
            @Override public void write(String text,int offset,int length)throws IOException {take(length);out.write(text,offset,length);}
        };
        try {com.anatomist.json.Json.writeTransport(bounded,value);}
        catch(IOException impossible){throw new java.io.UncheckedIOException(impossible);}
        return result.toString();
    }
    public static void admit(Object value,com.anatomist.core.AnalysisContext budget) {com.anatomist.json.Json.admitTransport(value,budget);}
    public static long writeTo(Path path,Object value,long maximum) throws IOException {
        if(maximum<0)throw new IllegalArgumentException("JSON_BYTE_BUDGET");
        final long cap=Math.min(maximum,MAX_BYTES);
        final long[] used={0};
        try(var file=Files.newOutputStream(path,java.nio.file.StandardOpenOption.CREATE_NEW);
            var bounded=new java.io.FilterOutputStream(file) {
                private void take(int n)throws IOException {
                    if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
                    if(n>cap-used[0])throw new IOException("JSON_BYTE_BUDGET");used[0]+=n;
                }
                @Override public void write(int value)throws IOException {take(1);out.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException {take(length);out.write(bytes,offset,length);}
            };
            var writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(bounded,StandardCharsets.UTF_8),8192)) {
            com.anatomist.json.Json.writeTransport(writer,value);
        }
        return used[0];
    }
    public static String sha(Path path) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
            return hex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
