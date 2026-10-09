import com.anatomist.core.java.JavaNode;
import java.util.*;

/** Checks that allocation rebasing preserves every semantic field and edge. */
public class ModelSyntaxIdentityWitness {
    static JavaNode tree(int offset, String literal) {
        return new JavaNode(offset,"UNIT",0,12,Map.of("body",List.of(new JavaNode(offset+3,"LITERAL",1,5,Map.of("value",literal,"metadata",Map.of("id",777))))));
    }
    static Map<String,Object> bindings(int offset) {
        return Map.of(Integer.toString(offset+3),new TreeMap<>(Map.of("id",offset+3,"type","Widget","expressionType",7,"declaredType",-1,"name","value")));
    }
    public static void main(String[] args) {
        var first=tree(100,"source literal");var second=tree(700,"source literal");
        ParityWitness.observedSyntaxBase=ParityWitness.minimumSyntaxId(first);
        Object a=ParityWitness.canonical("raw.syntax",first);Object ab=ParityWitness.canonical("raw.bindings",bindings(100));
        ParityWitness.observedSyntaxBase=ParityWitness.minimumSyntaxId(second);
        Object b=ParityWitness.canonical("raw.syntax",second);Object bb=ParityWitness.canonical("raw.bindings",bindings(700));
        if(!a.equals(b)||!ab.equals(bb))throw new AssertionError("syntax/binding identity not bijective");
        if(a.equals(ParityWitness.canonical("raw.syntax",tree(700,"changed literal"))))throw new AssertionError("literal was normalized");
        var binding=(Map<?,?>)((Map<?,?>)bb).get("3");if(!binding.get("expressionType").equals(7))throw new AssertionError("type graph index changed");
        var broken=new TreeMap<>((Map<String,Object>)bindings(700));broken.put("704",broken.remove("703"));
        try { ParityWitness.canonical("raw.bindings",broken);throw new AssertionError("invalid binding ID accepted"); }
        catch(IllegalArgumentException expected) { }
        System.out.println("syntax_identity_rebasing_ok");
    }
}
