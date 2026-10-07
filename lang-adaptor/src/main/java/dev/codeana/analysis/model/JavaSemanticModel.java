package dev.codeana.analysis.model;

import dev.codeana.common.Json;
import dev.codeana.common.ModelRowLease;
import java.util.*;
import java.util.function.Predicate;

/** File-scoped semantic passes over the leased compiler archive. Adapted from Anatomist's
 * JavaModel/JavaUnit boundaries at dff2e395. Context indexes retain positions; optional
 * bounded row leases accelerate recovery. Original javac evidence remains the backing store.
 */
public final class JavaSemanticModel implements AutoCloseable {
    public record SyntaxNode(Map<String,Object> evidence) {
        public long id() { return Json.number(evidence,"id"); }
        public String kind() { return Json.string(evidence,"kind"); }
    }
    public record Binding(Map<String,Object> evidence) {
        public String kind() { return Json.string(evidence,"kind"); }
        public boolean call() { return kind().equals("call"); }
    }
    public final class Unit {
        private final String source;
        private final Map<String,List<Integer>> positions = new HashMap<>();
        private Unit(String source) { this.source=source; }
        public String source() { check();return source; }
        public List<Object> rows(String section) {
            check();List<Object> backing=Json.list(model.get(section));
            List<Integer> selected=positions.computeIfAbsent(section,s->new ArrayList<>());
            return new AbstractList<>() {
                public int size() { check();return selected.size(); }
                public Object get(int index) { check();return row(section,selected.get(index)); }
                public void add(int index,Object raw) {
                    check();Objects.checkIndex(index,selected.size()+1);
                    if(!source.equals(Json.string(Json.map(raw),"source")))
                        throw new IllegalArgumentException("JAVA_MODEL_UNIT_OWNERSHIP");
                    int position=backing.size();backing.add(raw);selected.add(index,position);modCount++;
                }
            };
        }
        public Iterable<SyntaxNode> syntax() { return ()->rows("syntax").stream().map(Json::map).map(SyntaxNode::new).iterator(); }
        public Iterable<Binding> bindings() { return ()->rows("observations").stream().map(Json::map).map(Binding::new).iterator(); }
        /** Wire maps are confined to the existing rule boundary; all body rows belong to this file. */
        public Map<String,Object> evidence() {
            check();var view=new LinkedHashMap<>(model);
            for(String section:List.of("declarations","syntax","observations"))view.put(section,rows(section));
            return view;
        }
    }
    private final Map<String,Object> model;
    private final Map<String,Unit> units=new LinkedHashMap<>();
    private final Map<String,List<Integer>> members=new LinkedHashMap<>();
    private final RowIndex declarations;
    private final RowIndex types;
    private final RowIndex known;
    private boolean closed;
    private record RowPosition(String section,int row) {}
    private final class RowCache {
        final long limit;
        final Map<RowPosition,ModelRowLease> entries=new LinkedHashMap<>(64,.75f,true);
        long bytes,hits,misses,evictions;
        RowCache(long limit){this.limit=limit;}
        Object get(String section,int position) {
            var key=new RowPosition(section,position);var lease=entries.get(key);
            if(lease!=null){hits++;return lease.row;}
            misses++;
            if(limit==0)return Json.list(model.get(section)).get(position);
            lease=ModelRowLease.borrow(Json.list(model.get(section)),position);
            if(lease.bytes<=limit) {
                while(bytes>limit-lease.bytes && !entries.isEmpty()) {
                    var iterator=entries.entrySet().iterator();var oldest=iterator.next();
                    bytes-=oldest.getValue().bytes;iterator.remove();evictions++;
                }
                entries.put(key,lease);bytes+=lease.bytes;
            }
            return lease.row;
        }
        void clear(){entries.clear();bytes=0;}
    }
    private final RowCache headers,body;
    private Object row(String section,int position) {
        check();return (section.equals("syntax")?body:headers).get(section,position);
    }
    public void beginUnit(){check();body.clear();}
    public List<Map<String,Object>> declarationRows() {
        check();return new AbstractList<>() {
            public int size(){check();return Json.list(model.get("declarations")).size();}
            public Map<String,Object> get(int index){return Json.map(row("declarations",index));}
        };
    }
    public Map<String,Object> cacheMetrics() {
        check();return Json.object("row_cache_hits",headers.hits+body.hits,
                "row_cache_misses",headers.misses+body.misses,
                "row_cache_evictions",headers.evictions+body.evictions,
                "header_cache_bytes",headers.bytes,"body_cache_bytes",body.bytes);
    }

