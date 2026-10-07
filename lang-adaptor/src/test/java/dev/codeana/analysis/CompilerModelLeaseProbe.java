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
        try(CompilerSession session=new CompilerSession(request)) {
            var first=session.analyze(context);
            var original=type(first.get("declarations"));
            var expected=TypeGraph.owner(original).canonical(List.of(TypeGraph.reference(original)));
            var detached=session.stageModel(first,context);
            var other=new LinkedHashMap<>(context);other.put("context",context.get("context")+"-other");
            var second=session.analyze(other);
            var surviving=type(second.get("declarations"));
            session.releaseContextModels(Json.string(context,"context"));
            try {TypeGraph.owner(original).node(TypeGraph.reference(original));throw new AssertionError("completed context retained");}
            catch(IllegalStateException closed) {
                if(!"RECORD_ARCHIVE_CLOSED".equals(closed.getMessage()))throw closed;
            }
            TypeGraph.owner(surviving).node(TypeGraph.reference(surviving));
            try(var directory=detached.open()) {
                var rootValue=type(directory.model().get("declarations"));
                if(!expected.equals(TypeGraph.owner(rootValue).canonical(List.of(TypeGraph.reference(rootValue)))))
                    throw new AssertionError("detached directory depends on completed model");
            }
            session.releaseContextModels(Json.string(other,"context"));
            session.releaseContextModels(Json.string(other,"context"));
        }
        System.out.println("actual javac roots survive context release and close with the compiler task");
    }
}
