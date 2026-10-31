package com.anatomist.core;

/** A file-owned ledger shared by SAX construction and graph projection. */
public final class XmlResourceBudget {
    private static final long LIMIT = 64L << 20;
    private final AnalysisContext analysis;
    private final AnalysisContext parsing, emitting;
    public XmlResourceBudget() { this(null); }
    public XmlResourceBudget(AnalysisContext parent) {
        analysis = parent == null ? null : parent.childScope(new AnalysisContext.Limits(parent.limits().maxWork(),parent.limits().maxNodes(),Math.min(LIMIT,parent.limits().maxPayloadBytes()),Math.min(256,parent.limits().maxDepth())));
        parsing = analysis == null ? null : analysis.childScope(new AnalysisContext.Limits(analysis.limits().maxWork(),Math.min(100000,analysis.limits().maxNodes()),analysis.limits().maxPayloadBytes(),analysis.limits().maxDepth()));
        emitting = analysis == null ? null : analysis.childScope(new AnalysisContext.Limits(analysis.limits().maxWork(),Math.min(100000,analysis.limits().maxNodes()),analysis.limits().maxPayloadBytes(),analysis.limits().maxDepth()));
    }
    public AnalysisContext analysisContext() { return analysis; }
    private HardResourceLimit failure;
    private HardResourceLimit fail(String reason) {
        if (failure == null) failure = new HardResourceLimit(reason);
        return failure;
    }
    public void check() { if (analysis != null) analysis.check(); if (failure != null) throw failure; }
    private long payload;
    private int parsedNodes;
    private int emittedNodes;

    public void platformDepthExceeded(int acceptedDepth) {
        check();
        if(analysis!=null && acceptedDepth>0 && acceptedDepth<=analysis.limits().maxDepth())
            analysis.deny("depth",acceptedDepth,acceptedDepth,1);
    }
    public void parsedNode(int depth) {
        check();
        if (parsing != null) { parsing.depth(depth); parsing.charge(1, 1, 96); }
        if (depth > 256 || ++parsedNodes > 100000) throw fail("XML parse node/depth limit");
    }
    public void emittedNode(int depth) {
        check();
        if (emitting != null) { emitting.depth(depth); emitting.charge(1, 1, 256); }
        if (depth > 256 || ++emittedNodes > 100000) throw fail("XML extraction node/depth limit");
    }
    public void bytes(long bytes) {
        check();
        account(0, bytes);
    }
    private void account(long nodes,long bytes) {
        check();
        if(bytes<0) throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        if(analysis!=null)analysis.charge(1,nodes,bytes);
        else if(bytes>LIMIT-payload)throw fail("XML payload exceeds 64 MiB");
        payload+=bytes;
    }
    public void text(CharSequence value) {
        if (value == null) return;
        long size = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) size++;
            else if (c < 2048) size += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) { size += 4; i++; }
            else size += 3;
        }
        bytes(size);
    }
    /** Reserve escaped JSON strings and framing before the metadata writer allocates. */
    private record Pending(Object value,int depth) {}
    public void json(Object value) {
        var pending=new java.util.ArrayDeque<Pending>();
        int[] visited={0};
        java.util.function.BiConsumer<Object,Integer> push=(item,depth)-> {
            if(item==null)return;
            check();
            if(analysis!=null)analysis.depth(depth);
            else if(depth>256)throw fail("XML metadata depth limit");
            if(visited[0]>=100000) {
                if(analysis!=null)analysis.deny("nodes",Math.min(100000,analysis.limits().maxNodes()),Math.min(100000,analysis.limits().maxNodes()),1);
                throw fail("XML metadata node limit");
            }
            long bytes=item instanceof CharSequence chars ? 32L+6L*chars.length() : 32;
            account(1,bytes);
            visited[0]++;
            pending.push(new Pending(item,depth));
        };
        push.accept(value,1);
        while(!pending.isEmpty()) {
            var frame=pending.pop();
            if(frame.value() instanceof java.util.Map<?,?> map) {
                for(var entry:map.entrySet()) { push.accept(entry.getKey(),frame.depth()+1);push.accept(entry.getValue(),frame.depth()+1); }
            } else if(frame.value() instanceof Iterable<?> items) {
                for(Object child:items)push.accept(child,frame.depth()+1);
            }
        }
    }
    // SAX may split surrogate pairs across callbacks. Carry the high surrogate into the next chunk.
    private boolean pendingHigh;
    public void characters(char[] chars, int start, int length) {
        long size = 0;
        for (int i = start; i < start + length; i++) {
            char c = chars[i];
            if (pendingHigh) { pendingHigh = false; if (Character.isLowSurrogate(c)) { size++; continue; } }
            if (Character.isHighSurrogate(c)) { size += 3; pendingHigh = true; }
            else size += c < 128 ? 1 : c < 2048 ? 2 : 3;
        }
        bytes(size);
    }
}
