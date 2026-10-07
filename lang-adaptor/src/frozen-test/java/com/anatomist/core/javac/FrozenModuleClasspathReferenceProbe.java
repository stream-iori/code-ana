package com.anatomist.core.javac;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.*;
import java.nio.file.*;
import java.util.*;
public class FrozenModuleClasspathReferenceProbe {
 public static void main(String[] args) throws Exception {
  Path project=Path.of(args[0]).toAbsolutePath(),home=Path.of(args[1]),one=Path.of(args[2]).toAbsolutePath(),two=Path.of(args[3]).toAbsolutePath();
  Path lib=project.resolve("lib/api/Api.java"),use=project.resolve("use/p/Use.java");
  Files.createDirectories(lib.getParent());Files.createDirectories(use.getParent());
  Files.writeString(lib,"package api;public class Api {public static final int VALUE=ext.Constants.VALUE;}");
  Files.writeString(use,"package p;public class Use {public static final int seen=api.Api.VALUE;public static final int direct=ext.Constants.VALUE;}");
  var roots=List.of(new SourceRoot(project.resolve("lib"),"lib",SourceScope.MAIN),new SourceRoot(project.resolve("use"),"use",SourceScope.MAIN));
  var ext1=new JavacMavenInputs.ExternalDependency("fixture:dep:1","","jar",one);
  var ext2=new JavacMavenInputs.ExternalDependency("fixture:dep:2","","jar",two);
  var modules=List.of(new JavacMavenInputs.Module("lib",project.resolve("lib"),"fixture:lib:1",8,List.of(project.resolve("lib")),List.of(),List.of(ext1),List.of(ext1),"fixture-lib"),
   new JavacMavenInputs.Module("use",project.resolve("use"),"fixture:use:1",8,List.of(project.resolve("use")),List.of(),List.of(new JavacMavenInputs.ReactorDependency("fixture:lib:1","lib"),ext2),List.of(),"fixture-use"));
  var inputs=new JavacMavenInputs(modules,Map.of(),"fixture-inputs",true);
  var options=new JavacOptions(home,true,true,false,false,project.resolve("cache"),1,128);
  var backend=new JavacBackend(project,JavacToolchain.resolve(options,8,home),List.of(),roots,options);
  backend.setMavenInputs(inputs);backend.setSourceSignatures(true);
  Map<String,Object> constants=new TreeMap<>();
  var result=backend.analyzeFiles(List.of(lib,use),context->{if(context.units().stream().noneMatch(u->u.path().equals(use)))return;try(var model=new JavaModel(context)) {
   for(var ref:model.declarations(use)) {var d=model.declaration(ref);if(d.constant()!=null)constants.put(d.symbol().name(),((com.anatomist.core.java.JavaBinaryType.Scalar)d.constant()).value());}
  }});
  System.out.println(constants);System.out.println(backend.signatureStats());
  if(!result.complete() || !Integer.valueOf(1).equals(constants.get("seen")) || !Integer.valueOf(2).equals(constants.get("direct")))throw new AssertionError("FROZEN_MODULE_CP_BINDING");
 }
}
