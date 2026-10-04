package dev.codeana.analysis;

import dev.codeana.common.*;
import java.nio.file.*;
import java.util.*;

/** Compiler type roots remain valid for later contexts, and are released at the task boundary. */
public final class CompilerModelLeaseProbe {
    private static Map<String,Object> type(Object raw) {
        if(TypeGraph.isView(raw))return Json.map(raw);
        if(raw instanceof Map)for(Object child:Json.map(raw).values()) {
            Map<String,Object> found=type(child);if(found!=null)return found;
        }
        if(raw instanceof List)for(Object child:Json.list(raw)) {
            Map<String,Object> found=type(child);if(found!=null)return found;
        }
        return null;
    }
    public static void main(String[] args) throws Exception {
        Map<String,Object> request=Json.map(Json.parse(Json.read(Path.of(args[0]))));
        Map<String,Object> context=Json.map(Json.list(request.get("contexts")).getFirst());
        TypeGraph retained;int root;
        try(CompilerSession session=new CompilerSession(request)) {
            Map<String,Object> first=session.analyze(context);
            Map<String,Object> value=type(first.get("declarations"));
            if(value==null)throw new AssertionError("compiler emitted no graph roots");
            retained=TypeGraph.owner(value);root=TypeGraph.reference(value);
            Map<String,Object> expected=retained.canonical(List.of(root));
            session.releaseModel(first);
            Map<String,Object> second=session.analyze(context);
            session.releaseModel(second);
            if(!expected.equals(retained.canonical(List.of(root))))throw new AssertionError("cross-context root drift");
        }
        try{retained.node(root);throw new AssertionError("task graph lease leaked");}
        catch(IllegalStateException expected) {
            if(!"RECORD_ARCHIVE_CLOSED".equals(expected.getMessage()))throw expected;
        }
        System.out.println("actual javac roots survive context release and close with the compiler task");
    }
}
