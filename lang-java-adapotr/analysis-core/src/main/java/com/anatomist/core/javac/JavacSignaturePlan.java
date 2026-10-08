package com.anatomist.core.javac;

import com.anatomist.core.*;
import com.anatomist.core.java.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Session-owned module archives and bounded source declaration catalogues. */
final class JavacSignaturePlan implements AutoCloseable {
    record Input(
            JavacContexts.Context context,
            List<Path> classpath,
            List<JavaUnit> catalogues,
            int archivedModules,
            String fingerprint) {}

    private record Module(
            JavacSignatureCache.Artifact artifact,
            List<JavaUnit> catalogues,
            Set<Path> sources,
            long weight) {}

    private static final long MAX_CATALOGUE_WEIGHT = 256L * 1024 * 1024;
    private final long maximumCatalogueWeight;
    private final Path project;
    private final List<SourceRoot> roots;
    private final SourceIdentityResolver identities;
    private final Path cache;
    private final String environment;
    private final int release;
    private final boolean enabled;
    private final List<Path> externalClasspath;
    private final JavacMavenInputs mavenInputs;
    private final List<Path> verifiedClasspath;
    private final List<JavacSignatureInputs.FileInput> classpathIdentity;
    private final List<JavacSignatureInputs.FileInput> moduleIdentity;
    private final Map<String, Module> modules = new LinkedHashMap<>(16, .75f, true);
    private long weight;
    private final Map<String, Long> catalogueSizes = new HashMap<>();
    private final Map<Module, Integer> pinned = new IdentityHashMap<>();
    private int catalogueEvictions;
    private int catalogueReloads;
    private int restoredModules;
    private int archivesUsed;
    private int fallbackModules;
    private final Map<String, List<String>> fallbackReasons = new LinkedHashMap<>();
    private long sourceAppearances;
    private final Set<String> neededModules = new HashSet<>();
    private final Map<Path, String> snapshots = new LinkedHashMap<>();
    private final Map<Path, JavacSignatureInputs.FileInput> sourceInputs = new LinkedHashMap<>();
    private IndexTimings timings;
    private boolean captured;
    private final JavacSignatureCache.Session verifiedSignatures =
            new JavacSignatureCache.Session();

    void setTimings(IndexTimings value) {
        timings = value;
    }

    private Optional<List<JavaUnit>> loadCatalogue(JavacSignatureCache.Artifact artifact)
            throws IOException {
        long started = timings == null ? 0 : timings.start();
        try {
            return verifiedSignatures.catalogue(artifact);
        } finally {
            if (timings != null) timings.stop("javac_catalogue_load", started);
        }
    }

    JavacSignaturePlan(
            Path project,
            List<SourceRoot> roots,
            Path cache,
            String environment,
            int release,
            List<Path> classpath,
            boolean enabled,
            JavacMavenInputs mavenInputs)
            throws IOException {
        this(
                project,
                roots,
                cache,
                environment,
                release,
                classpath,
                enabled,
                mavenInputs,
                MAX_CATALOGUE_WEIGHT);
    }

    JavacSignaturePlan(
            Path project,
            List<SourceRoot> roots,
            Path cache,
            String environment,
            int release,
            List<Path> classpath,
            boolean enabled,
            JavacMavenInputs mavenInputs,
            long maximumCatalogueWeight)
            throws IOException {
        if (maximumCatalogueWeight <= 0 || maximumCatalogueWeight > MAX_CATALOGUE_WEIGHT)
            throw new IllegalArgumentException("invalid catalogue cache budget");
        this.maximumCatalogueWeight = maximumCatalogueWeight;
        this.project = project;
        this.roots = roots;
        this.identities = SourceIdentityResolver.fromRoots(project, roots);
        this.cache = cache;
        this.environment = environment;
        this.release = release;
        this.enabled = enabled;
        this.mavenInputs = mavenInputs;
        if (mavenInputs != null) mavenInputs.verify();
        externalClasspath = classpath;
        verifiedClasspath =
                mavenInputs != null && mavenInputs.resolvedClasspath()
                        ? mavenInputs.modules().stream()
                                .flatMap(
                                        module ->
                                                java.util.stream.Stream.concat(
                                                        module.classpath(SourceScope.MAIN).stream(),
                                                        module
                                                                .classpath(SourceScope.TEST)
                                                                .stream()))
                                .distinct()
                                .toList()
                        : classpath;
        classpathIdentity = enabled ? JavacSignatureInputs.files(verifiedClasspath) : List.of();
        moduleIdentity = enabled ? JavacSignatureInputs.files(modelFiles()) : List.of();
    }

