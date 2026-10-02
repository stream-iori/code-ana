package dev.codeana.compiler;

import dev.codeana.common.Json;
import dev.codeana.common.TypeGraph;
import java.util.*;
import java.util.function.Function;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;

/** Adapted from frozen Anatomist WorkerTypes.add: identity graphs preserve recursive bounds. */
public final class CompilerTypeGraph {
    private final IdentityHashMap<TypeMirror,Integer> identities = new IdentityHashMap<TypeMirror,Integer>();
    private final IdentityHashMap<Element,Map<String,Integer>> variables = new IdentityHashMap<Element,Map<String,Integer>>();
    private final List<Map<String,Object>> entries = new ArrayList<Map<String,Object>>();
    private final TypeGraph.Builder graph = new TypeGraph.Builder();
    private final Elements elements;
    private final Function<TypeMirror,Map<String,Object>> metadata;
    public CompilerTypeGraph(Elements elements, Function<TypeMirror,Map<String,Object>> metadata) {
        this.elements=elements;this.metadata=metadata;
    }
    public int add(TypeMirror type) {
        int checkpoint=entries.size();
        try { return add(type,0); }
        catch(RuntimeException failure) {
            graph.rollback(checkpoint);entries.subList(checkpoint,entries.size()).clear();
            identities.entrySet().removeIf(entry->entry.getValue()>=checkpoint);
            for(Map<String,Integer> values:variables.values())values.entrySet().removeIf(entry->entry.getValue()>=checkpoint);
            throw failure;
        }
    }
    private int add(TypeMirror type,int depth) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CANCELLED");
        if (type == null) return -1;
        Integer known=identities.get(type);
        if (known != null) return known;
        Element variable=null;String bounds=null;
        if (type.getKind()==TypeKind.TYPEVAR && !type.toString().startsWith("capture#")) {
            TypeVariable parameter=(TypeVariable)type;variable=parameter.asElement();
            bounds=parameter.getUpperBound()+"|"+parameter.getLowerBound()+"|"+type.getAnnotationMirrors();
            Map<String,Integer> values=variables.get(variable);
            known=values==null?null:values.get(bounds);
            if (known != null) return known;
        }
        if (depth > 128 || entries.size() >= TypeGraph.MAX_NODES) throw new Budget();
        int reference=entries.size();
        Map<String,Object> node=Json.object("version",TypeGraph.VERSION,"kind",type.getKind().name(),
                "display",type.toString(),"complete",type.getKind()!=TypeKind.ERROR);
        int reserved=graph.reserve(node);if(reserved!=reference)throw new AssertionError("graph identity");
        node=graph.edit(reference);entries.add(node);identities.put(type,reference);
        if(variable!=null)variables.computeIfAbsent(variable,ignored->new HashMap<String,Integer>()).put(bounds,reference);
        node.putAll(metadata.apply(type));
        switch(type.getKind()) {
            case ARRAY:node.put("component",add(((ArrayType)type).getComponentType(),depth+1));break;
            case DECLARED:case ERROR:
                DeclaredType declared=(DeclaredType)type;
                Element element=declared.asElement();
                node.put("element",element instanceof TypeElement?elements.getBinaryName((TypeElement)element).toString():element.toString());
                node.put("element_kind",element.getKind().name());
                node.put("enclosing",add(declared.getEnclosingType(),depth+1));
                node.put("arguments",all(declared.getTypeArguments(),depth+1));break;
            case TYPEVAR:
                TypeVariable parameter=(TypeVariable)type;node.put("name",parameter.asElement().getSimpleName().toString());
                node.put("upper",add(parameter.getUpperBound(),depth+1));
                if(parameter.getLowerBound()==null) {node.put("complete",false);node.put("reason","TYPE_VARIABLE_LOWER_BOUND_UNAVAILABLE");}
                else node.put("lower",add(parameter.getLowerBound(),depth+1));
                break;
            case WILDCARD:
                WildcardType wildcard=(WildcardType)type;
                node.put("extends",add(wildcard.getExtendsBound(),depth+1));
                node.put("super",add(wildcard.getSuperBound(),depth+1));break;
            case INTERSECTION:node.put("bounds",all(((IntersectionType)type).getBounds(),depth+1));break;
            case UNION:node.put("alternatives",all(((UnionType)type).getAlternatives(),depth+1));break;
            case EXECUTABLE:
                ExecutableType executable=(ExecutableType)type;
                node.put("type_variables",all(executable.getTypeVariables(),depth+1));
                node.put("receiver",add(executable.getReceiverType(),depth+1));
                node.put("return",add(executable.getReturnType(),depth+1));
                node.put("parameters",all(executable.getParameterTypes(),depth+1));
                node.put("throws",all(executable.getThrownTypes(),depth+1));break;
            default:break;
        }
        return reference;
    }
    private List<Integer> all(List<? extends TypeMirror> types,int depth) {
        List<Integer> output=new ArrayList<Integer>();for(TypeMirror type:types)output.add(add(type,depth));return output;
    }
    public Map<String,Object> view(TypeMirror type) { return graph.graph().view(add(type)); }
    public Map<String,Object> view(int root) { return graph.graph().view(root); }
    public TypeGraph snapshot() { return graph.seal(); }
    public static final class Budget extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        Budget() {super("TYPE_GRAPH_BUDGET");}
    }
}
