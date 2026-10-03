package dev.codeana.analysis;

import dev.codeana.analysis.extension.ExtensionContext;
import dev.codeana.analysis.extension.DetachedJavaModel;
import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import java.nio.file.Path;
import java.util.*;

/** Exercise navigation over an actual target-JDK compiler archive. */
public final class DetachedModelApiProbe {
    private static Map<String,Object> key(Map<String,Object> value) {
        return Json.object("kind",Json.map(value.get("attributes")).get("element_kind"),
                "name",value.get("name"),"start",value.get("start"),"end",value.get("end"));
    }
    private static Map<String,Object> syntaxKey(Map<String,Object> node) {
        return Json.object("kind",node.get("kind"),"start",node.get("start"),"end",node.get("end"));
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> request=Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String,Object> context=Json.map(Json.list(request.get("contexts")).getFirst());
        boolean inventory=Boolean.TRUE.equals(request.get("model_api_inventory"));
        Set<String> selected=new HashSet<>();
        for(Object raw:Json.list(context.get("sources"))) {
            var source=Json.map(raw);
            if(!Boolean.TRUE.equals(request.get("model_api_published_only")) || Boolean.TRUE.equals(source.get("publish")))
                selected.add(Json.string(source,"path"));
        }
        try(CompilerSession compiler=new CompilerSession(request)) {
            ModelArchive.OpenModel captured=request.containsKey("model_api_archive")
                ? ModelArchive.open(Path.of(Json.string(request,"model_api_archive")),
                    Json.string(request,"model_api_archive_sha256"),ModelArchive.byteLimit(context),Json.string(context,"context"))
                : null;
            Map<String,Object> archive=captured==null ? compiler.analyze(context) : captured.model();
            try {
                DetachedJavaModel model=new ExtensionContext(context,archive,List.of(),List.of()).javaModel();
                List<Object> rows=new ArrayList<>(),syntaxRows=new ArrayList<>();Map<String,Object> sources=new TreeMap<>();
                for(String path:model.unitsInContext()) {
                    if(!selected.contains(path))continue;
                    DetachedJavaModel.Source source=model.source(path);String text=source.text();
                    List<Object> positions=new ArrayList<>();
                    for(int offset=0;!inventory&&offset<=text.length();offset++) {
                        var p=source.position(offset);positions.add(List.of(p.line(),p.column()));
                    }
                    sources.put(path,inventory ? ModelApiInventory.source(source.bytes(),text,offset->{
                        var p=source.position(offset);return new int[]{p.line(),p.column()};
                    }) : Json.object("sha256",Json.sha(source.bytes()),"text",text,"positions",positions));
                    byte[] copy=source.bytes();if(copy.length>0)copy[0]^=1;
                    if(!Json.sha(source.bytes()).equals(Json.string(model.unit(path),"hash")))throw new AssertionError("mutable source escape");
                    var syntax=model.syntax(path);
                    for(var node:syntax.nodes()) {
                        int id=Math.toIntExact(Json.number(node,"id"));
                        if(syntax.node(id).isEmpty())throw new AssertionError("syntax identity lookup missed node");
                        Map<String,Object> row=syntaxKey(node);row.put("source",path);
                        row.put("parent",syntax.parent(node).map(DetachedModelApiProbe::syntaxKey).orElse(null));
                        row.put("enclosing",syntax.enclosing(node,Set.of("METHOD","CLASS","INTERFACE","ENUM","ANNOTATION_TYPE"))
                                .map(DetachedModelApiProbe::syntaxKey).orElse(null));syntaxRows.add(row);
                    }
                    for(var ref:model.declarations(path)) {
                        Map<String,Object> d=model.declaration(ref);long start=Json.number(d,"start"),end=Json.number(d,"end");
                        List<Object> members=new ArrayList<>();for(var member:model.members(ref))members.add(key(model.declaration(member)));
                        Object parent=model.parent(ref).map(p->key(model.declaration(p))).orElse(null);
                        Object node=model.syntax(ref).map(n->n.get("kind")).orElse(null);
                        if(start>=0&&end>=start&&!model.declarationsAt(path,start,end).contains(ref))throw new AssertionError("span catalogue missed declaration");
                        Map<String,Object> row=key(d);row.put("source",path);row.put("parent",parent);row.put("members",members);row.put("syntax",node);
                        String slice=start>=0&&end>=start?source.slice(start,end):null;
                        row.put("slice",inventory ? ModelApiInventory.slice(slice) : slice);rows.add(row);
                        try {d.put("name","changed");throw new AssertionError("mutable declaration escape");}
                        catch(UnsupportedOperationException expected){}
                    }
                }
                List<Object> lookups=new ArrayList<>();
                for(Object name:Json.list(request.getOrDefault("model_type_queries",List.of()))) {
                    var lookup=model.type(name.toString());List<Object> candidates=new ArrayList<>();
                    if(lookup instanceof DetachedJavaModel.FoundType found)candidates.add(key(model.declaration(found.declaration())));
                    if(lookup instanceof DetachedJavaModel.AmbiguousType ambiguous)for(var ref:ambiguous.declarations())candidates.add(key(model.declaration(ref)));
                    lookups.add(Json.object("name",name,"status",lookup instanceof DetachedJavaModel.MissingType?"missing":candidates.size()==1?"found":"ambiguous","candidates",candidates));
                }
                model.close();try {model.type("p.Use");throw new AssertionError("closed model remained readable");}
                catch(IllegalStateException expected){}
                System.out.println(Json.write(Json.object("rows",rows,"syntax",syntaxRows,"sources",sources,"types",lookups,"closed",true)));
            } finally {if(captured==null)compiler.releaseModel(archive);else captured.close();}
        }
    }
}
