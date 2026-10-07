package dev.codeana.common;

import java.io.*;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Java 8 data-only model frames. Maps keep insertion order; graph edges remain scalar IDs.
 * A fixed dictionary is versioned with ModelArchive, never with a process or mutable cache.
 * Public facts continue to use their canonical JSON representation.
 */
final class BinaryModelCodec {
    private static final String[] WORDS = {
        "kind","value","version","id","source","signature","parent","attributes",
        "name","start","end","complete","display","element","owner","parameters",
        "arguments","type_model","return_type_model","parameter_type_models","type",
        "modifiers","annotations","source_annotations","source_hash","world","context",
        "resolved","target","receiver","children","$graph","$type","$object","graph",
        "row","graph_node","header","counts","graphs","sections","types","diagnostics",
        "sources","declarations","observations","syntax","external","annotation_types",
        "binding_origin","origin","element_kind","javac","DECLARED","TYPEVAR","ERROR",
        "UNKNOWN","ARRAY","EXECUTABLE","NONE","VOID","CLASS","METHOD","FIELD",
        "INTERFACE","CONSTRUCTOR","BOOLEAN","INT","LONG","STRING_LITERAL",
        "upper","lower","component","enclosing","extends","super","bounds","return",
        "thrown","interfaces","superclass","binary_name","publish","scope","module",
        "input","hash","auxiliary","call","reference","tree_id","constant","descriptor",
        "type_parameters","written_type_annotations","sam","functional_type_model"
    };
    private static final Map<String,Integer> IDS = new HashMap<String,Integer>();
    static {for(int i=0;i<WORDS.length;i++)IDS.put(WORDS[i],i);}
    private BinaryModelCodec() {}

    private static final class BoundedBytes extends ByteArrayOutputStream {
        private void reserve(int size) {
            if(size<0 || size>RecordArchive.MAX_RECORD_BYTES-count)
                throw new IllegalArgumentException("MODEL_FRAME_BYTES_BUDGET");
        }
        public synchronized void write(int value) {reserve(1);super.write(value);}
        public synchronized void write(byte[] value,int offset,int length) {reserve(length);super.write(value,offset,length);}
    }

    static byte[] encode(Object value) throws IOException {
        BoundedBytes bytes=new BoundedBytes();
        DataOutputStream out=new DataOutputStream(bytes);
        write(out,value,0);out.flush();return bytes.toByteArray();
    }

    private static void unsigned(DataOutputStream out,long value) throws IOException {
        while((value & ~127L)!=0) {out.writeByte((int)value & 127 | 128);value >>>=7;}
        out.writeByte((int)value);
    }
    private static long unsigned(DataInputStream in) throws IOException {
        long value=0;
        for(int shift=0;shift<64;shift+=7) {
            int next=in.readUnsignedByte();
            if(shift==63 && (next & ~1)!=0)throw new IOException("MODEL_FRAME_INTEGER");
            value |= (long)(next & 127)<<shift;
            if((next & 128)==0)return value;
        }
        throw new IOException("MODEL_FRAME_INTEGER");
    }
    private static int count(DataInputStream in,int minimum) throws IOException {
        long value=unsigned(in);
        if(value<0 || value>in.available()/minimum)throw new IOException("MODEL_FRAME_COUNT");
        return (int)value;
    }

