package dev.codeana.analysis;

import dev.codeana.common.Json;
import java.util.*;
import java.security.MessageDigest;
import org.objectweb.asm.*;

/** NoCode bytes, class completion boundaries, and bounded lazy-header access. */
public final class SignatureProjectionHeadersProbe {
    private static int reads;
    private static Map<String,Object> declaration(String binary, String enclosing, String kind) {
        var attributes=Json.object("element_kind",kind,"binary_name",binary,
                "nesting_kind",enclosing==null?"TOP_LEVEL":"MEMBER","enclosing_type",enclosing,
                "modifiers",List.of("public"),"superclass_type_model",
                Json.object("kind","DECLARED","element","java.lang.Object","complete",true),
                "interface_type_models",List.of(),"type_parameter_models",List.of(),"annotations",List.of());
        var row=Json.object("signature",binary,"name",binary.substring(binary.lastIndexOf('$')+1),"attributes",attributes);
        return new AbstractMap<>() {
            public Object get(Object key) { if("attributes".equals(key))reads++;return row.get(key); }
            public Set<Entry<String,Object>> entrySet() { return row.entrySet(); }
        };
    }
    private static Map<String,Integer> innerClasses(byte[] bytes) {
        Map<String,Integer> result=new LinkedHashMap<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            public void visitInnerClass(String name,String outer,String inner,int access) {
                result.put(name,access);
            }
        },ClassReader.SKIP_CODE);
        return result;
    }
    private static void require(boolean test,String reason) { if(!test)throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<256;i++) {
            String parent="p.Type"+i;rows.add(declaration(parent,null,"CLASS"));
            rows.add(declaration(parent+"$Child",parent,"CLASS"));
        }
        rows.add(declaration("p.Type0$Child$Deep","p.Type0$Child","INTERFACE"));
        rows.add(declaration("p.Type0$Anno","p.Type0","ANNOTATION_TYPE"));
        rows.add(declaration("p.Type0$Enum","p.Type0","ENUM"));
        // '$' in an unrelated top-level name must retain the old prefix semantics.
        rows.add(declaration("p.Type0$Child$Literal",null,"CLASS"));
        rows.add(declaration("p.Unrelated$Child","p.Unrelated","CLASS"));
        var output=SignatureProjection.project(rows);
        var root=innerClasses(output.get("p.Type0"));
        require(root.keySet().equals(new LinkedHashSet<>(List.of("p/Type0$Anno","p/Type0$Child","p/Type0$Enum"))),"root completion boundary");
        var deep=innerClasses(output.get("p.Type0$Child$Deep"));
        require(deep.keySet().equals(new LinkedHashSet<>(List.of("p/Type0$Child","p/Type0$Child$Deep"))),"ancestor and self boundary");
        require(innerClasses(output.get("p.Type0$Child$Literal")).keySet().equals(Set.of("p/Type0$Child")),"literal dollar prefix");
        require((root.get("p/Type0$Anno") & Opcodes.ACC_ANNOTATION)!=0,"annotation flags");
        require((root.get("p/Type0$Enum") & Opcodes.ACC_ENUM)!=0,"enum flags");
        var hash=MessageDigest.getInstance("SHA-256");
        for(var entry:output.entrySet()) { hash.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));hash.update(entry.getValue()); }
        String digest=HexFormat.of().formatHex(hash.digest());
        if(args.length==0) {
            require(digest.equals("0b534a76bbe86477b07ac662a5c05d73fccbb3efaa1b6edbd0bb0b9e5315f900"), "NoCode classfile byte drift");
            require(reads<rows.size()*12,"repeated model header decoding: "+reads);
            Map<String,Object> lazy=new AbstractMap<>() {
                public Object get(Object key) { return "element_kind".equals(key)?"FIELD":"source_annotations".equals(key)?List.of():null; }
                public Set<Entry<String,Object>> entrySet() { throw new AssertionError("unchanged header enumerated"); }
            };
            var original=Json.object("generated",false,"attributes",lazy);
            require(new SupplementalAbi(Map.of()).project(original)==original,"unchanged header copied");
        }
        System.out.println(Json.write(Json.object("sha256",digest,"types",output.size(),"header_reads",reads)));
    }
}
