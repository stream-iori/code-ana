package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Invalid source/cycle/budget/cancellation must never become a partial successful model. */
public final class DetachedModelApiGuards {
    private static Map<String,Object> row(String signature,String parent) {
        return Json.object("kind","declaration","source","Use.java","signature",signature,"parent",parent,
            "name",signature,"start",0,"end",0,"attributes",Json.object("element_kind","CLASS"));
    }
    private static DetachedJavaModel view(Path path,byte[] bytes,List<Object> declarations) {
        return new DetachedJavaModel(ExtensionContext.freezeMap(Json.object("sources",List.of(Json.object(
            "path","Use.java","input",path.toString(),"hash",Json.sha(bytes))),"declarations",declarations,"syntax",List.of())));
    }
    private static void fails(String reason,Action action) throws Exception {
        try {action.run();throw new AssertionError("accepted invalid "+reason);}
        catch(AssertionError failure) {throw failure;}
        catch(Exception failure) {if(!failure.toString().contains(reason))throw failure;}
    }
    private interface Action {void run() throws Exception;}
    private static Map<String,Object> syntax(int id,int parent,String kind) {
        return Json.object("id",id,"parent",parent,"source","Use.java","kind",kind,"start",0,"end",1,"roles",Map.of());
    }
    private static DetachedJavaModel syntaxView(List<Object> syntax) {
        return new DetachedJavaModel(ExtensionContext.freezeMap(Json.object("sources",List.of(Json.object("path","Use.java")),
                "declarations",List.of(),"syntax",syntax)));
    }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);Path source=directory.resolve("Use.java");
        try(var model=syntaxView(List.of(syntax(1,0,"CLASS"),syntax(2,1,"NEW_CLASS"),syntax(3,2,"IDENTIFIER"),
                                       syntax(4,2,"CLASS"),syntax(3,4,"IDENTIFIER")))) {
            var syntax=model.syntax("Use.java");
            if(syntax.nodes().size()!=4||model.syntaxAt("Use.java",0,1).size()!=4
                ||Json.number(syntax.parent(syntax.node(3).orElseThrow()).orElseThrow(),"id")!=2)
                throw new AssertionError("shared syntax did not keep first parent");
        }
        try(var model=syntaxView(List.of(syntax(1,0,"CLASS"),syntax(1,0,"METHOD")))) {fails("SYNTAX_IDENTITY",()->model.syntax("Use.java"));}
        try(var model=syntaxView(List.of(syntax(1,0,"CLASS"),syntax(2,1,"IDENTIFIER"),syntax(2,99,"IDENTIFIER")))) {
            fails("PARENT_OUTSIDE_SOURCE",()->model.syntax("Use.java"));
        }
        try(var model=syntaxView(List.of(syntax(1,0,"CLASS"),syntax(2,0,"CLASS"),syntax(1,2,"CLASS"),syntax(2,1,"CLASS")))) {
            fails("PARENT_CYCLE",()->model.syntax("Use.java"));
        }
        byte[] bytes="// 原字节😀\r\na\rb\nc\u2028d".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(source,bytes);
        try(var model=view(source,bytes,List.of(row("A","")))) {
            var escaped=model.source("Use.java");String text=escaped.text();
            if(!escaped.position(text.length()).equals(new DetachedJavaModel.Position(4,4)))throw new AssertionError("CR/LF/Unicode positions changed");
            fails("OUTSIDE_CONTEXT",()->model.unit("other.java"));
            fails("Index",()->model.declaration(new DetachedJavaModel.DeclarationRef("Use.java",99)));
            fails("SPAN_UNAVAILABLE",()->escaped.slice(-1,1));fails("OFFSET_UNAVAILABLE",()->escaped.position(text.length()+1));
            Thread.currentThread().interrupt();try {fails("CANCELLED",()->model.type("A"));}finally {Thread.interrupted();}
            if(!(model.type("A") instanceof DetachedJavaModel.FoundType))throw new AssertionError("cancelled index poisoned retry");
            model.close();if(!Arrays.equals(escaped.bytes(),bytes))throw new AssertionError("closing changed escaped original bytes");
            fails("CLOSED",()->model.declarations("Use.java"));
        }
        try(var model=view(source,bytes,List.of(row("A","B"),row("B","A")))) {fails("PARENT_CYCLE",()->model.type("A"));}
        List<Object> deep=new ArrayList<>();for(int i=0;i<131;i++)deep.add(row("A"+i,i==0?"":"A"+(i-1)));
        try(var model=view(source,bytes,deep)) {fails("DEPTH_BUDGET",()->model.type("A0"));}
        try(var model=view(source,bytes,Collections.nCopies(100001,row("A","")))) {fails("DECLARATION_BUDGET",()->model.type("A"));}
        Files.write(source,new byte[]{(byte)0xff,0x01});
        try(var model=view(source,new byte[]{(byte)0xff,0x01},List.of())) {
            var invalid=model.source("Use.java");if(!Arrays.equals(invalid.bytes(),new byte[]{(byte)0xff,0x01}))throw new AssertionError("invalid bytes changed");
            fails("MalformedInputException",invalid::text);
        }
        try(var model=view(source,bytes,List.of())) {fails("SOURCE_CHANGED",()->model.source("Use.java"));}
        try(var file=java.nio.channels.FileChannel.open(source,StandardOpenOption.WRITE)) {file.position(64L*1024*1024);file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));}
        try(var model=view(source,bytes,List.of())) {fails("SOURCE_BYTES_BUDGET",()->model.source("Use.java"));}
        Files.delete(source);System.out.println("detached-model-api guards passed");
    }
}