    private static void string(DataOutputStream out,String value) throws IOException {
        Integer id=IDS.get(value);
        if(id!=null) {out.writeByte(8);unsigned(out,id);return;}
        // Preserve annotation constants containing lone UTF-16 surrogates exactly.
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c) && i+1<value.length() && Character.isLowSurrogate(value.charAt(i+1))) {i++;continue;}
            if(Character.isSurrogate(c)) {
                out.writeByte(9);unsigned(out,value.length());
                for(int j=0;j<value.length();j++)out.writeChar(value.charAt(j));
                return;
            }
        }
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(4);unsigned(out,bytes.length);out.write(bytes);
    }
    private static void write(DataOutputStream out,Object value,int depth) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
        if(depth>Json.MAX_DEPTH)throw new IOException("MODEL_FRAME_DEPTH");
        if(value==null)out.writeByte(0);
        else if(value instanceof Boolean)out.writeByte((Boolean)value?2:1);
        else if(value instanceof String)string(out,(String)value);
        else if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            out.writeByte(3);long number=((Number)value).longValue();unsigned(out,(number<<1)^(number>>63));
        } else if(value instanceof Number) {
            String number=value.toString();
            if(number.length()>1024)throw new IOException("MODEL_FRAME_NUMBER");
            try {new BigDecimal(number);}catch(NumberFormatException bad) {throw new IOException("MODEL_FRAME_NUMBER",bad);}
            out.writeByte(7);string(out,number);
        } else if(value instanceof Map) {
            Map<String,Object> map=Json.map(value);out.writeByte(5);unsigned(out,map.size());
            for(Map.Entry<String,Object> entry:map.entrySet()) {string(out,entry.getKey());write(out,entry.getValue(),depth+1);}
        } else if(value instanceof List) {
            List<Object> list=Json.list(value);out.writeByte(6);unsigned(out,list.size());
            for(Object item:list)write(out,item,depth+1);
        } else throw new IOException("MODEL_FRAME_VALUE");
    }

    static Object decode(byte[] bytes) throws IOException {
        if(bytes.length<1 || bytes.length>RecordArchive.MAX_RECORD_BYTES)throw new IOException("MODEL_FRAME_BYTES_BUDGET");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));
        Object result=read(in,0);
        if(in.available()!=0)throw new IOException("MODEL_FRAME_TRAILING_BYTES");
        return result;
    }
    private static String text(DataInputStream in,int tag) throws IOException {
        if(tag==8) {
            long id=unsigned(in);if(id<0 || id>=WORDS.length)throw new IOException("MODEL_FRAME_DICTIONARY");
            return WORDS[(int)id];
        }
        if(tag==9) {
            int size=count(in,2);char[] chars=new char[size];
            for(int i=0;i<size;i++)chars[i]=in.readChar();return new String(chars);
        }
        if(tag!=4)throw new IOException("MODEL_FRAME_STRING");
        byte[] bytes=new byte[count(in,1)];in.readFully(bytes);
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad) {throw new IOException("MODEL_FRAME_UTF8",bad);}
    }
    private static Object read(DataInputStream in,int depth) throws IOException {
        if(Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
        if(depth>Json.MAX_DEPTH)throw new IOException("MODEL_FRAME_DEPTH");
        int tag=in.readUnsignedByte();
        switch(tag) {
            case 0:return null;
            case 1:return false;
            case 2:return true;
            case 3:long number=unsigned(in);return (number>>>1)^-(number & 1);
            case 4:case 8:case 9:return text(in,tag);
            case 5: {
                int size=count(in,2);Map<String,Object> map=new LinkedHashMap<String,Object>();
                for(int i=0;i<size;i++) {
                    String key=text(in,in.readUnsignedByte());
                    if(map.containsKey(key))throw new IOException("MODEL_FRAME_DUPLICATE_KEY");
                    map.put(key,read(in,depth+1));
                }
                return map;
            }
            case 6: {
                int size=count(in,1);List<Object> list=new ArrayList<Object>(size);
                for(int i=0;i<size;i++)list.add(read(in,depth+1));return list;
            }
            case 7: {
                String decimal=text(in,in.readUnsignedByte());
                if(decimal.length()>1024)throw new IOException("MODEL_FRAME_NUMBER");
                try {return new BigDecimal(decimal);}catch(NumberFormatException bad) {throw new IOException("MODEL_FRAME_NUMBER",bad);}
            }
            default:throw new IOException("MODEL_FRAME_TAG");
        }
    }
}
