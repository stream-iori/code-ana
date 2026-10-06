package dev.codeana.analysis.extension;

import dev.codeana.analysis.LombokModel;
import dev.codeana.analysis.model.JavaContextModel;
import dev.codeana.common.*;
import java.util.*;

/** Anatomist's per-unit overlay/validation boundary over code-ana's leased row archives. */
final class LombokAstUnits {
    private LombokAstUnits() {}
    static void augment(ExtensionContext context,Map<String,Object> output) throws Exception {
        Set<String> pending=LombokModel.pendingSources(context.model());
        if(pending.isEmpty())return;
        List<Object> original=Json.list(context.model().get("declarations"));
        List<Object> staged=new ArrayList<>();
        ModelArchive.SyntaxRows lease=context.model().get("syntax") instanceof ModelArchive.SyntaxRows rows?rows:null;
        ModelArchive.RowSpool spool=lease==null?null:new ModelArchive.RowSpool(lease.path().getParent(),
                "lombok-unit",ModelArchive.byteLimit(context.configuration()));
        if(spool!=null)staged=spool;
        Map<String,int[]> ranges=new LinkedHashMap<>();
        boolean accepted=false;
        try(JavaContextModel environment=new JavaContextModel(context.model())) {
            context.progress("unit_inventory");
            for(JavaContextModel.Unit unit:environment.units()) {
                String path=unit.identity().path();
                if(!pending.contains(path))continue;
                context.progress("unit:"+path);
                List<Object> base=new ArrayList<>(), candidate=new ArrayList<>();
                Map<String,Map<String,Object>> known=new HashMap<>();
                for(Object raw:unit.rows()) {
                    Map<String,Object> row=editable(Json.map(raw));
                    base.add(row);candidate.add(editable(row));known.put(Json.string(row,"signature"),row);
                }
                Map<String,Object> working=new LinkedHashMap<>(context.model());
                working.put("declarations",candidate);
                LombokModel.augmentUnit(working,context.configuration(),environment,context::progress);
                ModelOverlay overlay=new ModelOverlay();Set<String> retained=new HashSet<>();
                for(Object raw:candidate) {
                    Map<String,Object> row=Json.map(raw),attributes=Json.map(row.get("attributes"));
                    String signature=Json.string(row,"signature");retained.add(signature);
                    if("lombok_model".equals(attributes.get("binding_origin"))) {
                        if(known.containsKey(signature))overlay.removeCompilerConstructor(signature);
                        overlay.add(row);
                    } else if(attributes.containsKey("lombok_summary")) {
                        overlay.describe(signature,Json.object("summary",attributes.get("lombok_summary"),
                                "effective_modifiers",attributes.get("lombok_effective_modifiers")));
                    }
                }
                for(String signature:known.keySet())if(!retained.contains(signature))overlay.removeCompilerConstructor(signature);
                working.put("declarations",base);
                context.progress("unit_validate:"+path);
                overlay.commit(working,"lombok-model-v1",context.configuration().containsKey("model_record_limit")
                        ?Json.number(context.configuration(),"model_record_limit"):100000);
                int start=staged.size();
                for(Object row:base)staged.add(row);
                int length=staged.size()-start;
                ranges.put(path,new int[]{start,length});
                final List<Object> sealedRows=staged;
                environment.replaceUnit(path,new AbstractList<>() {
                    public int size(){return length;}
                    public Object get(int index){Objects.checkIndex(index,length);return sealedRows.get(start+index);}
                });
            }
            long limit=context.configuration().containsKey("model_record_limit")?Json.number(context.configuration(),"model_record_limit"):100000;
            List<Integer> selected=new ArrayList<>();Set<String> inserted=new HashSet<>();
            for(int i=0;i<original.size();i++) {
                String source=Json.string(Json.map(original.get(i)),"source");
                int[] range=ranges.get(source);
                if(range==null)selected.add(i);
                else if(inserted.add(source))for(int n=0;n<range[1];n++)selected.add(-range[0]-n-1);
            }
            if(selected.size()>limit)throw new IllegalArgumentException("EXTENSION_MODEL_BUDGET");
            if(spool!=null) {
                staged=spool.mutableRows();
                lease.own(spool);
            }
            output.put("declarations",new UnitDeclarationRows(Json.list(output.get("declarations")),staged,
                    selected.stream().mapToInt(Integer::intValue).toArray()));
            accepted=true;context.progress("units_committed");
        } finally {
            if(!accepted&&spool!=null)spool.close();
        }
    }
    private static Map<String,Object> editable(Map<String,Object> row) {
        Map<String,Object> copy=new LinkedHashMap<>(row);
        copy.put("attributes",new LinkedHashMap<>(Json.map(row.get("attributes"))));return copy;
    }
}
