package com.anatomist.worker;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Test-only Java 8 entry, outside the production worker source tree. */
public final class CommentsProbe {
    public static void main(String[] args) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        final long limit = Long.parseLong(args[1]);
        final int cancelAfter = Integer.parseInt(args[2]);
        List<WorkerComments.Comment> comments = WorkerComments.scan(raw, Integer.parseInt(args[3]), new WorkerComments.Admission() {
            long bytes;
            int calls;
            public void charge(long work, long nodes, long requested) {
                if (++calls == cancelAfter) throw new IllegalStateException("COMMENT_CANCELLED");
                if (requested > limit - bytes) throw new IllegalStateException("COMMENT_BUDGET");
                bytes += requested;
            }
        });
        DataOutputStream out = new DataOutputStream(System.out);
        out.writeInt(comments.size());
        for (WorkerComments.Comment comment : comments) {
            text(out, comment.kind);
            out.writeInt(comment.start);
            out.writeInt(comment.end);
            text(out, comment.body);
            out.writeInt(comment.segments.size());
            for (WorkerComments.Segment segment : comment.segments) {
                out.writeInt(segment.bodyStart);
                out.writeInt(segment.bodyEnd);
                out.writeInt(segment.sourceStart);
                out.writeInt(segment.sourceEnd);
            }
        }
        out.flush();
    }
    private static void text(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
