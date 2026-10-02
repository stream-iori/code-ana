package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.RecordArchive;
import java.nio.file.Path;
import java.util.*;

/** Verify partial publication after a single declaration exhausts type traversal. */
public final class SourceTypeBudgetProbe {
    public static void main(String[] args) throws Exception {
        Map<String,Object> request=Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String,Object> context=Json.map(Json.list(request.get("contexts")).getFirst());
        String source=Json.string(Json.map(Json.list(context.get("sources")).getFirst()),"path");
        Map<String,Object> leaf=Json.object("kind","DECLARED","element","Leaf","complete",true);
        Map<String,Object> deep=leaf;
        for(int depth=0;depth<129;depth++)deep=Json.object("kind","ARRAY","component",deep,"complete",true);
        List<Object> declarations=List.of(
                declaration(source,"Leaf","", "CLASS",leaf),
                declaration(source,"Leaf#healthy","Leaf","FIELD",leaf),
                declaration(source,"Leaf#limited","Leaf","FIELD",deep));
        Map<String,Object> model=Json.object("syntax",List.of(),"declarations",declarations,
                "external",List.of(),"observations",List.of(),"diagnostics",List.of());
        Path path=Path.of(Json.string(request,"scratch")).resolve("budget-facts.bin");
        try(RecordArchive archive=RecordArchive.create(path,16*1024*1024,1000)) {
            List<Object> facts=new FactExtractor(context,Json.string(context,"digest"),archive).extract(model);
            System.out.println(Json.write(Json.object("facts",facts)));
        }
    }

    private static Map<String,Object> declaration(String source,String signature,String parent,
            String kind,Map<String,Object> type) {
        return Json.object("source",source,"signature",signature,"name",signature,"parent",parent,
                "start",0,"end",5,"generated",false,
                "attributes",Json.object("element_kind",kind,"type_model",type));
    }
}
