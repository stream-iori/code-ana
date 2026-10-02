package dev.codeana.analysis;

import dev.codeana.common.Json;
import dev.codeana.common.TypeGraph;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import java.util.*;
import java.util.function.IntConsumer;

/** Isolated candidate adapted from frozen Anatomist JavaBinaryTypes. Not wired into BinaryIndex. */
public final class BinarySignatureGraph {
    private record Formal(String name, String owner, int root, List<Integer> bounds) {}
    private record Executable(int root, List<Formal> formals) {}
    private static final List<String> SINGLE = List.of("enclosing", "component", "extends", "super", "upper", "lower", "return", "receiver");
    private static final List<String> MANY = List.of("arguments", "bounds", "alternatives", "parameters", "throws", "type_variables");
    private final TypeGraph.Builder builder = new TypeGraph.Builder();
    private final Map<String,Integer> variables = new LinkedHashMap<>();
    private final Map<String,Integer> classVariables = new LinkedHashMap<>();
    private final IdentityHashMap<TypeGraph,Map<Integer,Integer>> imports = new IdentityHashMap<>();
    private final List<Formal> classFormals = new ArrayList<>();
    private final Map<String,Executable> methods = new LinkedHashMap<>();
    private final Map<String,Integer> fields = new LinkedHashMap<>();
    private final List<Integer> interfaces = new ArrayList<>();
    private final int nil = node("NULL", Json.object("display", "null"), true);
    private int superclass = -1, work;
    private final String owner;
    public BinarySignatureGraph(String owner, String signature, String superclass, List<String> interfaces,
            Map<String,Map<String,Object>> enclosing) {
        this.owner = owner;
        for (var entry : enclosing.entrySet()) {
            Map<String,Object> model = Json.map(entry.getValue().get("type_model"));
            if (!TypeGraph.isView(model)) throw new IllegalArgumentException("BINARY_ENCLOSING_GRAPH_REQUIRED");
            variables.put(entry.getKey(), importGraph(TypeGraph.owner(model), TypeGraph.reference(model)));
        }
        String header = signature;
        if (header == null) {
            header = "L" + (superclass == null ? "java/lang/Object" : superclass.replace('.', '/')) + ";";
            for (String name : interfaces) header += "L" + name.replace('.', '/') + ";";
        }
        checkInput(header); new Grammar(header).declaration(); Header classHeader=header(classFormals, false, owner);
        new SignatureReader(header).accept(classHeader);classHeader.finish(null);
        if (superclass == null) this.superclass = -1;
        classVariables.putAll(variables);
    }
    private int node(String kind, Map<String,Object> extra, boolean complete) {
        Map<String,Object> meta = Json.object("kind",kind,"complete",complete,"origin","binary_signature");
        meta.putAll(extra); return builder.reserve(meta);
    }
    private Map<String,Object> edit(int id) { return builder.edit(id); }
    private void checkInput(String input) {
        if (input == null || input.isEmpty() || input.length() > 65536)
            throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
        work = 0;
    }
    private void step(int depth) {
        if (depth > 64 || ++work > 4096) throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
    }
    private int declared(String name,int enclosing,List<Integer> arguments) {
        int id = node("DECLARED",Json.object("element",name,"display",name),true);
        edit(id).put("enclosing",enclosing);edit(id).put("arguments",arguments);return id;
    }
    private int variable(String name) {
        Integer previous=variables.get(name);if(previous!=null)return previous;
        int id=node("TYPEVAR",Json.object("name",name,"display",name,"owner","","owner_unavailable",true,"bounds_unavailable",true),false);
        edit(id).put("upper",-1);edit(id).put("lower",nil);variables.put(name,id);return id;
    }
    private Header header(List<Formal> formals,boolean method,String owner) {
        return new Header(formals,method,owner);
    }
    private final class Header extends SignatureVisitor {
            private final List<Formal> formals;
            private final boolean method;
            private final String owner;
            Header(List<Formal> formals, boolean method, String owner) {
                super(Opcodes.ASM9);this.formals=formals;this.method=method;this.owner=owner;
            }
            private Formal current;
            private int returns=-1;
            private final List<Integer> parameters=new ArrayList<>(),thrown=new ArrayList<>();
            public void visitFormalTypeParameter(String name) {
                step(0);if(name.isEmpty())throw new IllegalArgumentException("BINARY_SIGNATURE_FORMAL");
                int id=node("TYPEVAR",Json.object("name",name,"display",name,"owner",owner),true);
                edit(id).put("lower",nil);variables.put(name,id);
                current=new Formal(name,owner,id,new ArrayList<>());formals.add(current);
            }
            private SignatureVisitor bound() {
                if(current==null)throw new IllegalArgumentException("BINARY_SIGNATURE_FORMAL");
                Formal formal=current;
                return type(value->{formal.bounds.add(value);
                    if(formal.bounds.size()==1)edit(formal.root).put("upper",value);
                    else {int upper=node("INTERSECTION",Json.object(),true);edit(upper).put("bounds",formal.bounds);edit(formal.root).put("upper",upper);}},0);
            }
            public SignatureVisitor visitClassBound(){return bound();}
            public SignatureVisitor visitInterfaceBound(){return bound();}
            public SignatureVisitor visitSuperclass(){return type(value->{if(!method)superclass=value;},0);}
            public SignatureVisitor visitInterface(){return type(value->{if(!method)interfaces.add(value);},0);}
            public SignatureVisitor visitParameterType(){return type(parameters::add,0);}
            public SignatureVisitor visitExceptionType(){return type(thrown::add,0);}
            public SignatureVisitor visitReturnType(){return type(value->returns=value,0);}
            void finish(String key) {
                for(Formal f:formals)if(f.bounds.isEmpty()){edit(f.root).put("upper",-1);edit(f.root).put("complete",false);edit(f.root).put("bounds_unavailable",true);}
                if(method){if(returns<0)throw new IllegalArgumentException("BINARY_SIGNATURE_RETURN");
                    int root=node("EXECUTABLE",Json.object(),true);edit(root).put("return",returns);edit(root).put("parameters",parameters);edit(root).put("throws",thrown);edit(root).put("type_variables",formals.stream().map(Formal::root).toList());methods.put(key,new Executable(root,List.copyOf(formals)));}
            }
    }
    public void field(String key,String signature,String descriptor) {
        variables.clear();variables.putAll(classVariables);String input=signature==null?descriptor:signature;checkInput(input);
        new Grammar(input).field();
        new SignatureReader(input).acceptType(type(value->{if(fields.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("BINARY_SIGNATURE_DUPLICATE_FIELD");},0));
    }
    public void method(String key,String signature,String descriptor) {
        variables.clear();variables.putAll(classVariables);String input=signature==null?descriptor:signature;checkInput(input);
        new Grammar(input).method();
        List<Formal> formals=new ArrayList<>();Header visitor=header(formals,true,key);
        new SignatureReader(input).accept(visitor);
        visitor.finish(key);
    }
    private SignatureVisitor type(IntConsumer output,int depth) {
        step(depth);
        return new SignatureVisitor(Opcodes.ASM9) {
            private String name;private int enclosing=-1;private final List<Integer> arguments=new ArrayList<>();
            public void visitBaseType(char token) {
                String kind=switch(token){case 'Z'->"BOOLEAN";case 'B'->"BYTE";case 'C'->"CHAR";case 'S'->"SHORT";case 'I'->"INT";case 'J'->"LONG";case 'F'->"FLOAT";case 'D'->"DOUBLE";case 'V'->"VOID";default->throw new IllegalArgumentException("BINARY_SIGNATURE_PRIMITIVE");};
                output.accept(node(kind,Json.object("display",kind.toLowerCase(Locale.ROOT),"descriptor",String.valueOf(token)),true));
            }
            public void visitTypeVariable(String name){step(depth);output.accept(variable(name));}
            public SignatureVisitor visitArrayType(){return type(value->{int id=node("ARRAY",Json.object(),true);edit(id).put("component",value);output.accept(id);},depth+1);}
            public void visitClassType(String binary){step(depth);name=binary.replace('/','.');}
            public void visitInnerClassType(String part){step(depth);enclosing=declared(name,enclosing,arguments);arguments.clear();name+="$"+part;}
            public void visitTypeArgument(){step(depth);int id=node("WILDCARD",Json.object(),true);edit(id).put("extends",-1);edit(id).put("super",-1);arguments.add(id);}
            public SignatureVisitor visitTypeArgument(char variance){return type(value->{if(variance=='=')arguments.add(value);else if(variance=='+'||variance=='-'){int id=node("WILDCARD",Json.object(),true);edit(id).put("extends",variance=='+'?value:-1);edit(id).put("super",variance=='-'?value:-1);arguments.add(id);}else throw new IllegalArgumentException("BINARY_SIGNATURE_VARIANCE");},depth+1);}
            public void visitEnd(){if(name==null||name.isEmpty())throw new IllegalArgumentException("BINARY_SIGNATURE_NAME");output.accept(declared(name,enclosing,arguments));}
        };
    }
    private int importGraph(TypeGraph graph,int root) {
        Map<Integer,Integer> ids=imports.computeIfAbsent(graph,key->new HashMap<>());
        if(ids.containsKey(root))return ids.get(root);
        ArrayDeque<Integer> pending=new ArrayDeque<>();
        java.util.function.IntUnaryOperator reserve=id->{if(id<0)return -1;Integer known=ids.get(id);if(known!=null)return known;Map<String,Object> meta=new LinkedHashMap<>(graph.node(id));for(String role:SINGLE)meta.remove(role);for(String role:MANY)meta.remove(role);int fresh=builder.reserve(meta);ids.put(id,fresh);pending.add(id);return fresh;};
        int result=reserve.applyAsInt(root);
        while(!pending.isEmpty()) {int old=pending.removeFirst(),fresh=ids.get(old);Map<String,Object> row=graph.node(old);
            for(String role:SINGLE)if(row.containsKey(role))edit(fresh).put(role,reserve.applyAsInt(((Number)row.get(role)).intValue()));
            for(String role:MANY)if(row.containsKey(role)){List<Integer> refs=new ArrayList<>();for(Object child:Json.list(row.get(role)))refs.add(reserve.applyAsInt(((Number)child).intValue()));edit(fresh).put(role,refs);}}
        return result;
    }
    public Map<String,Object> finish() {
        TypeGraph graph=builder.seal();Map<String,Object> result=Json.object("type_parameters",formals(graph,classFormals),"superclass",superclass<0?Json.object("kind","NONE","version",3,"complete",true):graph.view(superclass),"interfaces",interfaces.stream().map(graph::view).toList());
        Map<String,Object> ms=Json.object(),fs=Json.object();for(var entry:methods.entrySet())ms.put(entry.getKey(),Json.object("type_model",graph.view(entry.getValue().root),"type_parameters",formals(graph,entry.getValue().formals)));for(var entry:fields.entrySet())fs.put(entry.getKey(),graph.view(entry.getValue()));return Json.object("declaration",result,"methods",ms,"fields",fs);
    }
    /** Validate the entire signature: ASM acceptType alone silently ignores trailing input. */
    private static final class Grammar {
        private final String input;private int position,nodes;
        Grammar(String input){this.input=input;}
        private char peek(){if(position>=input.length())throw new IllegalArgumentException("BINARY_SIGNATURE_INVALID");return input.charAt(position);}
        private void expect(char token){if(peek()!=token)throw new IllegalArgumentException("BINARY_SIGNATURE_INVALID");position++;}
        private void end(){if(position!=input.length())throw new IllegalArgumentException("BINARY_SIGNATURE_TRAILING_INPUT");}
        private void formals(){
            if(peek()!='<')return;position++;
            while(peek()!='>'){int start=position;while(peek()!=':')position++;if(start==position)throw new IllegalArgumentException("BINARY_SIGNATURE_FORMAL");position++;
                if(peek()!=':')type(0);while(peek()==':'){position++;type(0);}}
            position++;
        }
        void declaration(){formals();type(0);while(position<input.length())type(0);end();}
        void method(){formals();expect('(');while(peek()!=')')type(0);position++;type(0);while(position<input.length()){expect('^');type(0);}end();}
        void field(){type(0);end();}
        private void type(int depth){
            if(depth>64||++nodes>4096)throw new IllegalArgumentException("BINARY_SIGNATURE_BUDGET");
            char token=peek();position++;
            if("ZBCSIJFDV".indexOf(token)>=0)return;
            if(token=='['){type(depth+1);return;}
            if(token=='T'){int start=position;while(peek()!=';')position++;if(start==position)throw new IllegalArgumentException("BINARY_SIGNATURE_FORMAL");position++;return;}
            if(token!='L')throw new IllegalArgumentException("BINARY_SIGNATURE_INVALID");
            while(true){int start=position;while(peek()!='<'&&peek()!='.'&&peek()!=';')position++;if(start==position)throw new IllegalArgumentException("BINARY_SIGNATURE_NAME");
                if(peek()=='<'){position++;while(peek()!='>'){char variance=peek();if(variance=='*'){position++;}else{if(variance=='+'||variance=='-')position++;type(depth+1);}}position++;}
                char delimiter=peek();position++;if(delimiter==';')return;if(delimiter!='.')throw new IllegalArgumentException("BINARY_SIGNATURE_INVALID");}
        }
    }
    private static List<Object> formals(TypeGraph graph,List<Formal> formals) {
        return formals.stream().<Object>map(f->Json.object("name",f.name,"owner",f.owner,"bounds",f.bounds.stream().map(graph::view).toList(),"type_model",graph.view(f.root))).toList();
    }
}
