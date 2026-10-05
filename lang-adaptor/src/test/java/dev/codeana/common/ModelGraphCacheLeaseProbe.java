package dev.codeana.common;

import java.nio.file.*;
import java.util.*;

/** Snapshot caches must not accumulate for the whole task; escaped roots keep their leases. */
public final class ModelGraphCacheLeaseProbe {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        boolean release = args.length == 1;
        List<Map<String,Object>> nodes = new ArrayList<Map<String,Object>>();
        for (int i=0;i<512;i++) nodes.add(Json.object("kind","DECLARED","complete",true,
                "element","p.Type"+i,"display",String.join("",Collections.nCopies(1024,"x")),
                "arguments",Arrays.asList((i+1)%512),"enclosing",-1));
        TypeGraph original = new TypeGraph(nodes);
        List<Map<String,Object>> retained = new ArrayList<Map<String,Object>>();
        try (RecordArchive owner = RecordArchive.create(root.resolve("owner.bin"), 1L<<20, 10)) {
            for (int i=0;i<80;i++) {
                ModelArchive.RowSpool spool = new ModelArchive.RowSpool(root,"snapshot",4L<<20);
                owner.own(spool);
                spool.add(Json.object("type",original.view(0)));
                spool.seal();
                retained.add(Json.map(Json.map(spool.get(0)).get("type")));
                if (release) owner.releaseNodeCaches();
            }
            for (Map<String,Object> view : retained) {
                TypeGraph graph = TypeGraph.owner(view);
                if (!"p.Type511".equals(graph.node(511).get("element")))
                    throw new AssertionError("released archive lease or lost rows");
                if (release) owner.releaseNodeCaches();
            }
        }
        for (Map<String,Object> view : retained) {
            try { view.get("element"); throw new AssertionError("task lease leaked"); }
            catch (IllegalStateException expected) {
                if (!"RECORD_ARCHIVE_CLOSED".equals(expected.getMessage())) throw expected;
            }
        }
        System.out.println("80 snapshot graph leases survive cache release in 32MiB heap and close with task");
    }
}
