package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.nio.file.Path;
import java.util.*;

/** Auxiliary bindings remain readable, but recovery must never visit their call bodies. */
public final class RecoveryBoundaryProbe {
    public static void main(String[] args) throws Exception {
        var model=JavaUnitModelProbe.model();
        var field=Json.map(Json.list(model.get("declarations")).get(3));
        Json.map(field.get("attributes")).put("binding_origin","lombok_model");
        var auxiliary=Json.object("source","B.java","kind","call","resolved",false,
                "attributes",Json.object("tree_id",1));
        model.put("observations",new ArrayList<>(List.of(auxiliary)));
        String before=Json.write(auxiliary);
        var context=Json.object("classpath",List.of(),"sources",List.of(
                Json.object("path","A.java","publish",true),
                Json.object("path","B.java","publish",false,"auxiliary",true)));
        List<String> phases=new ArrayList<>();List<Map<String,Object>> metrics=new ArrayList<>();
        CallRecovery.recover(model,context,Path.of(System.getProperty("java.home")),phases::add,metrics::add);
        if(!before.equals(Json.write(auxiliary)))throw new AssertionError("auxiliary call was mutated");
        if(phases.contains("call_recovery.unit:B.java") || !phases.contains("call_recovery.unit:A.java"))
            throw new AssertionError("recovery crossed publication boundary");
        var total=metrics.getLast();
        if(Json.number(total,"published_files")!=1 || Json.number(total,"model_files")!=2)
            throw new AssertionError("auxiliary context discarded");
        System.out.println("published recovery boundary and completed metrics passed");
    }
}
