package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Test-only repeatable context profiler; records are copied before session cleanup. */
public final class RecoveryProfileProbe {
    public static void main(String[] args) throws Exception {
        Path requestPath=Path.of(args[0]),output=Path.of(args[1]);
        Files.createDirectories(output);
        Map<String,Object> request=Json.map(Json.parse(Json.read(requestPath)));
        long started=System.nanoTime();
        try(CompilerSession session=new CompilerSession(request)) {
            Map<String,Object> result=JavaAnalysis.run(request,session);
            // Stream exact facts so a large result does not acquire a second JSON copy.
            Json.writeTo(output.resolve("result.json"),result,4L*1024*1024*1024);
            try(var directories=Files.list(Path.of(Json.string(request,"scratch")))) {
                for(Path directory:directories.filter(p->p.getFileName().toString().startsWith("compiler-")).toList())
                    for(String name:List.of("phases.ndjson","recovery-metrics.ndjson")) {
                        Path file=directory.resolve(name);
                        if(Files.exists(file))Files.copy(file,output.resolve(name),StandardCopyOption.REPLACE_EXISTING);
                    }
            }
            Files.writeString(output.resolve("timing.json"),Json.write(Json.object(
                    "elapsed_seconds",(System.nanoTime()-started)/1e9,"fact_count",Json.list(result.get("facts")).size(),
                    "product_acceptance",false,"performance_gate",false)));
        }
    }
}
