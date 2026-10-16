package com.anatomist.core.javac;

import static com.anatomist.core.javac.JavacBackend.*;

import com.anatomist.core.*;
import com.anatomist.core.java.JavaAnalysisContext;
import com.anatomist.core.java.JavaDiagnostic;
import com.anatomist.core.java.JavaSyntax;
import com.anatomist.core.java.JavaUnit;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Decodes and validates one attempt, then consumes its detached model. */
final class JavacModelConsumer {
    private final JavacBackend backend;

    JavacModelConsumer(JavacBackend backend) {
        this.backend = backend;
    }

    boolean consumeAttempt(
            JavacAnalysisPlanner.Task task,
            JavacAnalysisAttempt attempt,
            JavacSignaturePlan signatures,
            java.util.function.Consumer<JavaAnalysisContext> consumer,
            AnalysisInventory inventory)
            throws IOException {
        JavacCancellation.check();
        ModelBudget budget =
                backend.modelBudgets.get(task.context().module() + "@" + task.context().scope());
        if (budget != null && Files.size(attempt.request().result()) > budget.bytes())
            throw new IOException("JAVA_MODEL_ARCHIVE_BYTES");
        List<JavaUnit> units = new ArrayList<>();
        final List<JavaUnit> decodedUnits = units;
        long[] records = {0};
        java.util.function.Consumer<JavaUnit> collect =
                unit -> {
                    if (budget != null) {
                        records[0] +=
                                new JavaSyntax(unit.tree()).nodes().size()
                                        + unit.bindings().size()
                                        + unit.types().entries().size();
                        var declarations = new ArrayDeque<>(unit.declarationInventory());
                        while (!declarations.isEmpty()) {
                            records[0]++;
                            declarations.addAll(declarations.removeFirst().members());
                        }
                        if (records[0] > budget.records())
                            throw new IllegalArgumentException("JAVA_MODEL_RECORD_LIMIT");
                    }
                    decodedUnits.add(unit);
                };
        JavacProtocol.Result response;
        if (attempt.received() != null) {
            response =
                    JavacProtocol.response(
                            attempt.received(),
                            collect,
                            attempt.declarations(),
                            backend.decodeBudgetBytes);
        } else {
            try (InputStream input = Files.newInputStream(attempt.request().result())) {
                response =
                        JavacProtocol.response(
                                input,
                                collect,
                                attempt.declarations(),
                                0,
                                true,
                                backend.decodeBudgetBytes);
            }
        }
        if (backend.timings != null) response.timingsNanos().forEach(backend.timings::addNanos);
        String compileFailure = "";
        boolean compiled = attempt.processing() && response.compileSucceeded();
        if (backend.config.lombokCompile()) {
            boolean missingProcessor =
                    !attempt.processing()
                            && (units.stream()
                                            .anyMatch(
                                                    unit ->
                                                            JavacSignaturePlan.lombok(
                                                                    unit.declarationInventory()))
                                    || response.diagnostics().stream()
                                            .anyMatch(
                                                    d ->
                                                            d.kind().equals("ERROR")
                                                                    && d.message()
                                                                            .contains("lombok")));
            if (missingProcessor || attempt.processing() && !compiled) {
                compileFailure =
                        missingProcessor
                                ? "Lombok processor is unavailable"
                                : "Lombok compiler failed: "
                                        + response.failure()
                                        + " "
                                        + response.diagnostics().stream()
                                                .filter(d -> d.kind().equals("ERROR"))
                                                .limit(5)
                                                .map(JavaDiagnostic::message)
                                                .toList();
                if (attempt.processing()) {
                    var attempted =
                            units.stream()
                                    .filter(
                                            unit ->
                                                    unit.hasProcessorEvidence()
                                                            && backend.declarationOnlySource(unit))
                                    .toList();
                    var failed = response;
                    units.clear();
                    attempt.declarations().close();
                    response =
                            backend.execute(
                                    task, attempt.input(), 1, false, null, null, null, units::add);
                    backend.restorePartialProcessorDeclarations(attempted, failed, units);
                }
            }
            if (compiled && !attempt.cached()) {
                Files.copy(attempt.request().result(), attempt.directory().resolve("analysis.bin"));
                Files.createDirectories(attempt.compilationTarget().getParent());
                JavacCompilationCache.publish(attempt.directory(), attempt.compilationTarget());
            }
        }
        if (compiled)
            units =
                    new ArrayList<>(
                            JavacCompiledDeclarations.merge(attempt.compilationTarget(), units));
        if (signatures.conflicts(attempt.input(), units)) {
            signatures.discarded(attempt.input());
            return false;
        }
        processContext(
                task,
                attempt.input(),
                units,
                response,
                compileFailure,
                signatures,
                consumer,
                inventory);
        if (!attempt.processing() || compiled) attempt.declarations().commit();
        return true;
    }

