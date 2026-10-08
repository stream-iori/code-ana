package com.anatomist.config;

import com.anatomist.core.ExternalTypePolicy;
import com.anatomist.core.SourceScope;

import java.nio.file.Path;
import java.util.*;

public class ProjectConfig implements ExternalTypePolicy {

    public com.anatomist.core.javac.JavacOptions javacOptions() {
        return new com.anatomist.core.javac.JavacOptions(
                javaHome == null ? null : Path.of(javaHome),
                javacExecution == JavacExecution.TOOLCHAIN,
                vmClasspath,
                lombokCompile(),
                lombokStrict,
                StoragePaths.home().resolve("javac"),
                javacAnalysisThreads,
                javacWorkerHeapMb);
    }

    private int javacAnalysisThreads = 1;
    private int javacWorkerHeapMb = 1024;

    public int javacAnalysisThreads() {
        return javacAnalysisThreads;
    }

    public int javacWorkerHeapMb() {
        return javacWorkerHeapMb;
    }

    public void setJavacAnalysisThreads(int value) {
        if (value < 1 || value > 8)
            throw new IllegalArgumentException("javac_analysis_threads must be between 1 and 8");
        javacAnalysisThreads = value;
    }

    public void setJavacWorkerHeapMb(int value) {
        javacWorkerHeapMb = positive(value, "javac_worker_heap_mb");
    }

    private int fullBatchFiles = 32;
    private int fullBatchFacts = 10_000;
    private int incrementalBatchFiles = 128;
    private int sourceCacheEntries;
    private int combinedCacheEntries;
    private long asmCacheMaxBytes = 256L * 1024 * 1024;
    private long diffSpillBytes = 8L * 1024 * 1024;

    public int fullBatchFiles() {
        return fullBatchFiles;
    }

    public int fullBatchFacts() {
        return fullBatchFacts;
    }

    public int incrementalBatchFiles() {
        return incrementalBatchFiles;
    }

    public int sourceCacheEntries() {
        return sourceCacheEntries;
    }

    public int combinedCacheEntries() {
        return combinedCacheEntries;
    }

    public long asmCacheMaxBytes() {
        return asmCacheMaxBytes;
    }

    public long diffSpillBytes() {
        return diffSpillBytes;
    }

    public void setFullBatchFiles(int value) {
        fullBatchFiles = positive(value, "full_batch_files");
    }

    public void setFullBatchFacts(int value) {
        fullBatchFacts = positive(value, "full_batch_facts");
    }

    public void setIncrementalBatchFiles(int value) {
        incrementalBatchFiles = positive(value, "incremental_batch_files");
    }

    public void setSourceCacheEntries(int value) {
        sourceCacheEntries = nonnegative(value, "source_cache_entries");
    }

    public void setCombinedCacheEntries(int value) {
        combinedCacheEntries = nonnegative(value, "combined_cache_entries");
    }

    public void setAsmCacheMaxBytes(long value) {
        asmCacheMaxBytes = positive(value, "asm_cache_max_bytes");
    }

    public void setDiffSpillBytes(long value) {
        diffSpillBytes = positive(value, "diff_spill_bytes");
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static int nonnegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must be >= 0");
        return value;
    }

    private ObservabilityMode observabilityMode = ObservabilityMode.BASIC;

    public ObservabilityMode observabilityMode() {
        return observabilityMode;
    }

    public void setObservabilityMode(String value) {
        observabilityMode = ObservabilityMode.parse(value);
    }

    private List<String> captureIncludeIgnored = List.of();
    private boolean versionsAutoGc = true, versionsIncludeCaches;
    private int versionsKeep = 20, versionsMaxAgeDays = 30;
    private long versionsMaxBytes;

    public List<String> captureIncludeIgnored() {
        return captureIncludeIgnored;
    }

    public void setCaptureIncludeIgnored(List<String> value) {
        captureIncludeIgnored = List.copyOf(value);
    }

    public boolean versionsAutoGc() {
        return versionsAutoGc;
    }

    public void setVersionsAutoGc(boolean value) {
        versionsAutoGc = value;
    }

    public boolean versionsIncludeCaches() {
        return versionsIncludeCaches;
    }

    public void setVersionsIncludeCaches(boolean value) {
        versionsIncludeCaches = value;
    }

    public int versionsKeep() {
        return versionsKeep;
    }

    public void setVersionsKeep(int value) {
        if (value < 0) throw new IllegalArgumentException("keep must be >= 0");
        versionsKeep = value;
    }

    public int versionsMaxAgeDays() {
        return versionsMaxAgeDays;
    }

    public void setVersionsMaxAgeDays(int value) {
        if (value < 0) throw new IllegalArgumentException("max_age_days must be >= 0");
        versionsMaxAgeDays = value;
    }

    public long versionsMaxBytes() {
        return versionsMaxBytes;
    }

    public void setVersionsMaxBytes(long value) {
        if (value < 0) throw new IllegalArgumentException("max_bytes must be >= 0");
        versionsMaxBytes = value;
    }

    private Integer javaVersion;
    private ParserBackend parserBackend = ParserBackend.JAVAC;
    private JavacExecution javacExecution = JavacExecution.TOOLCHAIN;
    private String javaHome;
    private Map<String, String> parserEnvironment = Map.of();

    public Map<String, String> parserEnvironment() {
        return parserEnvironment;
    }

    public void setParserEnvironment(Map<String, String> values) {
        parserEnvironment = Map.copyOf(values);
    }

    public ParserBackend parserBackend() {
        return parserBackend;
    }

    public void setParserBackend(String value) {
        parserBackend = ParserBackend.parse(value);
    }

    public JavacExecution javacExecution() {
        return javacExecution;
    }

