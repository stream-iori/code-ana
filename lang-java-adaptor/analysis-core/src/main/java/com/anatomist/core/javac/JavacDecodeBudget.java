package com.anatomist.core.javac;

import java.io.*;

/** Conservative allocation admission, not an exact JVM heap measurement. Shared by all frames. */
final class JavacDecodeBudget {
    static final long DEFAULT_BYTES = 512L * 1024 * 1024;

    enum Section {
        METADATA,
        SOURCE_TREE,
        SEMANTICS,
        DECLARATIONS,
        DIAGNOSTICS
    }

    record Usage(
            long metadata, long sourceTree, long semantics, long declarations, long diagnostics) {
        static final Usage EMPTY = new Usage(0, 0, 0, 0, 0);

        long total() {
            return metadata + sourceTree + semantics + declarations + diagnostics;
        }

        java.util.Map<String, Object> toMap() {
            return java.util.Map.of(
                    "total",
                    total(),
                    "metadata",
                    metadata,
                    "source_tree",
                    sourceTree,
                    "semantics",
                    semantics,
                    "declarations",
                    declarations,
                    "diagnostics",
                    diagnostics);
        }
    }

    private final long maximum;
    private final com.anatomist.core.AnalysisContext analysis;
    private final long[] used = new long[Section.values().length];
    private Section section = Section.METADATA;
    private String path = "";
    private long remaining;

    void section(Section value, String source) {
        section = value;
        path = source;
    }

    Usage usage() {
        return new Usage(used[0], used[1], used[2], used[3], used[4]);
    }

    JavacDecodeBudget(long maximum) { this(maximum, null); }
    JavacDecodeBudget(long maximum, com.anatomist.core.AnalysisContext analysis) {
        this.analysis = analysis == null ? null : analysis.childScope(new com.anatomist.core.AnalysisContext.Limits(
                analysis.limits().maxWork(),analysis.limits().maxNodes(),Math.min(maximum,analysis.limits().maxPayloadBytes()),analysis.limits().maxDepth()));
        this.maximum = maximum;
        remaining = maximum;
    }

    static final class Input extends DataInputStream {
        private final JavacDecodeBudget budget;

        Input(InputStream input, JavacDecodeBudget budget) {
            super(input);
            this.budget = budget;
        }
    }

    static DataInputStream frame(InputStream input, DataInputStream parent) {
        return parent instanceof Input bounded
                ? new Input(input, bounded.budget)
                : new DataInputStream(input);
    }

    static void depth(DataInputStream input, int depth, int localMaximum) {
        if (input instanceof Input bounded && bounded.budget.analysis != null)
            bounded.budget.analysis.depth(depth, localMaximum);
    }
    static void count(DataInputStream input, int count) throws IOException {
        take(input,64L*count,1L+count,1L+count);
    }
    static void take(DataInputStream input,long bytes) throws IOException { take(input,bytes,1,1); }
    private static void take(DataInputStream input, long bytes, long work, long nodes) throws IOException {
        JavacCancellation.check();
        if (bytes < 0) throw new IOException("invalid javac allocation size");
        if (input instanceof Input bounded) {
            var budget=bounded.budget;
            String reason="JAVAC_DECODE_BUDGET_EXCEEDED: file="+budget.path+" section="+budget.section+" used="+(budget.maximum-budget.remaining)+" requested="+bytes+" limit="+budget.maximum;
            if(budget.analysis!=null) {
                try { budget.analysis.charge(work,nodes,bytes); }
                catch(com.anatomist.core.HardResourceLimit denied) { throw new com.anatomist.core.HardResourceLimit(reason,denied); }
            } else if(bytes>budget.remaining) throw new IOException(reason);
            budget.remaining-=bytes;
            budget.used[budget.section.ordinal()]+=bytes;
        }
    }
}
