package dev.codeana.analysis.extension;

import dev.codeana.common.Json;
import dev.codeana.common.TypeGraph;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Immutable, context-owned navigation over existing javac/overlay records. No compiler objects. */
public final class DetachedJavaModel implements AutoCloseable {
    public record DeclarationRef(String source, int ordinal) {}
    public sealed interface TypeLookup permits FoundType, AmbiguousType, MissingType {}
    public record FoundType(DeclarationRef declaration) implements TypeLookup {}
    public record AmbiguousType(List<DeclarationRef> declarations) implements TypeLookup {
        public AmbiguousType { declarations=List.copyOf(declarations); }
    }
    public record MissingType(String name) implements TypeLookup {}
    public record Position(int line,int column) {}
    private record Span(String source,long start,long end) {}
    private record Identity(String source,String signature) {}
    private final Map<String,Object> model;
    private final boolean fullAuxiliaryModel;
    private Map<String,Map<String,Object>> units;
    private Map<String,List<Integer>> positions;
    private Map<Identity,List<DeclarationRef>> identities;
    private Map<String,List<DeclarationRef>> types;
    private Map<DeclarationRef,DeclarationRef> parents;
    private Map<DeclarationRef,List<DeclarationRef>> children;
    private Map<Span,List<DeclarationRef>> spans;
    private final List<Map<String,Object>> formalDeclarations=new ArrayList<>();
    private boolean modeled,closed;
    private Map<Span,List<Integer>> syntax;
    private final Map<String,Syntax> syntaxViews=new LinkedHashMap<>(8,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Syntax> entry) {return size()>4;}
    };
    private final Map<String,Source> sources=new LinkedHashMap<>(8,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Source> entry) {return size()>4;}
    };

    DetachedJavaModel(Map<String,Object> immutableModel) { this(immutableModel,false); }
    DetachedJavaModel(Map<String,Object> immutableModel,boolean fullAuxiliaryModel) {
        model=immutableModel;this.fullAuxiliaryModel=fullAuxiliaryModel;
    }
    private void open() {if(closed)throw new IllegalStateException("JAVA_MODEL_CLOSED");cancelled();}
    private static void cancelled() {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("CANCELLED");
    }
    private void units() {
        open();if(units!=null)return;
        Map<String,Map<String,Object>> found=new LinkedHashMap<>();
        for(Object raw:Json.list(model.get("sources"))) {
            cancelled();Map<String,Object> source=Json.map(raw);String path=Json.string(source,"path");
            if(path.isEmpty()||found.putIfAbsent(path,source)!=null)throw new IllegalArgumentException("JAVA_MODEL_DUPLICATE_UNIT");
        }
        units=found;
    }
    public Set<String> unitsInContext() {units();return Collections.unmodifiableSet(units.keySet());}
    public Map<String,Object> unit(String path) {
        units();Map<String,Object> unit=units.get(path);
        if(unit==null)throw new IllegalArgumentException("JAVA_MODEL_UNIT_OUTSIDE_CONTEXT: "+path);
        return unit;
    }
    private void catalogue() {
        units();if(positions!=null)return;
        formalDeclarations.clear();
        Map<String,List<Integer>> rows=new LinkedHashMap<>();
        Map<Identity,List<DeclarationRef>> byIdentity=new HashMap<>();
        Map<String,List<DeclarationRef>> byType=new HashMap<>();
        Map<Span,List<DeclarationRef>> bySpan=new HashMap<>();
        List<Object> declarations=Json.list(model.get("declarations"));
        List<Integer> catalogueRows=new ArrayList<>();
        Set<Identity> callableHeaders=new HashSet<>();
        Set<Identity> auxiliaryBodyDeclarations=new HashSet<>();
        Map<Identity,List<Identity>> auxiliaryChildren=new HashMap<>();
        Set<List<String>> parameterHeaders=new HashSet<>();
        for(Object raw:declarations) {
            cancelled();var value=Json.map(raw);var attributes=Json.map(value.get("attributes"));
            String source=Json.string(value,"source"),signature=Json.string(value,"signature");
            if(!fullAuxiliaryModel && Boolean.FALSE.equals(unit(source).get("publish"))) {
                Identity identity=new Identity(source,signature);
                auxiliaryChildren.computeIfAbsent(new Identity(source,Json.string(value,"parent")),ignored->new ArrayList<>()).add(identity);
                if(Set.of("LOCAL","ANONYMOUS").contains(Objects.toString(attributes.get("nesting_kind"),"")))
                    auxiliaryBodyDeclarations.add(identity);
            }
            if(Set.of("METHOD","CONSTRUCTOR").contains(Json.string(attributes,"element_kind")))
                callableHeaders.add(new Identity(Json.string(value,"source"),Json.string(value,"signature")));
            if("PARAMETER".equals(attributes.get("element_kind")))
                parameterHeaders.add(List.of(source,Json.string(value,"parent"),Json.string(value,"name")));
        }
        Deque<Identity> pending=new ArrayDeque<>(auxiliaryBodyDeclarations);
        while(!pending.isEmpty()) {
            cancelled();Identity parent=pending.removeFirst();
            for(Identity child:auxiliaryChildren.getOrDefault(parent,List.of()))
                if(auxiliaryBodyDeclarations.add(child))pending.addLast(child);
        }
        for(int i=0;i<declarations.size();i++) {
            cancelled();Map<String,Object> value=Json.map(declarations.get(i)),attributes=Json.map(value.get("attributes"));
            String source=Json.string(value,"source");
            // The raw compiler model retains field-initializer anonymous types
            // for semantic consumers. The dependency declaration view contains
            // named headers and their members, without body-local inventories.
            if(auxiliaryBodyDeclarations.contains(new Identity(source,Json.string(value,"signature"))))continue;
            // The compiler also retains body symbols for graph consumers. The
            // detached declaration inventory follows the reference's headers.
            if(Set.of("LOCAL_VARIABLE","EXCEPTION_PARAMETER","RESOURCE_VARIABLE","INITIALIZER","LAMBDA","METHOD_REF")
                    .contains(Json.string(attributes,"element_kind")))continue;
            if("PARAMETER".equals(attributes.get("element_kind"))&&!callableHeaders.contains(
                    new Identity(Json.string(value,"source"),Json.string(value,"parent"))))continue;
            catalogueRows.add(i);
            // javac creates enum valueOf(String) without source parameter trees.
            // Its verified names/types already live in the compiler method header.
            if(Boolean.TRUE.equals(attributes.get("compiler_implicit"))) {
                List<Object> names=Json.list(attributes.getOrDefault("parameter_names",List.of()));
                List<Object> parameterTypes=Json.list(attributes.getOrDefault("parameter_type_models",List.of()));
                if(names.size()!=parameterTypes.size())throw new IllegalArgumentException("JAVA_MODEL_IMPLICIT_PARAMETER_HEADER");
                for(int p=0;p<names.size();p++) {
                    String name=names.get(p).toString(),owner=Json.string(value,"signature");
                    if(parameterHeaders.contains(List.of(source,owner,name)))continue;
                    formalDeclarations.add(ExtensionContext.freezeMap(Json.object("kind","declaration","source",source,
                        "signature",owner+"#parameter:"+p+":"+name,"parent",owner,"name",name,"start",-1,"end",-1,
                        "generated",true,"attributes",Json.object("element_kind","PARAMETER","compiler_implicit",true,
                            "type_model",parameterTypes.get(p),"body_available",false))));
                    catalogueRows.add(-formalDeclarations.size());
                }
            }
            for(Object raw:Json.list(attributes.getOrDefault("type_parameter_declarations",List.of()))) {
                Map<String,Object> formal=new LinkedHashMap<>(Json.map(raw));
                formal.put("source",value.get("source"));formal.put("parent",value.get("signature"));
                formal.put("signature",Json.string(value,"signature")+"#typevar:"+Json.string(formal,"name"));
                formal.put("kind","declaration");formalDeclarations.add(ExtensionContext.freezeMap(formal));
                catalogueRows.add(-formalDeclarations.size());
            }
        }
        boolean hasModeled=false;
        for(int i:catalogueRows) {
            cancelled();Map<String,Object> value=row(i);String source=Json.string(value,"source");
            unit(source);List<Integer> sourceRows=rows.computeIfAbsent(source,ignored->new ArrayList<>());
            if(sourceRows.size()>=100000)throw new IllegalArgumentException("JAVA_MODEL_DECLARATION_BUDGET");
            DeclarationRef ref=new DeclarationRef(source,sourceRows.size());sourceRows.add(i);
            byIdentity.computeIfAbsent(new Identity(source,Json.string(value,"signature")),ignored->new ArrayList<>()).add(ref);
            Map<String,Object> attributes=Json.map(value.get("attributes"));
            if(Boolean.TRUE.equals(attributes.get("modeled"))||attributes.containsKey("extension_producer"))hasModeled=true;
            long start=number(value,"start"),end=number(value,"end");
            if(start>=0&&end>=start)bySpan.computeIfAbsent(new Span(source,start,end),ignored->new ArrayList<>()).add(ref);
            if(Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(Json.string(attributes,"element_kind"))) {
                Set<String> names=new LinkedHashSet<>();String binary=Objects.toString(attributes.get("binary_name"),"");
                names.add(Json.string(value,"signature"));if(!binary.isEmpty())names.add(binary);
                if(!binary.isEmpty()&&!Set.of("LOCAL","ANONYMOUS").contains(Objects.toString(attributes.get("nesting_kind"),"")))names.add(binary.replace('$','.'));
                for(String name:names)if(!name.isEmpty())byType.computeIfAbsent(name,ignored->new ArrayList<>()).add(ref);
            }
        }
        Map<DeclarationRef,DeclarationRef> parentIndex=new HashMap<>();
        Map<DeclarationRef,List<DeclarationRef>> memberIndex=new HashMap<>();
        for(var entry:rows.entrySet())for(int ordinal=0;ordinal<entry.getValue().size();ordinal++) {
            cancelled();DeclarationRef ref=new DeclarationRef(entry.getKey(),ordinal);
            Map<String,Object> value=row(entry.getValue().get(ordinal));
            if(Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(Json.string(Json.map(value.get("attributes")),"element_kind"))
                    &&Set.of("LOCAL","ANONYMOUS").contains(Objects.toString(Json.map(value.get("attributes")).get("nesting_kind"),"")))continue;
            String owner=Json.string(value,"parent");if(owner.isEmpty())continue;
            List<DeclarationRef> candidates=byIdentity.getOrDefault(new Identity(ref.source(),owner),List.of());
            if(candidates.size()!=1)continue;DeclarationRef parent=candidates.getFirst();
            if(parent.equals(ref))throw new IllegalArgumentException("JAVA_MODEL_PARENT_CYCLE");
            parentIndex.put(ref,parent);memberIndex.computeIfAbsent(parent,ignored->new ArrayList<>()).add(ref);
        }
        for(DeclarationRef ref:parentIndex.keySet()) {
            Set<DeclarationRef> visited=new HashSet<>();DeclarationRef cursor=ref;
            while(cursor!=null) {
                if(!visited.add(cursor))throw new IllegalArgumentException("JAVA_MODEL_PARENT_CYCLE");
                if(visited.size()>129)throw new IllegalArgumentException("JAVA_MODEL_PARENT_DEPTH_BUDGET");
                cursor=parentIndex.get(cursor);
            }
        }
        positions=rows;identities=byIdentity;types=byType;spans=bySpan;parents=parentIndex;children=memberIndex;modeled=hasModeled;
    }
    private static long number(Map<String,Object> value,String key) {
        return value.get(key) instanceof Number number?number.longValue():-1;
    }
    private Map<String,Object> row(int position) {
        return position<0?formalDeclarations.get(-position-1):Json.map(Json.list(model.get("declarations")).get(position));
    }
    public boolean hasModeledDeclarations() {catalogue();return modeled;}
    public List<DeclarationRef> declarations(String source) {
        unit(source);catalogue();List<DeclarationRef> result=new ArrayList<>();
        for(int ordinal=0;ordinal<positions.getOrDefault(source,List.of()).size();ordinal++)result.add(new DeclarationRef(source,ordinal));
        return List.copyOf(result);
    }
    public Map<String,Object> declaration(DeclarationRef ref) {
        unit(ref.source());catalogue();List<Integer> sourceRows=positions.getOrDefault(ref.source(),List.of());
        Objects.checkIndex(ref.ordinal(),sourceRows.size());
        return row(sourceRows.get(ref.ordinal()));
    }
    public List<DeclarationRef> members(DeclarationRef ref) {declaration(ref);return List.copyOf(children.getOrDefault(ref,List.of()));}
    public Optional<DeclarationRef> parent(DeclarationRef ref) {declaration(ref);return Optional.ofNullable(parents.get(ref));}
    public List<DeclarationRef> declarationsAt(String source,long start,long end) {
        unit(source);catalogue();return List.copyOf(spans.getOrDefault(new Span(source,start,end),List.of()));
    }
    public TypeLookup type(String name) {
        catalogue();List<DeclarationRef> candidates=types.getOrDefault(name,List.of());
        return switch(candidates.size()) {case 0->new MissingType(name);case 1->new FoundType(candidates.getFirst());default->new AmbiguousType(candidates);};
    }
    public List<DeclarationRef> declarationsNamed(String source,String signature) {
        unit(source);catalogue();return List.copyOf(identities.getOrDefault(new Identity(source,signature),List.of()));
    }
    private void syntax() {
        open();if(syntax!=null)return;Map<Span,List<Integer>> found=new HashMap<>();List<Object> rows=Json.list(model.get("syntax"));
        Map<String,List<Integer>> sourceRows=new HashMap<>();Map<String,Map<Integer,Integer>> identities=new HashMap<>();
        Map<String,Map<Integer,List<Integer>>> parentEdges=new HashMap<>();
        for(int i=0;i<rows.size();i++) {
            cancelled();if(i>=2000000)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_BUDGET");
            Map<String,Object> row=Json.map(rows.get(i));String source=Json.string(row,"source");unit(source);
            int id=Math.toIntExact(Json.number(row,"id")),parent=Math.toIntExact(Json.number(row,"parent"));
            if(id<=0||parent<0)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_IDENTITY");
            parentEdges.computeIfAbsent(source,ignored->new HashMap<>()).computeIfAbsent(id,ignored->new ArrayList<>()).add(parent);
            Integer previous=identities.computeIfAbsent(source,ignored->new HashMap<>()).putIfAbsent(id,i);
            if(previous!=null) {
                if(!sameSyntax(Json.map(rows.get(previous)),row))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_IDENTITY");
                continue; // javac shares anonymous base types and multi-variable type/modifier trees.
            }
            sourceRows.computeIfAbsent(source,ignored->new ArrayList<>()).add(i);
            long start=number(row,"start"),end=number(row,"end");
            if(start>=0&&end>=start)found.computeIfAbsent(new Span(source,start,end),ignored->new ArrayList<>()).add(i);
        }
        for(var edges:parentEdges.values())validateSyntaxParents(edges);
        syntax=found;syntaxSourceRows=sourceRows;
    }
    private record SyntaxVisit(int id,Iterator<Integer> parents) {}
    private static void validateSyntaxParents(Map<Integer,List<Integer>> edges) {
        Map<Integer,Integer> depths=new HashMap<>();depths.put(0,-1);Set<Integer> active=new HashSet<>();
        Deque<SyntaxVisit> pending=new ArrayDeque<>();
        for(int root:edges.keySet()) {
            cancelled();if(depths.containsKey(root))continue;
            active.add(root);pending.push(new SyntaxVisit(root,edges.get(root).iterator()));
            while(!pending.isEmpty()) {
                cancelled();SyntaxVisit current=pending.peek();
                if(current.parents().hasNext()) {
                    int parent=current.parents().next();if(depths.containsKey(parent))continue;
                    if(!edges.containsKey(parent))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_PARENT_OUTSIDE_SOURCE");
                    if(!active.add(parent))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_PARENT_CYCLE");
                    if(pending.size()>=513)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_DEPTH_BUDGET");
                    pending.push(new SyntaxVisit(parent,edges.get(parent).iterator()));
                } else {
                    int depth=0;for(int parent:edges.get(current.id()))depth=Math.max(depth,depths.get(parent)+1);
                    if(depth>512)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_DEPTH_BUDGET");
                    depths.put(current.id(),depth);active.remove(current.id());pending.pop();
                }
            }
        }
    }
    private Map<String,List<Integer>> syntaxSourceRows;
    private static boolean sameSyntax(Map<String,Object> left,Map<String,Object> right) {
        Set<String> keys=new HashSet<>(left.keySet());keys.addAll(right.keySet());keys.remove("parent");keys.remove("owner");
        for(String key:keys) {
            if(left.containsKey(key)!=right.containsKey(key))return false;
            Object a=left.get(key),b=right.get(key);
            if(TypeGraph.isView(a)&&TypeGraph.isView(b)) {
                var ga=TypeGraph.owner(Json.map(a));var gb=TypeGraph.owner(Json.map(b));
                int ra=TypeGraph.reference(Json.map(a)),rb=TypeGraph.reference(Json.map(b));
                if(ga==gb&&ra==rb)continue;
                if(!ga.canonical(List.of(ra)).equals(gb.canonical(List.of(rb))))return false;
            } else if(!Objects.equals(a,b))return false;
        }
        return true;
    }
    public List<Map<String,Object>> syntaxAt(String source,long start,long end) {
        unit(source);syntax();List<Map<String,Object>> result=new ArrayList<>();
        for(int row:syntax.getOrDefault(new Span(source,start,end),List.of()))result.add(Json.map(Json.list(model.get("syntax")).get(row)));
        return List.copyOf(result);
    }
    public Optional<Map<String,Object>> syntax(DeclarationRef ref) {
        Map<String,Object> declaration=declaration(ref);long start=number(declaration,"start"),end=number(declaration,"end");
        if(start<0||end<start)return Optional.empty();
        String kind=Json.string(Json.map(declaration.get("attributes")),"element_kind"),name=Json.string(declaration,"name");
        return syntaxAt(ref.source(),start,end).stream().filter(node->switch(kind) {
            case "CLASS","INTERFACE","ENUM","ANNOTATION_TYPE"->Set.of("CLASS","INTERFACE","ENUM","ANNOTATION_TYPE").contains(Json.string(node,"kind"))&&name.equals(Json.string(node,"name"));
            case "METHOD","CONSTRUCTOR"->"METHOD".equals(node.get("kind"));
            case "FIELD","ENUM_CONSTANT","PARAMETER"->"VARIABLE".equals(node.get("kind"))&&name.equals(Json.string(node,"name"));
            case "TYPE_PARAMETER"->"TYPE_PARAMETER".equals(node.get("kind"))&&name.equals(Json.string(node,"name"));
            default->false;
        }).findFirst();
    }
    /** Source-order navigation follows the compiler's existing IDs and parent edges. */
    public Syntax syntax(String source) {
        unit(source);Syntax cached=syntaxViews.get(source);if(cached!=null)return cached;
        syntax();Syntax found=new Syntax(source,Json.list(model.get("syntax")),syntaxSourceRows.getOrDefault(source,List.of()));syntaxViews.put(source,found);return found;
    }
    public static final class Syntax {
        private final String source;private final List<Object> rows;
        private final List<Integer> order=new ArrayList<>();
        private final Map<Integer,Integer> positions=new HashMap<>(),parents=new HashMap<>();
        private final Map<Span,List<Integer>> spans=new HashMap<>();
        private Syntax(String source,List<Object> rows,List<Integer> sourceRows) {
            this.source=source;this.rows=rows;
            for(int i:sourceRows) {
                cancelled();Map<String,Object> node=Json.map(rows.get(i));if(!source.equals(node.get("source")))continue;
                if(order.size()>=1000000)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_BUDGET");
                int id=Math.toIntExact(Json.number(node,"id")),parent=Math.toIntExact(Json.number(node,"parent"));
                if(id<=0||parent<0||positions.putIfAbsent(id,i)!=null)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_IDENTITY");
                parents.put(id,parent);order.add(i);
                long start=number(node,"start"),end=number(node,"end");
                if(start>=0&&end>=start)spans.computeIfAbsent(new Span(source,start,end),ignored->new ArrayList<>()).add(i);
            }
            Map<Integer,Integer> depths=new HashMap<>();
            for(int id:positions.keySet()) {
                cancelled();List<Integer> path=new ArrayList<>();Set<Integer> active=new HashSet<>();int cursor=id;
                while(cursor!=0&&!depths.containsKey(cursor)) {
                    if(!positions.containsKey(cursor))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_PARENT_OUTSIDE_SOURCE");
                    if(!active.add(cursor))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_PARENT_CYCLE");
                    if(path.size()>512)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_DEPTH_BUDGET");
                    path.add(cursor);cursor=parents.get(cursor);
                }
                int depth=cursor==0?-1:depths.get(cursor);
                for(int index=path.size()-1;index>=0;index--) {
                    if(++depth>512)throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_DEPTH_BUDGET");
                    depths.put(path.get(index),depth);
                }
            }
        }
        public List<Map<String,Object>> nodes() {
            cancelled();return new AbstractList<>() {
                @Override public int size() {return order.size();}
                @Override public Map<String,Object> get(int index) {cancelled();return Json.map(rows.get(order.get(index)));}
            };
        }
        public Optional<Map<String,Object>> node(int id) {
            cancelled();Integer position=positions.get(id);return position==null?Optional.empty():Optional.of(Json.map(rows.get(position)));
        }
        public List<Map<String,Object>> nodes(long start,long end) {
            cancelled();List<Map<String,Object>> result=new ArrayList<>();
            for(int position:spans.getOrDefault(new Span(source,start,end),List.of()))result.add(Json.map(rows.get(position)));
            return List.copyOf(result);
        }
        private int identity(Map<String,Object> node) {
            if(!source.equals(node.get("source")))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_NODE_OUTSIDE_SOURCE");
            int id=Math.toIntExact(Json.number(node,"id"));
            if(!positions.containsKey(id))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_NODE_IDENTITY");
            Map<String,Object> original=Json.map(rows.get(positions.get(id)));
            // Type views can contain cycles. Validate the scalar node identity;
            // navigation returns the sealed row, never caller-supplied payloads.
            for(String field:List.of("kind","parent","start","end"))
                if(!Objects.equals(original.get(field),node.get(field)))throw new IllegalArgumentException("JAVA_MODEL_SYNTAX_NODE_IDENTITY");
            return id;
        }
        public Optional<Map<String,Object>> parent(Map<String,Object> node) {cancelled();return node(parents.get(identity(node)));}
        public Optional<Map<String,Object>> enclosing(Map<String,Object> node,Set<String> kinds) {
            cancelled();for(Map<String,Object> parent=parent(node).orElse(null);parent!=null;parent=parent(parent).orElse(null))
                if(kinds.contains(Json.string(parent,"kind")))return Optional.of(parent);
            return Optional.empty();
        }
    }
    public Source source(String path) throws IOException {
        Map<String,Object> unit=unit(path);Source cached=sources.get(path);if(cached!=null)return cached;
        Path input=Path.of(Json.string(unit,"input"));long size=Files.size(input);
        if(size>64L*1024*1024)throw new IOException("JAVA_MODEL_SOURCE_BYTES_BUDGET");
        byte[] bytes;
        try(var stream=Files.newInputStream(input);var output=new java.io.ByteArrayOutputStream()) {
            byte[] chunk=new byte[65536];int count;
            while((count=stream.read(chunk))!=-1) {
                cancelled();if(count>64L*1024*1024-output.size())throw new IOException("JAVA_MODEL_SOURCE_BYTES_BUDGET");
                output.write(chunk,0,count);
            }
            bytes=output.toByteArray();
        }
        if(!Json.sha(bytes).equals(Json.string(unit,"hash")))throw new IOException("JAVA_MODEL_SOURCE_CHANGED: "+path);
        Source source=new Source(path,bytes);
        if(bytes.length<=8*1024*1024) {
            while(!sources.isEmpty()&&sources.values().stream().mapToLong(value->value.bytes.length).sum()+bytes.length>16L*1024*1024)
                sources.remove(sources.keySet().iterator().next());
            sources.put(path,source);
        }
        return source;
    }
    public static final class Source {
        private final String path;private final byte[] bytes;private String text;private int[] lines;
        private Source(String path,byte[] bytes) {this.path=path;this.bytes=bytes;}
        public String path() {return path;}
        public byte[] bytes() {return bytes.clone();}
        public String text() throws IOException {
            cancelled();
            if(text==null) {
                text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                int[] starts=new int[Math.min(text.length()+1,256)];int count=1;
                for(int i=0;i<text.length();i++) {
                    char c=text.charAt(i);if(c!='\r'&&c!='\n')continue;
                    if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;
                    if(count==starts.length)starts=Arrays.copyOf(starts,Math.min(text.length()+1,starts.length*2));
                    starts[count++]=i+1;
                }
                lines=Arrays.copyOf(starts,count);
            }
            return text;
        }
        public String slice(long start,long end) throws IOException {
            String value=text();if(start<0||end<start||end>value.length())throw new IllegalArgumentException("JAVA_MODEL_SOURCE_SPAN_UNAVAILABLE");
            return value.substring(Math.toIntExact(start),Math.toIntExact(end));
        }
        public Position position(long offset) throws IOException {
            String value=text();if(offset<0||offset>value.length())throw new IllegalArgumentException("JAVA_MODEL_SOURCE_OFFSET_UNAVAILABLE");
            int point=Math.toIntExact(offset),index=Arrays.binarySearch(lines,point),line=index>=0?index:-index-2;
            return new Position(line+1,point-lines[line]+1);
        }
    }
    @Override public void close() {
        if(closed)return;closed=true;units=null;positions=null;identities=null;types=null;parents=null;children=null;spans=null;syntax=null;syntaxSourceRows=null;sources.clear();formalDeclarations.clear();syntaxViews.clear();
    }
}
