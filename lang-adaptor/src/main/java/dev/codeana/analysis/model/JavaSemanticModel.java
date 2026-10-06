package dev.codeana.analysis.model;

import dev.codeana.common.Json;
import java.util.*;
import java.util.function.Predicate;

/** File-scoped semantic passes over the leased compiler archive. Adapted from Anatomist's
 * JavaModel/JavaUnit boundaries at dff2e395. Context indexes retain positions, never decoded
 * declarations, bindings or syntax trees. Original javac evidence remains the backing store.
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
                public Object get(int index) { check();return backing.get(selected.get(index)); }
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

    private final class RowIndex extends AbstractMap<String,Map<String,Object>> {
        private record Position(String section,int row) {}
        private final Map<String,Position> positions=new LinkedHashMap<>();
        void index(String name,String section,int row) { positions.put(name,new Position(section,row)); }
        public Map<String,Object> get(Object name) {
            check();Position position=positions.get(name);
            return position==null?null:Json.map(Json.list(model.get(position.section)).get(position.row));
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
        this.model=model;declarations=new RowIndex();types=new RowIndex();known=new RowIndex();
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
        check();return new AbstractMap<>() {
            public List<Map<String,Object>> get(Object owner) {
                check();var positions=members.get(owner);if(positions==null)return null;
                List<Integer> selected=new ArrayList<>();
                List<Object> rows=Json.list(model.get("declarations"));
                for(int position:positions)if(accepts.test(Json.map(rows.get(position))))selected.add(position);
                return new AbstractList<>() {
                    public int size(){check();return selected.size();}
                    public Map<String,Object> get(int index){check();return Json.map(rows.get(selected.get(index)));}
                };
            }
            public Set<Entry<String,List<Map<String,Object>>>> entrySet() {
                check();return new AbstractSet<>() {
                    public int size(){check();return members.size();}
                    public Iterator<Entry<String,List<Map<String,Object>>>> iterator() {
                        check();var owners=members.keySet().iterator();
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
    public void close(){closed=true;units.clear();members.clear();declarations.positions.clear();types.positions.clear();known.positions.clear();}
}
