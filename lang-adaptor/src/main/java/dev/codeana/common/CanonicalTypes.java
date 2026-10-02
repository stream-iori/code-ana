package dev.codeana.common;

import java.util.*;

/** Structural graph partitioning: compiler object sharing is not public type identity. */
final class CanonicalTypes {
    static final Set<String> SINGLE=new HashSet<String>(Arrays.asList("enclosing","component","extends","super","upper","lower","return","receiver"));
    static final Set<String> MANY=new HashSet<String>(Arrays.asList("arguments","bounds","alternatives","parameters","throws","type_variables"));
    private static final class Incoming {
        final int source;final String label;
        Incoming(int source,String label) {this.source=source;this.label=label;}
    }
    private CanonicalTypes() {}

    static Map<String,Object> encode(TypeGraph graph,List<Integer> roots) {
        graph.requireSealed();int n=graph.size();int[] classes=new int[n];
        List<Set<Integer>> blocks=new ArrayList<Set<Integer>>();Map<String,Integer> initial=new TreeMap<String,Integer>();
        List<List<Incoming>> incoming=new ArrayList<List<Incoming>>();for(int i=0;i<n;i++)incoming.add(new ArrayList<Incoming>());
        for(int i=0;i<n;i++) {
            Map<String,Object> shape=new TreeMap<String,Object>();
            for(Map.Entry<String,Object> entry:graph.node(i).entrySet()) {
                String key=entry.getKey();Object value=entry.getValue();
                if(SINGLE.contains(key)) {
                    int child=((Number)value).intValue();shape.put(key,child==-1?-1:0);
                    if(child>=0)incoming.get(child).add(new Incoming(i,key));
                } else if(MANY.contains(key)) {
                    List<Object> children=Json.list(value);shape.put(key,Collections.nCopies(children.size(),0));
                    for(int j=0;j<children.size();j++)incoming.get(((Number)children.get(j)).intValue()).add(new Incoming(i,key+":"+j));
                } else shape.put(key,stable(value));
            }
            String key=Json.write(shape);Integer block=initial.get(key);
            if(block==null) {block=blocks.size();initial.put(key,block);blocks.add(new LinkedHashSet<Integer>());}
            classes[i]=block;blocks.get(block).add(i);
        }
        ArrayDeque<Integer> pending=new ArrayDeque<Integer>();
        for(int i=0;i<blocks.size();i++)pending.add(i);
        while(!pending.isEmpty()) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("CANCELLED");
            int splitter=pending.removeFirst();
            Map<String,Set<Integer>> labels=new HashMap<String,Set<Integer>>();
            for(int child:blocks.get(splitter))for(Incoming edge:incoming.get(child))
                labels.computeIfAbsent(edge.label,ignored->new LinkedHashSet<Integer>()).add(edge.source);
            for(Set<Integer> predecessors:labels.values()) {
                Map<Integer,Set<Integer>> affected=new HashMap<Integer,Set<Integer>>();
                for(int parent:predecessors)affected.computeIfAbsent(classes[parent],ignored->new LinkedHashSet<Integer>()).add(parent);
                for(Map.Entry<Integer,Set<Integer>> entry:affected.entrySet()) {
                    int old=entry.getKey();Set<Integer> inside=entry.getValue(),block=blocks.get(old);
                    if(inside.size()==block.size())continue;
                    Set<Integer> outside=block;outside.removeAll(inside);
                    Set<Integer> small=inside.size()<=outside.size()?inside:outside,large=small==inside?outside:inside;
                    blocks.set(old,large);int fresh=blocks.size();blocks.add(small);
                    for(int node:small)classes[node]=fresh;
                    // The existing queue entry continues to visit the large half; otherwise
                    // only the small half must be visited. No recursion or depth-wide rounds.
                    pending.add(fresh);
                }
            }
        }
        List<Map<String,Object>> reduced=new ArrayList<Map<String,Object>>();
        for(Set<Integer> block:blocks) {
            Map<String,Object> node=new TreeMap<String,Object>();
            for(Map.Entry<String,Object> entry:graph.node(block.iterator().next()).entrySet()) {
                String key=entry.getKey();Object value=entry.getValue();
                if(SINGLE.contains(key)) {int child=((Number)value).intValue();value=child<0?-1:classes[child];}
                else if(MANY.contains(key)) {List<Integer> children=new ArrayList<Integer>();for(Object child:Json.list(value))children.add(classes[((Number)child).intValue()]);value=children;}
                else value=stable(value);
                node.put(key,value);
            }
            reduced.add(node);
        }
        List<Integer> normalized=new ArrayList<Integer>();for(int root:roots)normalized.add(root<0?-1:classes[root]);
        return new TypeGraph(reduced).canonical(normalized);
    }
    private static Object stable(Object value) {
        if(value instanceof Map) {Map<String,Object> copy=new TreeMap<String,Object>();for(Map.Entry<String,Object> entry:Json.map(value).entrySet())copy.put(entry.getKey(),stable(entry.getValue()));return copy;}
        if(value instanceof List) {List<Object> copy=new ArrayList<Object>();for(Object child:Json.list(value))copy.add(stable(child));return copy;}
        return value;
    }
}
