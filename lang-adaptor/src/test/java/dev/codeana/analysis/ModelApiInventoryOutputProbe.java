package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Reproduce complete inventory overflow without changing the product JSON budget. */
public final class ModelApiInventoryOutputProbe {
    public static void main(String[] args) throws Exception {
        Path scratch=Files.createTempDirectory("model-api-output-proof-");
        try {
            Object row=Json.object("source","原字节😀\"\\\r\n","value","x".repeat(1024));
            String encoded=Json.write(row);
            int count=Json.MAX_BYTES/encoded.length()+2;
            Object inventory=Json.object("rows",Collections.nCopies(count,row),"closed",true);
            try {Json.write(inventory);throw new AssertionError("original overflow was not reproduced");}
            catch(IllegalArgumentException expected) {
                if(!"JSON_BUDGET".equals(expected.getMessage()))throw expected;
            }
            MessageDigest actual=MessageDigest.getInstance("SHA-256"),expected=MessageDigest.getInstance("SHA-256");
            try(var sink=new DigestOutputStream(OutputStream.nullOutputStream(),actual)) {
                ModelApiInventory.writeOutput(inventory,scratch,sink,512L*1024*1024);
            }
            expected.update("{\"rows\":[".getBytes(StandardCharsets.UTF_8));
            byte[] bytes=encoded.getBytes(StandardCharsets.UTF_8);
            for(int i=0;i<count;i++) {if(i>0)expected.update((byte)',');expected.update(bytes);}
            expected.update("],\"closed\":true}\n".getBytes(StandardCharsets.UTF_8));
            if(!Arrays.equals(actual.digest(),expected.digest()))throw new AssertionError("inventory lost or reordered rows");
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try {ModelApiInventory.writeOutput(row,scratch,output,32);throw new AssertionError("output budget ignored");}
            catch(IOException failure) {if(!"JSON_BYTE_BUDGET".equals(failure.getMessage()))throw failure;}
            if(output.size()!=0)throw new AssertionError("failed output was published");
            Thread.currentThread().interrupt();
            try {ModelApiInventory.writeOutput(row,scratch,output,4096);throw new AssertionError("cancel ignored");}
            catch(IOException failure) {if(!"CANCELLED".equals(failure.getMessage()))throw failure;}
            finally {Thread.interrupted();}
            try(var files=Files.list(scratch)) {if(files.findAny().isPresent())throw new AssertionError("output spool leaked");}
            System.out.println("complete model API output: overflow reproduced; every row, UTF-8, budget and cancellation verified");
        } finally {Files.deleteIfExists(scratch);}
    }
}
