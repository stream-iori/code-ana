package com.anatomist.core;

import java.util.*;

/** Task-owned logical admission. No global/thread-local state; releases never refund usage. */
public final class AnalysisContext {
    public record Limits(long maxWork, long maxNodes, long maxPayloadBytes, int maxDepth) {
        public Limits {
            if (maxWork <= 0 || maxNodes <= 0 || maxPayloadBytes <= 0 || maxDepth < 1 || maxDepth > 4096)
                throw new IllegalArgumentException("ANALYSIS_LIMITS_INVALID");
        }
        public static Limits defaults() { return new Limits(10000000, 1000000, 512L << 20, 256); }
    }
    private final Limits limits;
    private final AnalysisContext root;
    // Root is accounted separately; ancestors run from the outermost scope inward.
    private final List<AnalysisContext> ancestors;
    private final String grantId;
    private final long started;
    private final long durationNanos;
    private long work, nodes, payload;
    private int depth;
    private Object failure;
    private boolean cancelled;
    private String phase = "input_decode";

    public AnalysisContext(Limits limits) { this(limits, "diagnostic", 0, Map.of()); }
    public AnalysisContext(Limits limits, String grantId, long remainingMs, Map<String, Object> seed) {
        this.limits = Objects.requireNonNull(limits);
        this.root = this;
        this.ancestors = List.of();
        this.grantId = Objects.requireNonNull(grantId);
        this.started = System.nanoTime();
        if (grantId.isEmpty() || remainingMs < 0 || remainingMs > 86400000)
            throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        durationNanos = remainingMs == 0 ? Long.MAX_VALUE : remainingMs * 1000000;
        work = number(seed, "work", 0); nodes = number(seed, "nodes", 0);
        payload = number(seed, "payload_bytes", 0); depth = Math.toIntExact(number(seed, "max_depth", 0));
        if (work > limits.maxWork || nodes > limits.maxNodes || payload > limits.maxPayloadBytes || depth > limits.maxDepth || seed.get("failure") != null)
            throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
    }
    private AnalysisContext(AnalysisContext parent, Limits limits) {
        this.root = parent.root;
        this.limits = limits;
        this.grantId = root.grantId;
        this.started = root.started;
        this.durationNanos = root.durationNanos;
        var ancestors = new ArrayList<>(parent.ancestors);
        if (parent != root) ancestors.add(parent);
        this.ancestors = List.copyOf(ancestors);
    }
    public AnalysisContext childScope(Limits requested) {
        Objects.requireNonNull(requested);
        synchronized (root) {
            var local = new Limits(Math.min(limits.maxWork, requested.maxWork),
                    Math.min(limits.maxNodes, requested.maxNodes),
                    Math.min(limits.maxPayloadBytes, requested.maxPayloadBytes),
                    Math.min(limits.maxDepth, requested.maxDepth));
            charge(1, 1, 128L + 32L * ancestors.size());
            return new AnalysisContext(this, local);
        }
    }
    private static long number(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger))
            throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        try {
            long n = new java.math.BigDecimal(value.toString()).longValueExact();
            if (n < 0) throw new ArithmeticException();
            return n;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID", invalid); }
    }
    @SuppressWarnings("unchecked")
    public static AnalysisContext fromRequest(Map<String, Object> request) {
        Object raw = request.get("analysis_grant");
        if (raw == null) throw new IllegalArgumentException("ANALYSIS_GRANT_REQUIRED");
        if (!(request.get("analysis_usage") instanceof Map<?,?> seed)
                || !request.containsKey("deadline_ms"))
            throw new IllegalArgumentException("ANALYSIS_USAGE_REQUIRED");
        for (String field:List.of("work","nodes","payload_bytes","max_depth"))
            if (!seed.containsKey(field) || seed.get(field)==null) throw new IllegalArgumentException("ANALYSIS_USAGE_REQUIRED");
        if (!(raw instanceof Map<?, ?> grant) || !"java".equals(grant.get("language")) || !(grant.get("id") instanceof String id) || !(grant.get("limits") instanceof Map<?, ?> limits))
            throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        for (String field : List.of("max_work", "max_nodes", "max_payload_bytes", "max_depth"))
            if (!limits.containsKey(field)) throw new IllegalArgumentException("ANALYSIS_LIMITS_INVALID");
        return new AnalysisContext(new Limits(number(limits,"max_work",0), number(limits,"max_nodes",0), number(limits,"max_payload_bytes",0), Math.toIntExact(number(limits,"max_depth",0))),
                id, number(request,"deadline_ms",0), (Map<String,Object>)seed);
    }
    public Limits limits() { return limits; }
    public void cancel() { synchronized(root) { root.cancelled = true; } }
    public void phase(String value) { synchronized(root) { check(); root.phase = Objects.requireNonNull(value); } }
    public void check() { synchronized(root) {
        if (root.failure == null) {
            if (root.cancelled || Thread.currentThread().isInterrupted()) root.failure = "Cancelled";
            else if (System.nanoTime() - root.started >= root.durationNanos) root.failure = "Deadline";
        }
        if (root.failure instanceof String kind) throw new FatalAnalysisFailure(kind.equals("Deadline") ? "TIMEOUT" : "CANCELLED");
        if (root.failure != null) throw new HardResourceLimit("ANALYSIS_RESOURCE_LIMIT: " + root.failure);
    } }
    private void limit(String resource, long maximum, long used, long requested) {
        root.failure = Map.of("Limit", Map.of("resource",resource,"limit",maximum,"used",used,"requested",requested));
        check();
    }
    public void deny(String resource, long maximum, long used, long requested) { synchronized(root) {
        check();
        long cap = switch(resource) { case "work" -> limits.maxWork; case "nodes" -> limits.maxNodes; case "payload_bytes" -> limits.maxPayloadBytes; case "depth" -> limits.maxDepth; default -> throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID"); };
        if(maximum<=0 || maximum>cap || used<0 || used>maximum || requested<=maximum-used) throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        limit(resource, maximum, used, requested);
    } }
    private void capacity(long moreWork,long moreNodes,long morePayload) {
        if (moreWork > limits.maxWork - work) limit("work",limits.maxWork,work,moreWork);
        if (moreNodes > limits.maxNodes - nodes) limit("nodes",limits.maxNodes,nodes,moreNodes);
        if (morePayload > limits.maxPayloadBytes - payload) limit("payload_bytes",limits.maxPayloadBytes,payload,morePayload);
    }
    private void add(long moreWork,long moreNodes,long morePayload) { work+=moreWork;nodes+=moreNodes;payload+=morePayload; }
    public void charge(long moreWork, long moreNodes, long morePayload) { synchronized(root) {
        check();
        if (moreWork < 0 || moreNodes < 0 || morePayload < 0) throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        root.capacity(moreWork,moreNodes,morePayload);
        for(var parent:ancestors)parent.capacity(moreWork,moreNodes,morePayload);
        if(this!=root)capacity(moreWork,moreNodes,morePayload);
        root.add(moreWork,moreNodes,morePayload);
        for(var parent:ancestors)parent.add(moreWork,moreNodes,morePayload);
        if(this!=root)add(moreWork,moreNodes,morePayload);
    } }
    public void depth(int value) { depth(value, limits.maxDepth); }
    public void depth(int value,int localMaximum) { synchronized(root) {
        check();
        if (value < 0 || localMaximum<1) throw new IllegalArgumentException("ANALYSIS_USAGE_INVALID");
        int cap=Math.min(limits.maxDepth,localMaximum);
        if (value > cap) limit("depth",cap,cap,(long)value-cap);
        root.depth = Math.max(root.depth,value);
        for(var parent:ancestors)parent.depth=Math.max(parent.depth,value);
        if(this!=root)depth=Math.max(depth,value);
    } }
    private Map<String,Object> counters() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("work",work); result.put("nodes",nodes); result.put("payload_bytes",payload);
        result.put("max_depth",depth); result.put("failure",root.failure); return result;
    }
    public Map<String,Object> usage() { synchronized(root) { return root.counters(); } }
    public Map<String,Object> scopeUsage() { synchronized(root) { return counters(); } }
    public Map<String,Object> receipt() { synchronized(root) {
        return Map.of("grant_id",root.grantId,"usage",root.counters(),"phase",root.phase);
    } }
}
