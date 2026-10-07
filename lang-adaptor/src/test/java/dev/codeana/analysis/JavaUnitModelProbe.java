package dev.codeana.analysis;

import dev.codeana.analysis.extension.*;
import dev.codeana.analysis.model.JavaContextModel;
import dev.codeana.analysis.model.JavaSemanticModel;
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
        var bodies=new LinkedHashMap<>(raw);
        bodies.put("syntax",List.of(Json.object("source","A.java","id",1),
                Json.object("source","B.java","id",2),Json.object("source","A.java","id",3)));
        List<Object> retainedBody;
        try(var session=new JavaSemanticModel(bodies,64L<<20)) {
            var published=session.readView(Set.of("A.java"));
            retainedBody=Json.list(published.get("syntax"));
            if(retainedBody.size()!=2 || Json.number(Json.map(retainedBody.get(0)),"id")!=1
                    || Json.number(Json.map(retainedBody.get(1)),"id")!=3)
                throw new AssertionError("published body changed archive order");
            if(session.declarations().get("B#value")==null
                    || Json.list(published.get("declarations")).size()!=4)
                throw new AssertionError("auxiliary headers lost");
            if(!Json.list(session.readView(Set.of()).get("syntax")).isEmpty())
                throw new AssertionError("empty publication retained bodies");
            if(Json.list(bodies.get("syntax")).size()!=3)
                throw new AssertionError("publication view changed original model");
        }
        try {retainedBody.size();throw new AssertionError("body escaped read session");}
        catch(IllegalStateException expected) {}
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
        var scoped=new LinkedHashMap<>(raw);
        scoped.put("observations",new ArrayList<>(List.of(
                Json.object("source","A.java","kind","call","target","B#value","attributes",Map.of()),
                Json.object("source","B.java","kind","call","target","A#value","attributes",Map.of()))));
        scoped.put("syntax",List.of(Json.object("source","A.java","id",1,"kind","IDENTIFIER"),
                Json.object("source","B.java","id",1,"kind","IDENTIFIER")));
        JavaSemanticModel.Unit escapedUnit;
        try(var semantic=new JavaSemanticModel(scoped)) {
            var iterator=semantic.units().iterator();escapedUnit=iterator.next();var other=iterator.next();
            if(escapedUnit.rows("syntax").size()!=1 || other.rows("observations").size()!=1)
                throw new AssertionError("body escaped file boundary");
            if(!"B#value".equals(Json.map(escapedUnit.rows("observations").get(0)).get("target")))
                throw new AssertionError("cross-file binding changed");
            if(semantic.sourceTypes().get("B")==null || semantic.members(d->true).get("B").size()!=1)
                throw new AssertionError("context lookup lost dependency");
            int[] scans={0};
            var indexedMembers=semantic.members(d->{scans[0]++;return true;});
            int indexed=scans[0];
            for(int i=0;i<200;i++) {
                if(indexedMembers.get("B").size()!=1)throw new AssertionError("indexed members lost dependency");
                indexedMembers.get("B").get(0).get("signature");
            }
            if(scans[0]!=indexed)throw new AssertionError("member lookup rescanned declarations");
            Json.map(semantic.declarations().get("A#value").get("attributes")).put("shared_probe",true);
            if(!Boolean.TRUE.equals(Json.map(Json.map(escapedUnit.rows("declarations").get(1)).get("attributes")).get("shared_probe")))
                throw new AssertionError("lost shared declaration write");
            escapedUnit.rows("observations").add(Json.object("source","A.java","kind","reference"));
            if(Json.list(scoped.get("observations")).size()!=3 || other.rows("observations").size()!=1)
                throw new AssertionError("file append was not routed to context");
            try { escapedUnit.rows("observations").add(Json.object("source","B.java","kind","reference"));
                throw new AssertionError("foreign file write accepted"); } catch(IllegalArgumentException expected) {}
        }
        try { escapedUnit.rows("syntax").size();throw new AssertionError("closed semantic handle readable"); }
        catch(IllegalStateException expected) {}
        // Each read allocates a large detached payload. Retaining decoded declarations would
        // exceed this probe's 64 MiB heap; positional indexes must not own those payloads.
        var lazy=new LinkedHashMap<>(raw);
        lazy.put("declarations",new AbstractList<Object>() {
            public int size(){return 1200;}
            public Object get(int index){
                Objects.checkIndex(index,size());
                var declaration=declaration("L"+index+".java","L"+index,"","CLASS",false);
                Json.map(declaration.get("attributes")).put("detached_payload","x".repeat(131072));
                return declaration;
            }
        });
        try(var semantic=new JavaSemanticModel(lazy)) {
            if(semantic.units().size()!=1200 || semantic.sourceTypes().size()!=1200)
                throw new AssertionError("lazy context lost identities");
            if(!"L1199".equals(semantic.declarations().get("L1199").get("signature")))
                throw new AssertionError("lazy lookup incorrect");
        }
        Path directory=Path.of(args[0]);Files.createDirectories(directory);Path archive=directory.resolve("model-"+UUID.randomUUID()+".bin");
        raw.put("syntax",List.of(Json.object("source","A.java","id",1,"kind","IDENTIFIER"),
                Json.object("source","B.java","id",1,"kind","IDENTIFIER")));
        ModelArchive.write(archive,raw,16*1024*1024);
        try(var open=ModelArchive.open(archive,Json.sha(archive),16*1024*1024,"units");
                var context=new JavaContextModel(open.model(),1024*1024);
                var staged=new ModelArchive.RowSpool(directory,"context-unit",16*1024*1024)) {
            var first=context.unique("A#value");
            var snapshot=Json.list(ExtensionContext.freeze(open.model().get("declarations")));
            if(first!=context.unique("A#value"))throw new AssertionError("context lease not reused");
            Json.map(first.get("attributes")).put("context_write",true);
            if(!Boolean.TRUE.equals(Json.map(context.unique("A#value").get("attributes")).get("context_write"))
                    ||Json.map(Json.map(snapshot.get(1)).get("attributes")).containsKey("context_write"))
                throw new AssertionError("context lease lost mutation or changed snapshot");
            var old=((JavaContextModel.FoundType)context.lookup("A")).declaration();
            staged.add(declaration("A.java","A","","CLASS",false));
            staged.add(declaration("A.java","A#fresh","A","FIELD",false));
            context.replaceUnit("A.java",new UnitDeclarationRows(List.of(),staged,new int[]{-1,-2}));
            if(context.unique("A#value")!=null || context.unique("A#fresh")==null
                    ||context.unique("A#fresh")!=context.unique("A#fresh"))
                throw new AssertionError("replacement reused stale lease or bypassed spool lease");
            try {context.declaration(old);throw new AssertionError("old unit handle accepted");}
            catch(IllegalArgumentException expected) {}
        }
        try(var open=ModelArchive.open(archive,Json.sha(archive),16*1024*1024,"units");
                var cached=new JavaSemanticModel(open.model(),1024*1024)) {
            var first=cached.declarations().get("A#value");
            var frozen=Json.list(ExtensionContext.freeze(open.model().get("declarations")));
            for(int i=0;i<200;i++)cached.declarations().get("A#value").get("signature");
            if(Json.number(cached.cacheMetrics(),"row_cache_hits")<200)
                throw new AssertionError("archive lease was not reused");
            Json.map(first.get("attributes")).put("cached_write",true);
            if(!Boolean.TRUE.equals(Json.map(Json.map(Json.list(open.model().get("declarations")).get(1)).get("attributes")).get("cached_write")))
                throw new AssertionError("cache copied mutable state");
            if(Json.map(Json.map(frozen.get(1)).get("attributes")).containsKey("cached_write"))
                throw new AssertionError("cache mutation leaked into snapshot");
            if(Json.number(cached.cacheMetrics(),"header_cache_bytes")>512*1024)
                throw new AssertionError("cache exceeded byte budget");
            var firstUnit=cached.units().iterator().next();
            var firstNode=firstUnit.rows("syntax").get(0);
            if(firstNode!=firstUnit.rows("syntax").get(0)
                    ||Json.number(cached.cacheMetrics(),"body_cache_bytes")==0)
                throw new AssertionError("immutable syntax was repeatedly decoded");
            cached.beginUnit();
            if(Json.number(cached.cacheMetrics(),"body_cache_bytes")!=0)
                throw new AssertionError("syntax lease escaped file lifetime");
        }
        try(var open=ModelArchive.open(archive,Json.sha(archive),16*1024*1024,"units");
                var tiny=new JavaSemanticModel(open.model(),2)) {
            for(int i=0;i<3;i++)tiny.declarations().get("A");
            if(Json.number(tiny.cacheMetrics(),"row_cache_hits")!=0
                    ||Json.number(tiny.cacheMetrics(),"header_cache_bytes")!=0)
                throw new AssertionError("oversized row entered cache");
        }
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
