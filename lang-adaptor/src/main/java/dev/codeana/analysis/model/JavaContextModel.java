package dev.codeana.analysis.model;

import dev.codeana.common.Json;
import java.util.*;

/** Context-owned declaration handles. Indexes never retain decoded declaration/type objects.
 * Adapted from Anatomist JavaModel/JavaUnit at dff2e395: unit boundaries, explicit lookup
 * ambiguity, and context-only references. The backing rows remain code-ana archives.
 */
public final class JavaContextModel implements AutoCloseable {
    public record SourceIdentity(String path, String hash) {}
    public record DeclarationRef(SourceIdentity source, int ordinal, int row) {}
    public sealed interface TypeLookup permits FoundType, AmbiguousType, MissingType {}
    public record FoundType(DeclarationRef declaration) implements TypeLookup {}
    public record AmbiguousType(List<DeclarationRef> declarations) implements TypeLookup {
        public AmbiguousType { declarations = List.copyOf(declarations); }
    }
    public record MissingType(String name) implements TypeLookup {}
    public record Declaration(DeclarationRef reference, Map<String,Object> header) {
        public String signature() { return Json.string(header, "signature"); }
        public String kind() { return Json.string(Json.map(header.get("attributes")), "element_kind"); }
    }
    private record Owner(String source, String signature) {}
    public final class Unit {
        private final SourceIdentity identity;
        private final List<DeclarationRef> declarations = new ArrayList<>();
        private Unit(SourceIdentity identity) { this.identity = identity; }
        public SourceIdentity identity() { check(); return identity; }
        public List<DeclarationRef> declarations() { check(); return Collections.unmodifiableList(declarations); }
        public List<Object> rows() {
            check();
            return new AbstractList<>() {
                public int size() { check(); return declarations.size(); }
                public Object get(int index) { return declaration(declarations.get(index)).header(); }
            };
        }
    }
    private final List<Object> rows;
    private final Map<String,Unit> units = new LinkedHashMap<>();
    private final Map<String,List<DeclarationRef>> signatures = new HashMap<>();
    private final Map<Owner,List<DeclarationRef>> members = new HashMap<>();
    private final Set<String> initialized = new HashSet<>();
    private final Map<String,List<Object>> replacements = new HashMap<>();
    private boolean closed;
    public JavaContextModel(Map<String,Object> model) {
        rows = Json.list(model.get("declarations"));
        for (Object raw : Json.list(model.getOrDefault("sources", List.of()))) {
            Map<String,Object> source = Json.map(raw);
            String path = Json.string(source,"path");
            if (units.putIfAbsent(path,new Unit(new SourceIdentity(path,Json.string(source,"hash")))) != null)
                throw new IllegalArgumentException("JAVA_MODEL_DUPLICATE_UNIT");
        }
        for (int i=0;i<rows.size();i++) {
            check();
            Map<String,Object> header=Json.map(rows.get(i));
            String path=Json.string(header,"source"), signature=Json.string(header,"signature");
            Unit unit=units.computeIfAbsent(path,p->new Unit(new SourceIdentity(p,"")));
            DeclarationRef ref=new DeclarationRef(unit.identity,unit.declarations.size(),i);
            unit.declarations.add(ref);
            signatures.computeIfAbsent(signature,s->new ArrayList<>()).add(ref);
            members.computeIfAbsent(new Owner(path,Json.string(header,"parent")),s->new ArrayList<>()).add(ref);
            if (Boolean.TRUE.equals(Json.map(header.get("attributes")).get("has_initializer"))) initialized.add(signature);
        }
    }
    private void check() {
        if (closed) throw new IllegalStateException("JAVA_MODEL_CLOSED");
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
    }
    public Collection<Unit> units() { check(); return Collections.unmodifiableCollection(units.values()); }
    public Unit unit(String path) {
        check(); Unit unit=units.get(path);
        if(unit==null)throw new IllegalArgumentException("JAVA_MODEL_UNIT_OUTSIDE_CONTEXT: "+path);
        return unit;
    }
    public Declaration declaration(DeclarationRef ref) {
        Unit unit=unit(ref.source.path);
        if(ref.ordinal<0 || ref.ordinal>=unit.declarations.size() || unit.declarations.get(ref.ordinal)!=ref)
            throw new IllegalArgumentException("JAVA_MODEL_DECLARATION_OUTSIDE_CONTEXT");
        List<Object> changed=replacements.get(ref.source.path);
        return new Declaration(ref,Json.map(changed==null?rows.get(ref.row):changed.get(ref.ordinal)));
    }
    public TypeLookup lookup(String signature) {
        check(); var candidates=signatures.getOrDefault(signature,List.of());
        return candidates.isEmpty()?new MissingType(signature):candidates.size()==1
                ?new FoundType(candidates.getFirst()):new AmbiguousType(candidates);
    }
    public Map<String,Object> unique(String signature) {
        TypeLookup found=lookup(signature);
        return found instanceof FoundType type ? declaration(type.declaration).header : null;
    }
    public List<Map<String,Object>> members(String signature) {
        TypeLookup found=lookup(signature);
        if (!(found instanceof FoundType type)) return List.of();
        var refs=members.getOrDefault(new Owner(type.declaration.source.path,signature),List.of());
        return new AbstractList<>() {
            public int size() { check(); return refs.size(); }
            public Map<String,Object> get(int index) { return declaration(refs.get(index)).header; }
        };
    }
    public boolean initialized(String signature) { check(); return initialized.contains(signature); }
    /** Only this private candidate context changes; published/compiler rows stay untouched. */
    public void replaceUnit(String path,List<Object> replacement) {
        Unit unit=unit(path);
        for(DeclarationRef ref:unit.declarations) {
            String signature=declaration(ref).signature();
            var entries=signatures.get(signature);entries.remove(ref);
            if(entries.isEmpty())signatures.remove(signature);
        }
        members.keySet().removeIf(owner->owner.source.equals(path));
        unit.declarations.clear();
        replacements.put(path,replacement);
        for(int i=0;i<replacement.size();i++) {
            var header=Json.map(replacement.get(i));
            if(!path.equals(Json.string(header,"source"))) throw new IllegalArgumentException("EXTENSION_MODEL_UNIT_OWNERSHIP");
            DeclarationRef ref=new DeclarationRef(unit.identity,i,-1);
            unit.declarations.add(ref);
            signatures.computeIfAbsent(Json.string(header,"signature"),s->new ArrayList<>()).add(ref);
            members.computeIfAbsent(new Owner(path,Json.string(header,"parent")),s->new ArrayList<>()).add(ref);
        }
    }
    public int declarationCount() { check(); return rows.size(); }
    public void close() { closed=true;units.clear();signatures.clear();members.clear();initialized.clear();replacements.clear(); }
}
