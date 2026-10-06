package com.anatomist.core.javac;

import com.anatomist.core.SourceScope;
import com.anatomist.core.java.*;
import dev.codeana.common.Json;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.lang.reflect.*;
import java.util.*;

/** Test-only: invoke the frozen compiler on exactly one supplied Maven context, without replanning it. */
public final class FrozenExactModelApiContext {
    public static void analyze(Map<String,Object> context,Path home,Path scratch,boolean effective,
                              java.util.function.Consumer<JavaAnalysisContext> consumer) throws Exception {
        List<Path> sources=new ArrayList<>(),classpath=new ArrayList<>();Set<Path> published=new LinkedHashSet<>();
        for(Object raw:Json.list(context.get("sources"))) {
            var row=Json.map(raw);Path path=Path.of(Json.string(row,"input")).toRealPath();
            if(!Json.sha(path).equals(Json.string(row,"hash")))throw new IOException("FROZEN_ORIGINAL_SOURCE_DRIFT");
            sources.add(path);if(Boolean.TRUE.equals(row.get("publish")))published.add(path);
        }
        for(Object raw:Json.list(context.get("classpath")))classpath.add(Path.of(raw.toString()).toRealPath());
        Files.createDirectories(scratch);
        JavacOptions options=new JavacOptions(home,true,true,false,false,scratch,1,512);
        JavacToolchain toolchain=JavacToolchain.resolve(options,8,home);
        Path worker=scratch.resolve("frozen-javac-worker.jar");
        try(InputStream resource=JavacBackend.class.getResourceAsStream("/META-INF/anatomist/javac-worker.jar")) {
            if(resource==null)throw new IOException("FROZEN_WORKER_MISSING");Files.copy(resource,worker);
        }
        if(!Json.sha(worker).equals("914727fd5e11a958f9c1b1b563fbbf1b1461daf06e1a5538ecc23d80b4118494"))
            throw new IOException("FROZEN_WORKER_DRIFT");
        var command=List.of(home.resolve("bin/java").toString(),"-Xmx512m","-cp",
                worker+File.pathSeparator+home.resolve("lib/tools.jar"),"com.anatomist.worker.JavacWorker","1");
        var cache=new JavacDeclarationCache(64L<<20);
        Set<Path> fullModels=Boolean.TRUE.equals(context.get("full_auxiliary_model"))?new LinkedHashSet<>(sources):published;
        try(var lease=cache.lease(sources,fullModels,true,toolchain.fingerprint());
            var session=new JavacWorkerSession(command,scratch,Duration.ofSeconds(3600))) {
            Path response=scratch.resolve("frozen-model.bin");
            var results=session.executeBatch(List.of(new JavacBatchProtocol.Request(1,0,0,
                out->JavacProtocol.request(out,toolchain,sources,classpath,false,null,null,scratch,lease),response)));
            var result=results.getFirst();
            if(result.bytes()!=Files.size(response)||!result.sha256().equals(Json.sha(response)))
                throw new IOException("FROZEN_EXACT_RESPONSE_DRIFT");
            // Consume the same frozen worker response one unit at a time. No compiler/model
            // implementation changes; the frozen 512 MiB admission cap applies to each live
            // unit, rather than retaining all 2852 units to construct a test-only catalogue.
            try(var raw=new DataInputStream(new BufferedInputStream(Files.newInputStream(response)))) {
                if(raw.readInt()!=JavacProtocol.MAGIC)throw new IOException("FROZEN_PROTOCOL_VERSION");
                string(raw);int count=count(raw,1000000);
                if(count!=sources.size())throw new IOException("FROZEN_EXACT_UNIT_COUNT");
                Set<Path> seen=new HashSet<>();
                List<JavaUnit> complete=effective?new ArrayList<>():null;
                for(int i=0;i<count;i++) {
                    var budget=new JavacDecodeBudget(JavacDecodeBudget.DEFAULT_BYTES);
                    var in=new JavacDecodeBudget.Input(raw,budget);
                    Path path=Path.of(string(in)).toAbsolutePath().normalize();String hash=string(in);
                    if(!seen.add(path)||!sources.contains(path)||!hash.matches("[a-f0-9]{64}"))throw new IOException("FROZEN_UNIT_IDENTITY");
                    boolean declarations=in.readBoolean();
                    if(declarations&&!lease.declarations(path))throw new IOException("FROZEN_DECLARATION_VIEW");
                    JavaNode tree=(JavaNode)call("readFrame",new Class<?>[]{DataInputStream.class,Path.class,JavacDeclarationCache.Lease.class},
                        in,path,declarations?lease:null);
                    if(tree==null)throw new IOException("FROZEN_SOURCE_TREE_MISSING");
                    JavaProcessingState processing=switch(in.readUnsignedByte()) {
                        case 0->JavaProcessingState.NONE;case 1->JavaProcessingState.LOMBOK;
                        default->throw new IOException("FROZEN_PROCESSOR_STATE");
                    };
                    @SuppressWarnings("unchecked") List<String> dictionary=(List<String>)call("strings",new Class<?>[]{DataInputStream.class},in);
                    JavaTypes types=(JavaTypes)call("types",new Class<?>[]{DataInputStream.class,List.class},in,dictionary);
                    Map<Integer,JavaBinding> bindings=new LinkedHashMap<>();int bindingCount=count(in,1000000);
                    for(int j=0;j<bindingCount;j++) {
                        int id=in.readInt();if(id<0)throw new IOException("FROZEN_SYNTAX_IDENTITY");
                        String kind=reference(in,dictionary),owner=reference(in,dictionary),name=reference(in,dictionary);
                        List<String> params=new ArrayList<>();int size=count(in,1000000);
                        for(int p=0;p<size;p++)params.add(reference(in,dictionary));
                        JavaBinding binding=new JavaBinding(id,kind,owner,name,List.copyOf(params),
                            reference(in,dictionary),reference(in,dictionary),in.readBoolean(),references(in,dictionary),
                            references(in,dictionary),reference(in,dictionary),in.readInt(),in.readInt(),in.readInt(),
                            in.readBoolean(),reference(in,dictionary),reference(in,dictionary),in.readBoolean());
                        if(bindings.putIfAbsent(id,binding)!=null)throw new IOException("FROZEN_BINDING_IDENTITY");
                    }
                    JavaUnit unit=new JavaUnit(path,tree,processing,bindings,types,declarations,JavacDeclarations.readFrame(in),hash);
                    if(effective)complete.add(unit);
                    else consumer.accept(new JavaAnalysisContext(Json.string(context,"module"),SourceScope.valueOf(Json.string(context,"scope")),
                        List.of(unit),published.contains(path)?Set.of(path):Set.of(),List.of(),""));
                }
                raw.readBoolean();String failure=string(raw);int diagnostics=count(raw,1000000);
                for(int i=0;i<diagnostics;i++){string(raw);string(raw);string(raw);raw.readLong();raw.readLong();string(raw);}
                Set<String> phases=new HashSet<>();int timings=count(raw,32);
                for(int i=0;i<timings;i++)if(!phases.add(string(raw))||raw.readLong()<0)throw new IOException("FROZEN_TIMING_IDENTITY");
                if(raw.readInt()!=0x414A4544||raw.read()!=-1||!failure.isEmpty())throw new IOException("FROZEN_EXACT_CONTEXT_INCOMPLETE: "+failure);
                // AST overlays need the actual cross-file context (not isolated units).
                if(effective)consumer.accept(new JavaAnalysisContext(Json.string(context,"module"),SourceScope.valueOf(Json.string(context,"scope")),
                        complete,published,List.of(),""));
                Files.writeString(scratch.resolve("frozen-worker-receipt.json"),Json.write(Json.object(
                    "response_sha256",result.sha256(),"response_bytes",result.bytes(),"units",count,
                    "compilations",1,"processing",false,"decode_view","one_unit_at_a_time",
                    "unit_decode_budget_bytes",JavacDecodeBudget.DEFAULT_BYTES,"capacity_accepted",false)));
            }
        }
    }
    private static final Map<String,Method> METHODS=new HashMap<>();
    private static Object call(String name,Class<?>[] signature,Object... values) throws Exception {
        String key=name+Arrays.toString(signature);Method method=METHODS.get(key);
        if(method==null){method=JavacProtocol.class.getDeclaredMethod(name,signature);method.setAccessible(true);METHODS.put(key,method);}
        try{return method.invoke(null,values);}catch(InvocationTargetException e){
            if(e.getCause() instanceof Exception cause)throw cause;
            if(e.getCause() instanceof Error cause)throw cause;
            throw e;
        }
    }
    private static String string(DataInputStream in) throws Exception {
        return (String)call("string",new Class<?>[]{DataInputStream.class},in);
    }
    private static int count(DataInputStream in,int max) throws Exception {
        return (Integer)call("count",new Class<?>[]{DataInputStream.class,int.class},in,max);
    }
    private static String reference(DataInputStream in,List<String> dictionary) throws Exception {
        return (String)call("reference",new Class<?>[]{DataInputStream.class,List.class},in,dictionary);
    }
    @SuppressWarnings("unchecked") private static List<String> references(DataInputStream in,List<String> dictionary) throws Exception {
        return (List<String>)call("references",new Class<?>[]{DataInputStream.class,List.class},in,dictionary);
    }
}
