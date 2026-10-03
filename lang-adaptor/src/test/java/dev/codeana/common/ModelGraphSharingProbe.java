package dev.codeana.common;

import java.util.*;

/** Multiple compiler models coexist without retaining packed and decoded graph copies. */
public final class ModelGraphSharingProbe {
    private static ModelGraphs model() throws Exception {
        ModelGraphs graphs=new ModelGraphs();
        for(int graph=0;graph<240;graph++) {
            for(int node=0;node<100;node++) {
                Map<String,Object> value=Json.object(
                    "kind","DECLARED","complete",true,"enclosing",-1,
                    "arguments",Arrays.asList((node+1)%100),
                    "element","p.Box","element_kind","CLASS","display","p.Box<T>",
                    "name","Box","descriptor","Lp/Box;","signature","Lp/Box<TT;>;",
                    "version",3,"annotations",Collections.emptyList());
                graphs.add(Json.object("graph",graph,"id",node,"value",value));
            }
        }
        graphs.seal();
        return graphs;
    }
    public static void main(String[] args) throws Exception {
        ModelGraphs original=model(),supplemental=model();
        List<Integer> counts=original.counts();
        if(counts.size()!=240 || counts.stream().anyMatch(count->count!=100))
            throw new AssertionError("dictionary counts changed");
        for(int index=0;index<240;index++) {
            Map<String,Object> type=original.view(Arrays.asList(index,0));
            if(!"p.Box".equals(type.get("element")))throw new AssertionError("root changed");
            TypeGraph graph=TypeGraph.owner(type);
            if(!graph.canonical(Arrays.asList(0)).equals(
                    TypeGraph.owner(supplemental.view(Arrays.asList(index,0))).canonical(Arrays.asList(0))))
                throw new AssertionError("cyclic graph drift");
            try {graph.node(0).put("element","mutated");throw new AssertionError("mutable node");}
            catch(UnsupportedOperationException expected) {}
        }
        original.seal();
        if(!counts.equals(original.counts()))throw new AssertionError("repeat seal drift");
        original.close();supplemental.close();
        System.out.println("two 24000-node dictionaries, cyclic roots and immutable metadata passed");
    }
}