    private List<Path> modelFiles() throws IOException {
        Set<Path> model = new TreeSet<>();
        if (mavenInputs != null) model.addAll(mavenInputs.modelInputs().keySet());
        for (SourceRoot root : roots) {
            for (Path directory = root.path();
                    directory != null;
                    directory = directory.getParent()) {
                for (String name : List.of("pom.xml", "lombok.config")) {
                    Path file = directory.resolve(name);
                    if (Files.isRegularFile(file)) model.add(file);
                }
                if (directory.equals(project.toAbsolutePath().normalize())) break;
            }
            if (Files.isDirectory(root.path()))
                try (var paths = Files.walk(root.path())) {
                    paths.filter(path -> path.getFileName().toString().equals("lombok.config"))
                            .filter(Files::isRegularFile)
                            .forEach(model::add);
                }
        }
        return List.copyOf(model);
    }

    List<JavacContexts.Context> capturedContexts(List<JavacContexts.Context> contexts) {
        captured = true;
        verifiedSignatures.capturedRoot(project);
        for (var context : contexts) {
            neededModules.addAll(context.dependencyModules());
            if (context.scope() == SourceScope.TEST) neededModules.add(context.module());
        }
        return List.copyOf(contexts);
    }

    List<JavacContexts.Context> contexts(List<Path> visible, List<Path> targets) {
        var requested = JavacContexts.prepare(project, roots, visible, targets, mavenInputs);
        if (!enabled) return requested;
        Set<String> prerequisites = new HashSet<>();
        for (var context : requested) {
            prerequisites.addAll(context.dependencyModules());
            if (context.scope() == SourceScope.TEST) prerequisites.add(context.module());
        }
        neededModules.addAll(prerequisites);
        Set<Path> expanded = new LinkedHashSet<>(targets);
        for (Path path : visible) {
            if (identities.compilationScope(path) != SourceScope.TEST
                    && prerequisites.contains(compilerModule(path))) expanded.add(path);
        }
        Set<Path> publish = Set.copyOf(targets);
        return JavacContexts.prepare(project, roots, visible, List.copyOf(expanded), mavenInputs)
                .stream()
                .map(
                        context ->
                                new JavacContexts.Context(
                                        context.module(),
                                        context.scope(),
                                        context.sources(),
                                        context.publish().stream()
                                                .filter(publish::contains)
                                                .collect(
                                                        java.util.stream.Collectors
                                                                .toUnmodifiableSet()),
                                        context.dependencyModules()))
                .toList();
    }

    private String compilerModule(Path path) {
        return JavacContexts.compilerModule(path, identities, mavenInputs);
    }

