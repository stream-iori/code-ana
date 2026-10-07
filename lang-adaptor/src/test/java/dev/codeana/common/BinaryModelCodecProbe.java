package dev.codeana.common;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lossless opaque extension fields and malformed input must survive the model transport change. */
public final class BinaryModelCodecProbe {
    private static void rejects(byte[] bytes) throws Exception {
        try {BinaryModelCodec.decode(bytes);throw new AssertionError("malformed model accepted");}
        catch(IOException expected) {}
    }
    public static void main(String[] args) throws Exception {
        List<Object> rows=new ArrayList<Object>();
        for(int i=0;i<1000;i++) rows.add(Json.object("kind","row","source","p/模块😀.java",
                "signature","p.Type#m"+i+"()","attributes",Json.object("unknown-extension",
                Arrays.asList(null,true,false,Long.MIN_VALUE,Long.MAX_VALUE,new BigDecimal("1.25E+100"),
                "\ud800","\udc00","\u0000","😀"),"complete",true),"start",i,"end",i+2));
        byte[] json=Json.write(rows).getBytes(StandardCharsets.UTF_8),binary=BinaryModelCodec.encode(rows);
        if(!Json.write(rows).equals(Json.write(BinaryModelCodec.decode(binary))))
            throw new AssertionError("opaque fields, numeric or UTF16 drift");
        if(binary.length>=json.length)throw new AssertionError("representative model grew");
        for(byte[] bad:new byte[][] {new byte[0],{99},{0,0},{4,2,(byte)0xc0,(byte)0xaf},
                {8,127},{6,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,15},
                {5,2,4,1,'x',0,4,1,'x',0}, {3,(byte)128}})rejects(bad);
        byte[] truncated=Arrays.copyOf(binary,binary.length-1);rejects(truncated);
        Object depth=null;
        for(int i=0;i<Json.MAX_DEPTH+2;i++)depth=Collections.singletonList(depth);
        try {BinaryModelCodec.encode(depth);throw new AssertionError("depth accepted");}
        catch(IOException expected) {}
        Thread.currentThread().interrupt();
        try {BinaryModelCodec.decode(binary);throw new AssertionError("cancel ignored");}
        catch(IOException expected) {if(!"CANCELLED".equals(expected.getMessage()))throw expected;}
        finally {Thread.interrupted();}
        System.out.println("binary model roundtrip/corruption/depth/cancel: json="+json.length+" binary="+binary.length);
    }
}
