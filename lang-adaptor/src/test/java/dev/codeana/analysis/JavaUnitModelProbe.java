package dev.codeana.analysis;

import dev.codeana.analysis.extension.*;
import dev.codeana.analysis.model.JavaContextModel;
import dev.codeana.common.*;
import java.nio.file.*;
import java.util.*;

/** Unit identity/ambiguity, extension rollback, archive mutation and lease guards. */
public final class JavaUnitModelProbe {
    static Map<String,Object> declaration(String source,String signature,String parent,String kind,boolean getter) {
        Map<String,Object> attributes=Json.object("element_kind",kind,"modifiers",List.of("public"),
                "annotations",getter?List.of(Json.object("type","lombok.Getter","complete",true,
                        "effective",Map.of(),"explicit",Map.of())):List.of());
        if(kind.equals("FIELD"))attributes.putAll(Json.object("has_initializer",false,"type","int",
                "type_model",Json.object("version",2,"kind","INT","complete",true)));
        return Json.object("kind","declaration","source",source,"signature",signature,"parent",parent,
                "name",signature.contains("#")?signature.substring(signature.indexOf('#')+1):signature,
                "start",0,"end",1,"generated",false,"attributes",attributes);
    }
    static Map<String,Object> model() {
        var rows=new ArrayList<Object>();
        for(String source:List.of("A.java","B.java")) {
            String name=source.substring(0,1);
            rows.add(declaration(source,name,"","CLASS",true));
            rows.add(declaration(source,name+"#value",name,"FIELD",false));
        }
        return Json.object("version",ModelArchive.VERSION,"context","units","sources",List.of(),
                "declarations",rows,"observations",List.of(),"syntax",List.of(),"external",List.of(),
                "annotation_types",List.of(),"diagnostics",List.of());
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> raw=model();
        var duplicates=new ArrayList<>(Json.list(raw.get("declarations")));
        duplicates.add(declaration("Other.java","A","","CLASS",false));
        var duplicateModel=new LinkedHashMap<>(raw);duplicateModel.put("declarations",duplicates);
        JavaContextModel.DeclarationRef retained;
        try(var context=new JavaContextModel(duplicateModel)) {
            if(!(context.lookup("A") instanceof JavaContextModel.AmbiguousType))throw new AssertionError("duplicate collapsed");
            if(!(context.lookup("B") instanceof JavaContextModel.FoundType found))throw new AssertionError("missing unit");
            retained=found.declaration();
            if(context.members("B").size()!=1)throw new AssertionError("members");
            try { context.declaration(new JavaContextModel.DeclarationRef(retained.source(),9,0));throw new AssertionError("foreign handle"); }
            catch(IllegalArgumentException expected) {}
        }
        Path directory=Path.of(args[0]);Files.createDirectories(directory);Path archive=directory.resolve("model-"+UUID.randomUUID()+".bin");
        ModelArchive.write(archive,raw,16*1024*1024);
        Map<String,Object> config=Json.object("lombok","model","world","units","context","units","resources",List.of(),"sources",List.of(),
                "model_record_limit",1000,"model_archive_bytes",16*1024*1024);
        Map<String,Object> escaped;
        try(var open=ModelArchive.open(archive,Json.sha(archive),16*1024*1024,"units")) {
            List<String> progress=new ArrayList<>();
            var runner=new ExtensionRunner(config,BuiltinExtensions.REGISTRY,progress::add);
            runner.augment(open.model());
            if(!Json.list(runner.report().get("failures")).isEmpty())throw new AssertionError("overlay failure");
            if(!(open.model().get("declarations") instanceof UnitDeclarationRows))throw new AssertionError("not unit-backed");
            List<Object> rows=Json.list(open.model().get("declarations"));
            if(rows.size()!=6)throw new AssertionError("generated count");
            escaped=Json.map(rows.get(0));Json.map(escaped.get("attributes")).put("probe",true);
            if(!Boolean.TRUE.equals(Json.map(Json.map(rows.get(0)).get("attributes")).get("probe")))throw new AssertionError("lost write");
            String before=Json.write(rows);
            runner.augment(open.model());
            if(!before.equals(Json.write(rows)))throw new AssertionError("repeat overlay changed rows");
            if(progress.stream().filter(s->s.contains("unit_validate:")).count()!=2)throw new AssertionError("not file-local");
            var limited=new LinkedHashMap<>(config);limited.put("model_record_limit",5);
            Map<String,Object> original=model();String unchanged=Json.write(original);
            var fail=new ExtensionRunner(limited,BuiltinExtensions.REGISTRY);fail.augment(original);
            if(!unchanged.equals(Json.write(original)))throw new AssertionError("partial commit escaped");
        }
        try { escaped.get("signature");throw new AssertionError("closed lease readable"); }
        catch(IllegalStateException expected) {}
        System.out.println("unit identity, ambiguity, transaction rollback, mutation, idempotence and lease passed");
    }
}
