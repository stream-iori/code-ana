package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Version/default precedence, negative memoization and request-local classpath ownership. */
public final class LombokPolicyProbe {
    static Path jar(Path folder,String name,String version,boolean getter) throws Exception {
        var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");
        if(version!=null)manifest.getMainAttributes().putValue("Lombok-Version",version);
        Path file=folder.resolve(name);
        try(var output=new JarOutputStream(Files.newOutputStream(file),manifest)) {
            if(getter){output.putNextEntry(new JarEntry("lombok/Getter.class"));output.write(0);output.closeEntry();}
        }
        return file;
    }
    static LombokGeneratedPolicy policy(Path... jars) {
        return new LombokGeneratedPolicy(Json.object("classpath",Arrays.stream(jars).map(Path::toString).toList()));
    }
    public static void main(String[] args) throws Exception {
        Path folder=Files.createTempDirectory("lombok-policy-");
        try {
            Path old=jar(folder,"old.jar","1.18.22",true), modern=jar(folder,"new.jar","1.18.34",true),
                    unknown=jar(folder,"unknown.jar",null,true), unrelated=jar(folder,"other.jar",null,false);
            var selected=policy(unrelated,modern);
            for(int i=0;i<500;i++)if(!Boolean.TRUE.equals(selected.value()))throw new AssertionError("modern default");
            if(selected.inspectedArchives()!=2)throw new AssertionError("classpath rescanned per file");
            var negative=policy(unknown);
            for(int i=0;i<500;i++)if(negative.value()!=null)throw new AssertionError("unknown version invented");
            if(negative.inspectedArchives()!=1)throw new AssertionError("negative policy rescanned");
            var ordered=policy(old,modern);
            if(!Boolean.FALSE.equals(ordered.value()) ||ordered.inspectedArchives()!=1)
                throw new AssertionError("classpath order changed");
            if(!Boolean.TRUE.equals(policy(modern).value()) ||!Boolean.FALSE.equals(policy(old).value()))
                throw new AssertionError("policy leaked across contexts");
            System.out.println("Lombok classpath scanned once; negative results/order/context isolation preserved");
        } finally {
            try(var files=Files.list(folder)){for(Path file:files.toList())Files.delete(file);}
            Files.delete(folder);
        }
    }
}
