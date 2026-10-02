package dev.codeana.compiler;

import dev.codeana.common.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real SDK8 compiler -> RowSpool -> final model graph, including a failed neighboring source. */
public final class CompilerGraphArchiveProbe {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("compiler-graph-archive-");
        try {
            String nested="Leaf";for(int i=0;i<80;i++)nested="Box<"+nested+">";
            String source="class Leaf {} class Box<T> {} class Use<T extends Comparable<T>> {"+nested+" deep;T recursive;Missing broken;}";
            Path input=root.resolve("App.java");Files.write(input,source.getBytes(StandardCharsets.UTF_8));
            Map<String,Object> unit=Json.object("path","app/App.java","input",input.toString(),"hash",Json.sha(input),"publish",true);
            Map<String,Object> request=Json.object("version",7,"context","app@MAIN","type_graph",true,"sources",Arrays.asList(unit),"classpath",Collections.emptyList());
            Path control=root.resolve("request.json"),model=root.resolve("model.bin");
            Files.write(control,Json.write(request).getBytes(StandardCharsets.UTF_8));
            Path javaBinary=Paths.get(System.getProperty("java.home"),"bin","java");
            String classpath=args[0]+java.io.File.pathSeparator+System.getProperty("java.class.path");
            Process process=new ProcessBuilder(javaBinary.toString(),"-Xmx128m","-cp",classpath,
                    "dev.codeana.compiler.CompilerBridge",control.toString(),model.toString())
                    .redirectError(root.resolve("stderr.log").toFile()).redirectOutput(root.resolve("stdout.log").toFile()).start();
            if(!process.waitFor(60,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("compiler timeout");}
            if(process.exitValue()!=0)throw new AssertionError(new String(Files.readAllBytes(root.resolve("stderr.log")),StandardCharsets.UTF_8));
            boolean deep=false,recursive=false,broken=false;
            try(ModelArchive.OpenModel decoded=ModelArchive.open(model,Json.sha(model),64L<<20,"app@MAIN")) {
                for(Object raw:Json.list(decoded.model().get("declarations"))) {
                    Map<String,Object> declaration=Json.map(raw),attrs=Json.map(declaration.get("attributes"));
                    if("Use#deep".equals(declaration.get("signature"))) {
                        Map<String,Object> type=Json.map(attrs.get("type_model"));
                        if(!TypeGraph.isView(type))throw new AssertionError("compiler graph became a recursive tree");
                        for(int i=0;i<80;i++) {
                            if(!"Box".equals(type.get("element")) || !Boolean.TRUE.equals(type.get("complete")))throw new AssertionError("deep type lost at "+i);
                            type=Json.map(Json.list(type.get("arguments")).get(0));
                        }
                        if(!"Leaf".equals(type.get("element")))throw new AssertionError("deep leaf lost");deep=true;
                    }
                    if("Use#recursive".equals(declaration.get("signature"))) {
                        Map<String,Object> type=Json.map(attrs.get("type_model"));
                        TypeGraph graph=TypeGraph.owner(type);
                        TypeGraph roundtrip=TypeGraph.decode(graph.canonical(Arrays.asList(TypeGraph.reference(type))));
                        if(!"TYPEVAR".equals(roundtrip.node(0).get("kind")))throw new AssertionError("formal root lost");recursive=true;
                    }
                    if("Use#broken".equals(declaration.get("signature")))broken=!Boolean.TRUE.equals(Json.map(attrs.get("type_model")).get("complete"));
                }
                for(Object raw:Json.list(decoded.model().get("diagnostics"))) {
                    String code=String.valueOf(Json.map(raw).get("code"));
                    if(code.startsWith("TYPE_MODEL_") || code.equals("TYPE_GRAPH_BUDGET"))throw new AssertionError("healthy graph truncated: "+code);
                }
            }
            if(!deep || !recursive || !broken)throw new AssertionError("declaration graph coverage missing");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(root)) {
                files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception failure){throw new RuntimeException(failure);}});
            }
        }
        System.out.println("real compiler graph -> RowSpool -> model: 80 generic levels, recursive bounds and neighboring error preserved");
    }
}