    void processContext(
            JavacAnalysisPlanner.Task task,
            JavacSignaturePlan.Input compilerInput,
            List<JavaUnit> units,
            JavacProtocol.Result response,
            String compileFailure,
            JavacSignaturePlan signatures,
            java.util.function.Consumer<JavaAnalysisContext> consumer,
            AnalysisInventory inventory)
            throws IOException {
        JavacCancellation.check();
        var context = task.context();
        Set<Path> received = new HashSet<>();
        for (JavaUnit unit : units) {
            if (!compilerInput.context().sources().contains(unit.path()))
                throw new IllegalStateException(
                        "JAVAC_PROTOCOL_INVALID: unexpected source " + unit.path());
            received.add(unit.path());
        }
        for (Path path : context.publish()) {
            if (received.contains(path)) inventory.parsed++;
            else inventory.failures.put(path, List.of("javac produced no compilation unit"));
        }
        List<JavaDiagnostic> diagnostics = new ArrayList<>(response.diagnostics());
        if (backend.policy != null && backend.mavenInputs != null) {
            for (String dependency : context.dependencyModules().stream().sorted().toList()) {
                var module = backend.mavenInputs.module(dependency);
                boolean excluded =
                        java.util.stream.Stream.concat(
                                        module.mainRoots().stream(),
                                        module.generatedMainRoots().stream())
                                .filter(Files::isDirectory)
                                .anyMatch(backend.policy::excludes);
                boolean available =
                        java.util.stream.Stream.concat(
                                        compilerInput.context().sources().stream(),
                                        compilerInput.catalogues().stream().map(JavaUnit::path))
                                .anyMatch(
                                        path ->
                                                backend.mavenInputs
                                                        .owningModule(path)
                                                        .name()
                                                        .equals(dependency));
                if (excluded && !available)
                    diagnostics.add(
                            new JavaDiagnostic(
                                    null,
                                    "ERROR",
                                    "SOURCE_DEPENDENCY_EXCLUDED",
                                    -1,
                                    -1,
                                    "Required module '"
                                            + dependency
                                            + "' has no eligible MAIN sources after scan"
                                            + " exclusions; dependency declarations are"
                                            + " incomplete."));
            }
        }
        if (!compileFailure.isEmpty())
            diagnostics.add(
                    new JavaDiagnostic(
                            null, "ERROR", "JAVAC_LOMBOK_COMPILE_FAILED", -1, -1, compileFailure));
        backend.diagnosticsEvidence.put(
                task.ordinal(),
                Map.of(
                        "module",
                        context.module(),
                        "scope",
                        context.scope().name(),
                        "diagnostics",
                        diagnostics.stream()
                                .map(
                                        diagnostic ->
                                                Map.of(
                                                        "path",
                                                        diagnostic.path() == null
                                                                ? ""
                                                                : diagnostic.path().toString(),
                                                        "kind",
                                                        diagnostic.kind(),
                                                        "code",
                                                        diagnostic.code(),
                                                        "line",
                                                        diagnostic.line(),
                                                        "column",
                                                        diagnostic.column(),
                                                        "message",
                                                        diagnostic.message()))
                                .toList()));
        units.addAll(compilerInput.catalogues());
        var sourceIdentities =
                SourceIdentityResolver.fromRoots(backend.projectRoot, backend.compilationRoots());
        Map<Path, SourceIdentity> unitIdentities = new LinkedHashMap<>();
        for (JavaUnit unit : units)
            unitIdentities.put(
                    unit.path(),
                    backend.sealedContexts == null
                            ? sourceIdentities.resolve(unit.path().toString())
                            : Objects.requireNonNull(
                                    backend.sourcePlan.identities().get(unit.path()),
                                    "uncaptured source identity"));
        JavaAnalysisContext analysis =
                new JavaAnalysisContext(
                        context.module(),
                        context.scope(),
                        units,
                        context.publish(),
                        diagnostics,
                        response.failure(),
                        unitIdentities,
                        compilerInput.classpath(),
                        backend.mavenInputs != null && backend.mavenInputs.resolvedClasspath()
                                ? "maven-effective"
                                : backend.classpath.isEmpty() ? "empty" : backend.classpathSource,
                        backend.classpathEntries(context, signatures, compilerInput.classpath()),
                        backend.toolchain.release());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int ordinal = 0; ordinal < analysis.classpathEntries().size(); ordinal++) {
            var entry = analysis.classpathEntries().get(ordinal);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ordinal", ordinal);
            item.put("path", entry.path().toString());
            item.put("sha256", entry.sha256());
            if (!entry.coordinate().isEmpty()) item.put("coordinate", entry.coordinate());
            entries.add(item);
        }
        backend.classpathEvidence.put(
                task.ordinal(),
                Map.of(
                        "module", context.module(),
                        "scope", context.scope().name(),
                        "target_jdk", backend.toolchain.release(),
                        "origin", analysis.classpathSource(),
                        "entries", List.copyOf(entries),
                        "analysis",
                                Map.of(
                                        "task_id",
                                        task.id(),
                                        "input_files",
                                        compilerInput.context().sources().size(),
                                        "published_files",
                                        context.publish().size(),
                                        "support_files",
                                        compilerInput.context().sources().stream()
                                                .filter(path -> !context.publish().contains(path))
                                                .map(Path::toString)
                                                .toList(),
                                        "archived_modules",
                                        compilerInput.archivedModules(),
                                        "response_bytes",
                                        response.bytes(),
                                        "decode_bytes",
                                        response.decodeUsage().toMap())));
        JavacCancellation.check();
        if (!backend.modelExtensions.isEmpty()) {
            long overlayStarted = backend.timings == null ? 0 : backend.timings.start();
            try (var binaries = backend.openBinaries(compilerInput.classpath())) {
                analysis = backend.modelExtensions.augment(analysis, binaries, backend.timings);
            }
            if (backend.timings != null) backend.timings.stop("javac_overlay", overlayStarted);
        }
        JavacCancellation.check();
        long signatureStarted = backend.timings == null ? 0 : backend.timings.start();
        signatures.completed(
                context,
                compilerInput,
                analysis.units(),
                !compileFailure.isEmpty()
                        ? compileFailure
                        : diagnostics.stream()
                                        .anyMatch(
                                                d ->
                                                        d.code().equals("JAVAC_PARSE_FAILED")
                                                                || backend.config.lombokCompile()
                                                                        && d.kind().equals("ERROR"))
                                ? "source compilation failed"
                                : response.failure());
        if (backend.timings != null)
            backend.timings.stop("javac_signature_publish", signatureStarted);
        JavacCancellation.check();
        if (backend.sealedContexts != null || !context.publish().isEmpty())
            consumer.accept(analysis);
        JavacCancellation.check();
    }
}