    private final class RowIndex extends AbstractMap<String,Map<String,Object>> {
        private record Position(String section,int row) {}
        private final Map<String,Position> positions=new LinkedHashMap<>();
        void index(String name,String section,int row) { positions.put(name,new Position(section,row)); }
        public Map<String,Object> get(Object name) {
            check();Position position=positions.get(name);
            return position==null?null:Json.map(row(position.section,position.row));
        }
        public boolean containsKey(Object name) {check();return positions.containsKey(name);}
        public Set<Entry<String,Map<String,Object>>> entrySet() {
            check();return new AbstractSet<>() {
                public int size(){check();return positions.size();}
                public Iterator<Entry<String,Map<String,Object>>> iterator() {
                    check();var names=positions.keySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext(){check();return names.hasNext();}
                        public Entry<String,Map<String,Object>> next(){String name=names.next();return new SimpleImmutableEntry<>(name,get(name));}
                    };
                }
            };
        }
    }
    public JavaSemanticModel(Map<String,Object> model) {
        this(model,0);
    }
    public JavaSemanticModel(Map<String,Object> model,long rowCacheBytes) {
        if(rowCacheBytes<0)throw new IllegalArgumentException("JAVA_MODEL_CACHE_BUDGET");
        this.model=model;headers=new RowCache(rowCacheBytes/2);body=new RowCache(rowCacheBytes-rowCacheBytes/2);
        declarations=new RowIndex();types=new RowIndex();known=new RowIndex();
        for(Object raw:Json.list(model.getOrDefault("sources",List.of()))) {
            String path=Json.string(Json.map(raw),"path");
            if(units.putIfAbsent(path,new Unit(path))!=null)throw new IllegalArgumentException("JAVA_MODEL_DUPLICATE_UNIT");
        }
        for(String section:List.of("declarations","syntax","observations")) {
            List<Object> rows=Json.list(model.get(section));
            for(int i=0;i<rows.size();i++) {
                check();var row=Json.map(rows.get(i));String path=Json.string(row,"source");
                units.computeIfAbsent(path,Unit::new).positions.computeIfAbsent(section,s->new ArrayList<>()).add(i);
                if(section.equals("declarations")) {
                    String signature=Json.string(row,"signature");declarations.index(signature,section,i);known.index(signature,section,i);
                    String kind=Json.string(Json.map(row.get("attributes")),"element_kind");
                    if(Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(kind))types.index(signature,section,i);
                    members.computeIfAbsent(Json.string(row,"parent"),s->new ArrayList<>()).add(i);
                }
            }
        }
    }
    public Collection<Unit> units(){check();return Collections.unmodifiableCollection(units.values());}
    public Map<String,Map<String,Object>> declarations(){check();return declarations;}
    public Map<String,Map<String,Object>> sourceTypes(){check();return types;}
    public Map<String,Map<String,Object>> known(){check();return known;}
    public void external(String signature,int row){check();known.index(signature,"external",row);}
    public Map<String,List<Map<String,Object>>> members(Predicate<Map<String,Object>> accepts) {
        check();
        // Filter once while the model is stable. Lookups retain only positions, so
        // mutable archive rows still supply the current evidence through eviction.
        Map<String,List<Integer>> filtered=new LinkedHashMap<>();
        List<Object> declarations=Json.list(model.get("declarations"));
        for(var entry:members.entrySet()) {
            List<Integer> selected=new ArrayList<>();
            for(int position:entry.getValue())
                if(accepts.test(Json.map(declarations.get(position))))selected.add(position);
            filtered.put(entry.getKey(),List.copyOf(selected));
        }
        return new AbstractMap<>() {
            public List<Map<String,Object>> get(Object owner) {
                check();var selected=filtered.get(owner);if(selected==null)return null;
                List<Object> rows=Json.list(model.get("declarations"));
                return new AbstractList<>() {
                    public int size(){check();return selected.size();}
                    public Map<String,Object> get(int index){check();return Json.map(row("declarations",selected.get(index)));}
                };
            }
            public Set<Entry<String,List<Map<String,Object>>>> entrySet() {
                check();return new AbstractSet<>() {
                    public int size(){check();return filtered.size();}
                    public Iterator<Entry<String,List<Map<String,Object>>>> iterator() {
                        check();var owners=filtered.keySet().iterator();
                        return new Iterator<>() {
                            public boolean hasNext(){check();return owners.hasNext();}
                            public Entry<String,List<Map<String,Object>>> next(){String owner=owners.next();return new SimpleImmutableEntry<>(owner,get(owner));}
                        };
                    }
                };
            }
        };
    }
    private void check() {
        if(closed)throw new IllegalStateException("JAVA_MODEL_CLOSED");
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("CANCELLED");
    }
    public void close(){closed=true;headers.clear();body.clear();units.clear();members.clear();declarations.positions.clear();types.positions.clear();known.positions.clear();}
}