    Input input(JavacContexts.Context context) throws IOException {
        List<Path> selectedClasspath = classpath(context);
        if (!enabled) return new Input(context, selectedClasspath, List.of(), 0, "");
        String fingerprint = fingerprint(context);
        Set<String> dependencies = new TreeSet<>(context.dependencyModules());
        if (context.scope() == SourceScope.TEST) dependencies.add(context.module());
        Set<Path> omitted = new HashSet<>();
        List<Path> archives = new ArrayList<>();
        List<JavaUnit> catalogues = new ArrayList<>();
        List<Module> selectedModules = new ArrayList<>();
        try (var ignored = new CatalogueLease(selectedModules)) {
            for (String dependency : dependencies) {
                Module module = modules.get(dependency);
                if (module == null) continue;
                if (module.catalogues().isEmpty() && !module.sources().isEmpty()) {
                    var restored = loadCatalogue(module.artifact());
                    if (restored.isEmpty()) {
                        fallback(
                                dependency,
                                List.of(
                                        "persisted declaration catalogue is unavailable or corrupt"));
                        continue;
                    }
                    if (!retain(dependency, module.artifact(), restored.get())) continue;
                    module = modules.get(dependency);
                    catalogueReloads++;
                }
                // Detached headers retain their original source identity. Originals are checked
                // again at the session boundary, before any staged facts may be published.
                for (JavaUnit unit : module.catalogues()) remember(unit);
                long verificationStarted = timings == null ? 0 : timings.start();
                boolean valid = verifiedSignatures.archiveUnchanged(module.artifact());
                if (timings != null)
                    timings.stop("javac_signature_archive_verify", verificationStarted);
                if (!valid) continue;
                pinned.merge(module, 1, Integer::sum);
                selectedModules.add(module);
                omitted.addAll(module.sources());
                archives.add(module.artifact().archive());
                catalogues.addAll(module.catalogues());
            }
            archives.addAll(
                    selectedClasspath); // Source signatures precede stale installed reactor JARs.
            var selected =
                    new JavacContexts.Context(
                            context.module(),
                            context.scope(),
                            context.sources().stream()
                                    .filter(path -> !omitted.contains(path))
                                    .toList(),
                            context.publish(),
                            context.dependencyModules());
            return new Input(
                    selected,
                    List.copyOf(archives),
                    List.copyOf(catalogues),
                    archives.size() - selectedClasspath.size(),
                    fingerprint);
        }
    }

    boolean conflicts(Input input, List<JavaUnit> units) {
        if (input.archivedModules() == 0) return false;
        Set<String> names = new HashSet<>();
        for (JavaUnit unit : input.catalogues())
            for (JavaDeclaration declaration : unit.declarationInventory())
                if (!names.add(declaration.binaryName())) return true;
        for (JavaUnit unit : units)
            for (JavaDeclaration declaration : unit.declarationInventory())
                if (!declaration.binaryName().isEmpty() && !names.add(declaration.binaryName()))
                    return true;
        return false;
    }

    Input fullSource(JavacContexts.Context context) throws IOException {
        return new Input(
                context, classpath(context), List.of(), 0, enabled ? fingerprint(context) : "");
    }

    void discarded(Input input) {
        sourceAppearances += input.context().sources().size();
        fallback(
                input.context().module(),
                List.of("conflicting source binary names; reanalyzed complete source context"));
    }

    private void fallback(String module, List<String> reasons) {
        if (!fallbackReasons.containsKey(module)) fallbackModules++;
        fallbackReasons.put(module, List.copyOf(reasons));
        com.anatomist.core.logging.AnatomistLog.debug(
                "javac_signature_fallback module=" + module + " reasons=" + reasons);
    }

    void completed(
            JavacContexts.Context original, Input input, List<JavaUnit> units, String failure)
            throws IOException {
        archivesUsed += input.archivedModules();
        sourceAppearances += input.context().sources().size();
        for (JavaUnit unit : units) remember(unit);
        if (!enabled
                || original.scope() != SourceScope.MAIN
                || !neededModules.contains(original.module())) return;
        List<JavaUnit> own =
                units.stream()
                        .filter(unit -> compilerModule(unit.path()).equals(original.module()))
                        .toList();
        long expected =
                original.sources().stream()
                        .filter(path -> compilerModule(path).equals(original.module()))
                        .count();
        if (!failure.isEmpty() || own.size() != expected || hasLombok(own)) {
            fallback(
                    original.module(),
                    List.of(
                            !failure.isEmpty()
                                    ? failure
                                    : own.size() != expected
                                            ? "missing module source declarations"
                                            : "Lombok declaration overlay is missing or incomplete for source signatures"));
            return;
        }
        String fingerprint = fingerprint(original);
        if (!fingerprint.equals(input.fingerprint()))
            throw new IllegalArgumentException(
                    "signature inputs changed during analysis: " + original.module());
        var result = JavacSignatureCache.publish(cache, fingerprint, own, release, timings, captured ? project : null);
        if (result instanceof JavacSignatureCache.Unavailable unavailable) {
            fallback(original.module(), unavailable.reasons());
            return;
        }
        var ready = (JavacSignatureCache.Ready) result;
        verifiedSignatures.published(ready);
        var artifact = ready.artifact();
        List<JavaUnit> compact = own.stream().map(JavaDeclarationView::of).toList();
        retain(original.module(), artifact, compact);
    }