    public void setJavacExecution(String value) {
        javacExecution = JavacExecution.parse(value);
    }

    public String javaHome() {
        return javaHome;
    }

    public void setJavaHome(String value) {
        javaHome = absoluteJavaHome(value);
    }

    public boolean lombokCompile() {
        return lombokMode == LombokMode.COMPILE;
    }

    private static String absoluteJavaHome(String value) {
        if (value == null || value.isBlank() || !java.nio.file.Path.of(value).isAbsolute())
            throw new IllegalArgumentException("java_home must be an absolute path");
        return value;
    }

    private boolean springXml = false;
    private boolean vmClasspath = true;
    private LombokMode lombokMode = LombokMode.OFF;
    private boolean lombokStrict;

    private List<SourceScope> scanScopes = List.of(SourceScope.MAIN, SourceScope.GENERATED);
    private boolean scanScopesConfigured;
    private List<String> scanIncludes = List.of("**");
    private List<String> scanExcludes = List.of();
    private List<String> scanTextOnly = List.of();

    public List<String> scanTextOnly() {
        return scanTextOnly;
    }

    public void setScanTextOnly(List<String> value) {
        scanTextOnly = List.copyOf(value);
    }

    private List<String> searchTextIncludes = List.of();

    public List<String> searchTextIncludes() {
        return searchTextIncludes;
    }

    public void setSearchTextIncludes(List<String> value) {
        searchTextIncludes = List.copyOf(value);
    }

    private List<String> sourceRootSpecs = List.of();

    private List<String> externalExcludePatterns =
            List.of(
                    "java.lang.*",
                    "java.io.*",
                    "java.net.*",
                    "java.nio.*",
                    "java.math.*",
                    "sun.*",
                    "com.sun.*",
                    "jdk.*");
    private List<String> sourceSpelledExcludePatterns = List.of();

    public Integer javaVersion() {
        return javaVersion;
    }

    public boolean hasJavaVersion() {
        return javaVersion != null;
    }

    public void setJavaVersion(int v) {
        this.javaVersion = v;
    }

    public boolean springXml() {
        return springXml;
    }

    public void setSpringXml(boolean v) {
        this.springXml = v;
    }

    public boolean vmClasspath() {
        return vmClasspath;
    }

    public void setVmClasspath(boolean v) {
        this.vmClasspath = v;
    }

    public LombokMode lombokMode() {
        return lombokMode;
    }

    public void setLombokMode(String value) {
        this.lombokMode = LombokMode.parse(value);
    }

    public boolean lombokStrict() {
        return lombokStrict;
    }

    public void setLombokStrict(boolean value) {
        this.lombokStrict = value;
    }

    private List<com.anatomist.core.SourceRoot> resolvedResourceRoots;

    public List<com.anatomist.core.SourceRoot> resolvedResourceRoots() {
        return resolvedResourceRoots;
    }

    public void setResolvedResourceRoots(List<com.anatomist.core.SourceRoot> roots) {
        resolvedResourceRoots = roots == null ? null : List.copyOf(roots);
    }

    public List<SourceScope> scanScopes() {
        return scanScopes;
    }

    public boolean scanScopesConfigured() {
        return scanScopesConfigured;
    }

    public void setScanScopes(List<SourceScope> value) {
        this.scanScopes = value == null ? List.of() : List.copyOf(value);
        this.scanScopesConfigured = true;
    }

    public List<String> scanIncludes() {
        return scanIncludes;
    }

    public void setScanIncludes(List<String> value) {
        this.scanIncludes = value == null || value.isEmpty() ? List.of("**") : List.copyOf(value);
    }

    public List<String> scanExcludes() {
        return scanExcludes;
    }

    public void setScanExcludes(List<String> value) {
        this.scanExcludes = value == null ? List.of() : List.copyOf(value);
    }

    public List<String> sourceRootSpecs() {
        return sourceRootSpecs;
    }

    public void setSourceRootSpecs(List<String> value) {
        this.sourceRootSpecs = value == null ? List.of() : List.copyOf(value);
    }

    public List<String> externalExcludePatterns() {
        return externalExcludePatterns;
    }

    public void setExternalExcludePatterns(List<String> v) {
        this.externalExcludePatterns = List.copyOf(v);
        this.sourceSpelledExcludePatterns = List.copyOf(v);
    }

    public void addExternalExcludePatterns(List<String> extra) {
        var merged = new ArrayList<>(externalExcludePatterns);
        for (String p : extra) {
            if (!merged.contains(p)) merged.add(p);
        }
        this.externalExcludePatterns = List.copyOf(merged);
        var explicit = new ArrayList<>(sourceSpelledExcludePatterns);
        for (String pattern : extra) {
            if (!explicit.contains(pattern)) explicit.add(pattern);
        }
        this.sourceSpelledExcludePatterns = List.copyOf(explicit);
    }

    @Override
    public boolean isSourceSpelledExcluded(String fqn) {
        if (fqn == null) return true;
        for (String pattern : sourceSpelledExcludePatterns) {
            if (matchesPattern(pattern, fqn)) return true;
        }
        return false;
    }

    @Override
    public boolean isExternalExcluded(String fqn) {
        if (fqn == null) return true;
        for (String pattern : externalExcludePatterns) {
            if (matchesPattern(pattern, fqn)) return true;
        }
        return false;
    }

    static boolean matchesPattern(String pattern, String fqn) {
        if (pattern.endsWith(".*")) {
            String prefix = pattern.substring(0, pattern.length() - 1);
            return fqn.startsWith(prefix);
        }
        if (pattern.endsWith(".**")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return fqn.startsWith(prefix);
        }
        return fqn.equals(pattern);
    }
}
