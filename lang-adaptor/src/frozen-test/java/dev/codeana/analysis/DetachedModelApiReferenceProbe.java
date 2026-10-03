package dev.codeana.analysis;

import com.anatomist.core.javac.*;
import com.anatomist.core.java.*;
import dev.codeana.common.Json;
import java.nio.file.*;
import java.util.*;

/** Actual frozen SDK8 parser and model API; never receives product declarations. */
public final class DetachedModelApiReferenceProbe {
    private static Map<String,Object> key(JavaDeclaration value) {
        return Json.object("kind",value.symbol().kind(),"name",value.symbol().name(),
                "start",value.span().start(),"end",value.span().end());
    }
    private static Map<String,Object> syntaxKey(JavaNode node) {
        return Json.object("kind",node.kind(),"start",node.start(),"end",node.end());
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> request=Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String,Object> context=Json.map(Json.list(request.get("contexts")).getFirst());
        Map<Path,String> paths=new LinkedHashMap<>();
        for(Object raw:Json.list(context.get("sources"))) {
            Map<String,Object> unit=Json.map(raw);paths.put(Path.of(Json.string(unit,"input")).toAbsolutePath(),Json.string(unit,"path"));
        }
        Path javaHome=Path.of(Json.string(request,"java_home")),scratch=Path.of(Json.string(request,"scratch"));
        JavacOptions options=new JavacOptions(javaHome,true,true,false,false,scratch.resolve("frozen-model-cache"),1,128);
        JavacToolchain toolchain=JavacToolchain.resolve(options,8,javaHome);
        var backend=new JavacBackend(toolchain,List.of(),List.of(paths.keySet().iterator().next().getParent()),options);
        List<Object> rows=new ArrayList<>(),lookups=new ArrayList<>(),syntaxRows=new ArrayList<>();Map<String,Object> sources=new TreeMap<>();
        backend.analyzeFiles(new ArrayList<>(paths.keySet()),analysis->{
            try(var model=new JavaModel(analysis)) {
                for(var unit:analysis.units()) {
                    String path=paths.get(unit.path());if(path==null)throw new IllegalArgumentException("reference discovered unrequested unit");
                    var source=model.source(unit.path());String text=source.text();List<Object> positions=new ArrayList<>();
                    for(int offset=0;offset<=text.length();offset++) {var p=source.position(offset);positions.add(List.of(p.line(),p.column()));}
                    sources.put(path,Json.object("sha256",Json.sha(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"text",text,"positions",positions));
                    var syntax=model.syntax(unit.path());
                    for(var node:syntax.nodes()) {
                        if(syntax.node(node.id()).isEmpty())throw new AssertionError("reference syntax identity lookup missed node");
                        Map<String,Object> row=syntaxKey(node);row.put("source",path);
                        row.put("parent",syntax.parent(node).map(DetachedModelApiReferenceProbe::syntaxKey).orElse(null));
                        row.put("enclosing",syntax.enclosing(node,Set.of("METHOD","CLASS","INTERFACE","ENUM","ANNOTATION_TYPE"))
                                .map(DetachedModelApiReferenceProbe::syntaxKey).orElse(null));syntaxRows.add(row);
                    }
                    for(var ref:model.declarations(unit.path())) {
                        var d=model.declaration(ref);List<Object> members=new ArrayList<>();
                        for(var member:model.members(ref))members.add(key(model.declaration(member)));
                        Object parent=model.parent(ref).map(p->key(model.declaration(p))).orElse(null);
                        Object node=model.syntax(ref).map(JavaNode::kind).orElse(null);
                        if(d.span().present()&&!model.declarationsAt(unit.path(),d.span()).contains(ref))throw new AssertionError("reference span catalogue missed declaration");
                        Map<String,Object> row=key(d);row.put("source",path);row.put("parent",parent);row.put("members",members);row.put("syntax",node);
                        row.put("slice",d.span().present()?source.slice(d.span()):null);rows.add(row);
                    }
                }
                for(Object name:Json.list(request.getOrDefault("model_type_queries",List.of()))) {
                    var lookup=model.type(name.toString());List<Object> candidates=new ArrayList<>();
                    if(lookup instanceof JavaModel.FoundType found)candidates.add(key(model.declaration(found.declaration())));
                    if(lookup instanceof JavaModel.AmbiguousType ambiguous)for(var ref:ambiguous.declarations())candidates.add(key(model.declaration(ref)));
                    lookups.add(Json.object("name",name,"status",lookup instanceof JavaModel.MissingType?"missing":candidates.size()==1?"found":"ambiguous","candidates",candidates));
                }
                model.close();try {model.type("p.Use");throw new AssertionError("reference closed model remained readable");}
                catch(IllegalStateException expected){}
            } catch(Exception failure) {throw new RuntimeException(failure);}
        });
        System.out.println(Json.write(Json.object("rows",rows,"syntax",syntaxRows,"sources",sources,"types",lookups,"closed",true)));
    }
}