    boolean restore(JavacContexts.Context context) throws IOException {
        if (!enabled
                || context.scope() != SourceScope.MAIN
                || !context.publish().isEmpty()
                || !neededModules.contains(context.module())) return false;
        var artifact = JavacSignatureCache.load(cache, fingerprint(context), captured ? project : null);
        if (artifact.isEmpty() || artifact.get().release() != release) return false;
        var catalogue = loadCatalogue(artifact.get());
        if (catalogue.isEmpty()) return false;
        Set<Path> expected =
                context.sources().stream()
                        .filter(path -> compilerModule(path).equals(context.module()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<Path> found =
                catalogue.get().stream()
                        .map(JavaUnit::path)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!expected.equals(found)) return false;
        for (JavaUnit unit : catalogue.get()) {
            String old = snapshots.putIfAbsent(unit.path(), unit.sourceHash());
            if (old != null && !old.equals(unit.sourceHash()))
                throw new IllegalArgumentException("cached source changed between javac contexts");
        }
        boolean retained = retain(context.module(), artifact.get(), catalogue.get());
        if (retained) restoredModules++;
        return retained;
    }

    boolean canAdmit(JavacContexts.Context context) {
        Set<String> dependencies = new HashSet<>(context.dependencyModules());
        if (context.scope() == SourceScope.TEST) dependencies.add(context.module());
        long required = pinned.keySet().stream().mapToLong(Module::weight).sum();
        for (String name : dependencies) {
            Module module = modules.get(name);
            if (module != null && !pinned.containsKey(module))
                required += catalogueSizes.getOrDefault(name, module.weight());
        }
        return required <= maximumCatalogueWeight;
    }

    CatalogueLease pin(Input input) {
        Set<JavaUnit> selected = Collections.newSetFromMap(new IdentityHashMap<>());
        selected.addAll(input.catalogues());
        List<Module> retained =
                modules.values().stream()
                        .filter(module -> module.catalogues().stream().anyMatch(selected::contains))
                        .toList();
        retained.forEach(module -> pinned.merge(module, 1, Integer::sum));
        return new CatalogueLease(retained);
    }

    final class CatalogueLease implements AutoCloseable {
        private List<Module> retained;

        private CatalogueLease(List<Module> retained) {
            this.retained = retained;
        }

        @Override
        public void close() {
            if (retained == null) return;
            for (Module module : retained) {
                int count = pinned.get(module);
                if (count == 1) pinned.remove(module);
                else pinned.put(module, count - 1);
            }
            retained = null;
        }
    }

    private boolean retain(
            String name, JavacSignatureCache.Artifact artifact, List<JavaUnit> compact) {
        long weightStarted = timings == null ? 0 : timings.start();
        long retained = catalogueWeight(compact);
        catalogueSizes.put(name, retained);
        if (timings != null) timings.stop("javac_catalogue_weight", weightStarted);
        if (retained > maximumCatalogueWeight) {
            fallback(name, List.of("module declaration catalogue exceeds cache budget"));
            return false;
        }
        var previous = modules.get(name);
        if (previous != null && pinned.containsKey(previous))
            throw new IllegalStateException("cannot replace an in-flight declaration catalogue");
        if (previous != null) weight -= previous.weight();
        modules.put(
                name,
                new Module(
                        artifact,
                        List.of(),
                        compact.stream()
                                .map(JavaUnit::path)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        0));
        var iterator = modules.entrySet().iterator();
        while (weight + retained > maximumCatalogueWeight && iterator.hasNext()) {
            var entry = iterator.next();
            var oldest = entry.getValue();
            if (pinned.containsKey(oldest)) continue;
            weight -= oldest.weight();
            if (oldest.weight() > 0) catalogueEvictions++;
            entry.setValue(new Module(oldest.artifact(), List.of(), oldest.sources(), 0));
        }
        if (weight + retained > maximumCatalogueWeight) {
            fallback(name, List.of("in-flight declaration catalogues exhaust cache budget"));
            return false;
        }
        modules.put(
                name,
                new Module(
                        artifact,
                        compact,
                        compact.stream()
                                .map(JavaUnit::path)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                        retained));
        weight += retained;
        return true;
    }

    int catalogueEvictions() {
        return catalogueEvictions;
    }

    int catalogueReloads() {
        return catalogueReloads;
    }

    int restoredModules() {
        return restoredModules;
    }

    static long catalogueWeight(List<JavaUnit> compact) {
        long retained = 0;
        for (JavaUnit unit : compact)
            retained +=
                    4L
                                    * (unit.declarationInventory().toString().length()
                                            + unit.types().toString().length()
                                            + unit.extensions().toString().length()
                                            + unit.tree().toString().length())
                            + 256L
                                    * (unit.types().entries().size()
                                            + declarationCount(unit.declarationInventory()));
        return retained;
    }

    private static long declarationCount(List<JavaDeclaration> declarations) {
        long count = declarations.size();
        for (JavaDeclaration declaration : declarations)
            count += declarationCount(declaration.members());
        return count;
    }

    private static boolean hasLombok(List<JavaUnit> units) {
        for (JavaUnit unit : units)
            if (lombok(unit.declarationInventory())) {
                if (!unit.extensions().counters().containsKey("lombok_files_seen")) return true;
                // completed() separately requires successful compilation. Unmodeled method-body
                // capabilities do not invalidate processor-confirmed declaration signatures.
                if (unit.hasProcessorEvidence()
                        && !unit.extensions().counters().containsKey("lombok_processor_partial"))
                    continue;
                for (var declaration : unit.declarationInventory()) {
                    if (!ownLombok(declaration)) continue;
                    Object capabilities =
                            unit.extensions()
                                    .declarationMetadata()
                                    .getOrDefault(declaration.canonicalName(), Map.of())
                                    .get("lombok");
                    if (!(capabilities instanceof Map<?, ?> summary)
                            || !"complete".equals(summary.get("coverage"))) return true;
                }
            }
        return false;
    }

    static boolean lombok(List<JavaDeclaration> declarations) {
        for (JavaDeclaration declaration : declarations)
            if (ownLombok(declaration) || lombok(declaration.members())) return true;
        return false;
    }

    private static boolean ownLombok(JavaDeclaration declaration) {
        if (declaration.origin() == JavaDeclaration.Origin.SOURCE
                && declaration.annotations().stream()
                        .anyMatch(
                                annotation ->
                                        annotation.descriptor().startsWith("Llombok/")
                                                && !annotation
                                                        .descriptor()
                                                        .equals("Llombok/Generated;"))) return true;
        for (JavaDeclaration member : declaration.members()) {
            if (Set.of("CLASS", "INTERFACE", "ENUM", "ANNOTATION_TYPE", "RECORD")
                    .contains(member.symbol().kind())) continue;
            if (ownLombok(member)) return true;
        }
        return false;
    }

    int archivesUsed() {
        return archivesUsed;
    }

    int fallbackModules() {
        return fallbackModules;
    }

    Map<String, List<String>> fallbackReasons() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fallbackReasons));
    }

    long sourceAppearances() {
        return sourceAppearances;
    }

    private void remember(JavaUnit unit) {
        Path path = unit.path().toAbsolutePath().normalize();
        String old = snapshots.putIfAbsent(path, unit.sourceHash());
        var input = sourceInputs.get(path);
        if (old != null && !old.equals(unit.sourceHash())
                || input != null && !input.sha256().equals(unit.sourceHash()))
            throw new IllegalArgumentException("source changed between javac contexts: " + path);
    }

    void verifyInputs() throws IOException {
        verifiedSignatures.verifyInputs();
        if (mavenInputs != null) mavenInputs.verify();
        if (!List.copyOf(sourceInputs.values())
                .equals(JavacSignatureInputs.files(List.copyOf(sourceInputs.keySet()))))
            throw new IllegalArgumentException("source changed during javac session");
        for (var snapshot : snapshots.entrySet())
            if (!sourceInputs.containsKey(snapshot.getKey())
                    && !snapshot.getValue().equals(ContentHash.sha256(snapshot.getKey())))
                throw new IllegalArgumentException(
                        "source changed during javac session: " + snapshot.getKey());
        if (enabled
                && (!classpathIdentity.equals(JavacSignatureInputs.files(verifiedClasspath))
                        || !moduleIdentity.equals(JavacSignatureInputs.files(modelFiles()))))
            throw new IllegalArgumentException(
                    "classpath or module model changed during javac session");
    }

    private List<Path> classpath(JavacContexts.Context context) {
        return mavenInputs != null && mavenInputs.resolvedClasspath()
                ? mavenInputs.module(context.module()).classpath(context.scope())
                : externalClasspath;
    }

    List<JavacSignatureInputs.FileInput> classpathEvidence(JavacContexts.Context context)
            throws IOException {
        List<JavacSignatureInputs.FileInput> known =
                enabled ? classpathIdentity : JavacSignatureInputs.files(classpath(context));
        Map<String, JavacSignatureInputs.FileInput> byPath = new HashMap<>();
        known.forEach(item -> byPath.put(item.path(), item));
        return classpath(context).stream()
                .map(
                        path -> {
                            String normalized = path.toAbsolutePath().normalize().toString();
                            var entry = Objects.requireNonNull(byPath.get(normalized));
                            if (!entry.sha256().equals("directory")) return entry;
                            String prefix = normalized + java.io.File.separator;
                            String contents =
                                    known.stream()
                                            .filter(item -> item.path().startsWith(prefix))
                                            .map(item -> item.path() + "=" + item.sha256())
                                            .collect(java.util.stream.Collectors.joining("\n"));
                            return new JavacSignatureInputs.FileInput(
                                    normalized, ContentHash.sha256(contents));
                        })
                .toList();
    }

    private List<JavacSignatureInputs.FileInput> stableInputs(List<JavacSignatureInputs.FileInput> inputs) {
        if (!captured) return inputs;
        Path base = project.toAbsolutePath().normalize();
        return inputs.stream().map(input -> {
            Path path = Path.of(input.path()).toAbsolutePath().normalize();
            String name = path.startsWith(base) ? "capture:" + base.relativize(path).toString().replace('\\', '/') : input.path();
            return new JavacSignatureInputs.FileInput(name, input.sha256());
        }).toList();
    }

    private String fingerprint(JavacContexts.Context context) throws IOException {
        List<JavacSignatureInputs.FileInput> selected = classpathIdentity;
        if (mavenInputs != null && mavenInputs.resolvedClasspath()) {
            Map<String, JavacSignatureInputs.FileInput> files = new HashMap<>();
            classpathIdentity.forEach(file -> files.put(file.path(), file));
            selected =
                    classpath(context).stream()
                            .map(
                                    path ->
                                            Objects.requireNonNull(
                                                    files.get(
                                                            path.toAbsolutePath()
                                                                    .normalize()
                                                                    .toString())))
                            .toList();
        }
        long started = timings == null ? 0 : timings.start();
        try {
            List<JavacSignatureInputs.FileInput> sources = new ArrayList<>();
            for (Path source : context.sources()) {
                Path path = source.toAbsolutePath().normalize();
                var known = sourceInputs.get(path);
                if (known == null) {
                    known = JavacSignatureInputs.files(List.of(path)).getFirst();
                    sourceInputs.put(path, known);
                }
                sources.add(known);
            }
            return JavacSignatureInputs.fingerprint(
                    context, environment, release, selected, stableInputs(moduleIdentity), stableInputs(sources));
        } finally {
            if (timings != null) timings.stop("javac_signature_fingerprint", started);
        }
    }

    @Override
    public void close() {
        verifiedSignatures.close();
        modules.clear();
        snapshots.clear();
        sourceInputs.clear();
        neededModules.clear();
        weight = 0;
    }
}
