package com.anatomist.core;

import com.anatomist.json.Json;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;

final class AnalysisContextTest {
    @Test @SuppressWarnings("unchecked") void shared_rust_java_vectors() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("tests/fixtures/analysis-resources/ledger-vectors.json"))) root=root.getParent();
        assertNotNull(root);
        var vectors=(List<Map<String,Object>>)Json.parseTree(Files.readString(root.resolve("tests/fixtures/analysis-resources/ledger-vectors.json")));
        for(var vector:vectors) {
            var limits=(Map<String,Number>)vector.get("limits");
            var context=new AnalysisContext(new AnalysisContext.Limits(limits.get("max_work").longValue(),limits.get("max_nodes").longValue(),limits.get("max_payload_bytes").longValue(),limits.get("max_depth").intValue()));
            for(var operation:(List<Map<String,Object>>)vector.get("operations")) {
                try {
                    if(operation.containsKey("depth"))context.depth(((Number)operation.get("depth")).intValue());
                    else { var c=(List<Number>)operation.get("charge"); context.charge(c.get(0).longValue(),c.get(1).longValue(),c.get(2).longValue()); }
                } catch(FatalAnalysisFailure expected) { assertNotNull(context.usage().get("failure")); }
            }
            assertEquals(Json.writeCanonical(vector.get("expected")),Json.writeCanonical(context.usage()),vector.get("name").toString());
            if(context.usage().get("failure")!=null)assertThrows(FatalAnalysisFailure.class,()->context.charge(0,0,0));
        }
    }
    @Test void a_seed_resumes_and_does_not_reset_the_grant() {
        var limits=new AnalysisContext.Limits(10,10,100,3);
        var first=new AnalysisContext(limits,"task/java/worker",0,Map.of());first.charge(7,2,30);
        var next=new AnalysisContext(limits,"task/java/worker",0,first.usage());
        assertThrows(FatalAnalysisFailure.class,()->next.charge(4,1,0));
        assertEquals(7L,next.usage().get("work"));
    }
    @Test void cancel_and_deadline_are_distinct_sticky_failures() throws Exception {
        var cancelled=new AnalysisContext(AnalysisContext.Limits.defaults());cancelled.cancel();
        assertThrows(FatalAnalysisFailure.class,cancelled::check);assertEquals("Cancelled",cancelled.usage().get("failure"));
        var expired=new AnalysisContext(AnalysisContext.Limits.defaults(),"task/java/worker",1,Map.of());Thread.sleep(5);
        assertThrows(FatalAnalysisFailure.class,expired::check);expired.cancel();
        assertEquals("Deadline",expired.usage().get("failure"));
    }
    @Test void semantic_json_uses_the_supplied_task_budget() {
        var context=new AnalysisContext(new AnalysisContext.Limits(3,100,10000,10));
        assertThrows(FatalAnalysisFailure.class,()->Json.parseTree("[1,2,3,4]",512,context));
        assertThrows(FatalAnalysisFailure.class,()->Json.writeCompact(List.of(1),context));
    }

    @Test void extension_batches_reserve_atomically_and_cannot_refund_by_clearing() {
        var context=new AnalysisContext(new AnalysisContext.Limits(1000,9,100000,256));
        var result=new com.anatomist.model.ExtractionResult(context);
        result.nodes.add(new com.anatomist.model.Node());
        assertThrows(FatalAnalysisFailure.class,()->result.nodes.addAll(List.of(new com.anatomist.model.Node(),new com.anatomist.model.Node())));
        assertEquals(1,result.nodes.size());
        result.clearFacts();
        assertEquals(8L,context.usage().get("nodes"));
        assertThrows(FatalAnalysisFailure.class,()->result.nodes.add(new com.anatomist.model.Node()));
    }

    @Test void wire_counters_must_be_integers_and_all_grant_limits_are_required() {
        var limits=new HashMap<String,Object>(Map.of("max_work",10L,"max_nodes",10L,"max_payload_bytes",1000L,"max_depth",3L));
        var grant=Map.<String,Object>of("id","task/java/worker","language","java","limits",limits);
        var request=Map.<String,Object>of("analysis_grant",grant,"analysis_usage",Map.of("work",0L,"nodes",0L,"payload_bytes",0L,"max_depth",0L),"deadline_ms",0L);
        limits.put("max_work","10");assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(request));
        limits.put("max_work",10.0);assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(request));
        limits.put("max_work",10L);limits.remove("max_depth");assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(request));
    }

    @Test void ancestor_denial_is_atomic_for_root_parent_and_child() {
        var root=new AnalysisContext(new AnalysisContext.Limits(100,100,100000,256));
        var parent=root.childScope(new AnalysisContext.Limits(10,100,10000,256));
        var child=parent.childScope(new AnalysisContext.Limits(100,100,10000,256));
        var sibling=root.childScope(root.limits());
        child.charge(3,2,100);
        var before=List.of(root.scopeUsage(),parent.scopeUsage(),child.scopeUsage());
        assertThrows(HardResourceLimit.class,()->child.charge(8,1,1));
        var after=List.of(root.scopeUsage(),parent.scopeUsage(),child.scopeUsage());
        for(int n=0;n<before.size();n++)for(String dimension:List.of("work","nodes","payload_bytes"))assertEquals(before.get(n).get(dimension),after.get(n).get(dimension));
        assertThrows(HardResourceLimit.class,()->sibling.charge(0,0,0));
        assertEquals(root.usage().get("failure"),child.scopeUsage().get("failure"));
    }
    @Test void child_depth_cannot_raise_parent_or_task_limits() {
        var root=new AnalysisContext(new AnalysisContext.Limits(100,100,100000,3));
        var child=root.childScope(new AnalysisContext.Limits(1000,1000,1000000,1));
        assertEquals(1,child.limits().maxDepth());
        assertThrows(HardResourceLimit.class,()->child.depth(2));
        assertEquals(0,root.usage().get("max_depth"));
    }
    @Test void concurrent_siblings_share_one_atomic_root() throws Exception {
        var root=new AnalysisContext(new AnalysisContext.Limits(8,100,100000,256));
        var first=root.childScope(root.limits());var second=root.childScope(root.limits());
        var barrier=new java.util.concurrent.CyclicBarrier(2);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(var context:List.of(first,second))futures.add(executor.submit(()-> {
                try {barrier.await();while(true)context.charge(1,0,0);}
                catch(HardResourceLimit expected) {}
                catch(Exception failure) {throw new RuntimeException(failure);}
            }));
            for(var future:futures)future.get();
        }
        assertEquals(8L,root.usage().get("work"));
        assertEquals(6L,(long)first.scopeUsage().get("work")+(long)second.scopeUsage().get("work"));
    }
    @Test void production_requests_require_grant_seed_and_deadline() {
        assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(Map.of()));
        var limits=Map.of("max_work",100L,"max_nodes",100L,"max_payload_bytes",1000L,"max_depth",3L);
        var request=new HashMap<String,Object>();
        request.put("analysis_grant",Map.of("id","task/java/worker","language","java","limits",limits));
        assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(request));
        request.put("analysis_usage",Map.of("work",0L,"nodes",0L,"payload_bytes",0L,"max_depth",0L));
        assertThrows(IllegalArgumentException.class,()->AnalysisContext.fromRequest(request));
        request.put("deadline_ms",0L);
        assertEquals(0L,AnalysisContext.fromRequest(request).usage().get("work"));
    }

}
