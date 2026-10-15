package com.anatomist.worker;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

/** Standalone Java 8 bytecode. Uses only public javac APIs, with versioned tree discovery. */
public final class JavacWorker {
    private static final int MAGIC = 0x414A4341;
    private static final int LIMIT = 32 * 1024 * 1024;
    private static final long TOTAL_LIMIT = 128L * 1024 * 1024;
    private static final int DIAGNOSTIC_LIMIT = 16384;
    private final IdentityHashMap<Tree, Integer> ids = new IdentityHashMap<Tree, Integer>();
    private Trees trees;
    private Set<Tree> originalTrees;
    private long spoolBytes;
    private long syntaxNanos;
    private Map<String, Integer> syntaxDictionary;
    private long parseBegin, parseEnd, analyzeBegin, analyzeEnd;

    long[] intervals() {
        return new long[] {parseBegin, parseEnd, analyzeBegin, analyzeEnd};
    }

    private final Map<String, Long> timings = new LinkedHashMap<String, Long>();
    private static final Map<Class<?>, List<Method>> TREE_GETTERS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, List<Method>>();
    private boolean declarationOnly;
    private final Map<CompilationUnitTree, IdentityHashMap<Tree, Integer>> declarationIds =
            new IdentityHashMap<CompilationUnitTree, IdentityHashMap<Tree, Integer>>();
    private final Set<String> declarationSources = new HashSet<String>();
    private final Map<String, String> sourceHashes = new HashMap<String, String>();
    private final Map<String, String> sourceTokens = new HashMap<String, String>();

    public static void main(String[] args) throws Exception {
        try {
            WorkerBatch.run(args.length == 0 ? 1 : Integer.parseInt(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            // A sibling compiler need not honor interruption. Infrastructure failure
            // invalidates the whole process, rather than waiting on its executor threads.
            System.exit(1);
        }
    }

    boolean run(DataInputStream in, DataOutputStream destinationOutput) throws Exception {
        DataOutputStream out =
                new DataOutputStream(new BoundedOutput(destinationOutput, TOTAL_LIMIT - 1024));
        long workerStarted = System.nanoTime();
        if (in.readInt() != MAGIC) throw new IOException("unsupported worker protocol");
        int release = in.readInt();
        boolean compile = in.readBoolean();
        String classpath = read(in);
        String processor = read(in);
        String destination = read(in);
        Path spool = Paths.get(read(in));
        int count = bounded(in.readInt(), 100000);
        List<File> sources = new ArrayList<File>();
        for (int i = 0; i < count; i++) {
            String path = read(in);
            sources.add(new File(path));
            if (in.readBoolean()) declarationSources.add(path);
            sourceTokens.put(path, read(in));
        }
        if (in.read() != -1) throw new IOException("trailing task request data");
        out.writeInt(MAGIC);
        write(out, System.getProperty("java.version"));
        out.flush();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("selected JVM has no javac compiler");
        BoundedDiagnostics diagnostics = new BoundedDiagnostics();
        try (StandardJavaFileManager manager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<File> dependencies = new ArrayList<File>();
            if (!classpath.isEmpty())
                for (String entry :
                        classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                    dependencies.add(new File(entry));
            manager.setLocation(StandardLocation.CLASS_PATH, dependencies);
            manager.setLocation(StandardLocation.SOURCE_PATH, Collections.<File>emptyList());
            if (compile) {
                // Java 8's task options do not reliably configure a supplied file manager.
                // Own both output locations explicitly; never emit beside project sources.
                List<File> outputs = Collections.singletonList(new File(destination));
                manager.setLocation(StandardLocation.CLASS_OUTPUT, outputs);
                manager.setLocation(StandardLocation.SOURCE_OUTPUT, outputs);
            }
            List<String> options = new ArrayList<String>();
            if (release == 8) options.addAll(Arrays.asList("-source", "8", "-target", "8"));
            else options.addAll(Arrays.asList("--release", Integer.toString(release)));
            options.addAll(Arrays.asList("-implicit:none", "-encoding", "UTF-8"));
            // javac otherwise suppresses errors after 100, while Trees.getElement can still
            // return an arbitrary candidate for an ambiguous invocation after that cutoff.
            options.addAll(
                    Arrays.asList(
                            "-Xmaxerrs", Integer.toString(DIAGNOSTIC_LIMIT + 1),
                            "-Xmaxwarns", Integer.toString(DIAGNOSTIC_LIMIT + 1)));
            if (compile) {
                options.addAll(
                        Arrays.asList(
                                "-processorpath",
                                processor,
                                "-processor",
                                "lombok.launch.AnnotationProcessorHider$AnnotationProcessor",
                                "-d",
                                destination));
            } else options.add("-proc:none");
            JavacTask task =
                    (JavacTask)
                            compiler.getTask(
                                    new PrintWriter(System.err),
                                    manager,
                                    diagnostics,
                                    options,
                                    null,
                                    manager.getJavaFileObjectsFromFiles(sources));
            trees = Trees.instance(task);
            List<CompilationUnitTree> units = new ArrayList<CompilationUnitTree>();
            List<Path> syntax = new ArrayList<Path>();
            timings.put("javac_setup", Long.valueOf(System.nanoTime() - workerStarted));
            long parseStarted = System.nanoTime();
            parseBegin = parseStarted;
            for (CompilationUnitTree unit : task.parse()) {
                units.add(unit);
                declarationOnly =
                        declarationSources.contains(
                                new File(unit.getSourceFile().toUri()).getAbsolutePath());
                syntax.add(spoolTree(spool, unit));
                sourceHashes.put(
                        new File(unit.getSourceFile().toUri()).getAbsolutePath(),
                        digest(
                                unit.getSourceFile()
                                        .getCharContent(true)
                                        .toString()
                                        .getBytes(StandardCharsets.UTF_8)));
            }

            parseEnd = System.nanoTime();
            Map<String, String> parseFailures = new LinkedHashMap<String, String>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getSource() != null)
                    parseFailures.put(
                            new File(diagnostic.getSource().toUri()).getAbsolutePath(),
                            diagnostic.getMessage(Locale.ROOT));
            }
            timings.put(
                    "javac_parse", Long.valueOf(System.nanoTime() - parseStarted - syntaxNanos));
            originalTrees = Collections.newSetFromMap(new IdentityHashMap<Tree, Boolean>());
            originalTrees.addAll(ids.keySet());
            String failure = "";
            long analyzeStarted = System.nanoTime();
            analyzeBegin = analyzeStarted;
            try {
                task.analyze();
            } catch (RuntimeException ex) {
                failure = ex.getClass().getName() + ": " + ex.getMessage();
            }
            analyzeEnd = System.nanoTime();
            if (diagnostics.exceeded)
                throw new IOException("javac diagnostics exceed task budget: " + DIAGNOSTIC_LIMIT);
            timings.put("javac_analyze", Long.valueOf(System.nanoTime() - analyzeStarted));
            boolean success = failure.isEmpty();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) success = false;
            Map<URI, NavigableSet<Long>> errorPositions = new HashMap<URI, NavigableSet<Long>>();
            final Map<URI, NavigableSet<Long>> declarationErrors =
                    new HashMap<URI, NavigableSet<Long>>();
            final Map<URI, NavigableSet<Long>> selectionErrors =
                    new HashMap<URI, NavigableSet<Long>>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() != Diagnostic.Kind.ERROR || diagnostic.getSource() == null)
                    continue;
                URI source = diagnostic.getSource().toUri();
                // Definite-assignment failures can be reported on a field/class header for an
                // implicit constructor. They do not change the declaration's ABI. Keep the
                // original diagnostic and local binding completeness separately. Likewise,
                // missing abstract implementations and an invalid @Override do not alter ABI
                // declarations. Override evidence is captured independently of header completeness.
                if (!diagnostic.getCode().equals("compiler.err.var.might.not.have.been.initialized")
                        && !diagnostic
                                .getCode()
                                .equals("compiler.err.var.might.already.be.assigned")
                        && !diagnostic
                                .getCode()
                                .equals("compiler.err.var.not.initialized.in.default.constructor")
                        && !diagnostic.getCode().equals("compiler.err.does.not.override.abstract")
                        && !diagnostic
                                .getCode()
                                .equals("compiler.err.method.does.not.override.superclass")) {
                    NavigableSet<Long> headerErrors = declarationErrors.get(source);
                    if (headerErrors == null) {
                        headerErrors = new TreeSet<Long>();
                        declarationErrors.put(source, headerErrors);
                    }
                    headerErrors.add(Long.valueOf(diagnostic.getPosition()));
                }
                if (blocksSelection(diagnostic.getCode())) {
                    NavigableSet<Long> blocked = selectionErrors.get(source);
                    if (blocked == null) {
                        blocked = new TreeSet<Long>();
                        selectionErrors.put(source, blocked);
                    }
                    blocked.add(Long.valueOf(diagnostic.getPosition()));
                }
                NavigableSet<Long> positions = errorPositions.get(source);
                if (positions == null) {
                    positions = new TreeSet<Long>();
                    errorPositions.put(source, positions);
                }
                positions.add(Long.valueOf(diagnostic.getPosition()));
            }
            final Types types = task.getTypes();
            final Elements elements = task.getElements();
            out.writeInt(units.size());
            int unitIndex = 0;
            long bindingNanos = 0, emitNanos = 0, declarationNanos = 0;
            for (final CompilationUnitTree unit : units) {
                String path = new File(unit.getSourceFile().toUri()).getAbsolutePath();
                declarationOnly = declarationSources.contains(path);
                long bindingStarted = System.nanoTime();
                final WorkerTypes semanticTypes = new WorkerTypes(elements);
                final WorkerOverloadEvidence overloadEvidence =
                        new WorkerOverloadEvidence(trees, elements);
                final Map<Integer, Binding> bindings = new LinkedHashMap<Integer, Binding>();
                final NavigableSet<Long> errors = errorPositions.get(unit.getSourceFile().toUri());
                final boolean declarationView = declarationOnly;
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void scan(Tree node, Void unused) {
                        if (node == null) return null;
                        if (declarationView && !declarationIds.get(unit).containsKey(node))
                            return null;
                        com.sun.source.util.TreePath path =
                                getCurrentPath() == null
                                        ? new com.sun.source.util.TreePath(unit)
                                        : new com.sun.source.util.TreePath(getCurrentPath(), node);
                        capture(node, path);
                        return super.scan(node, unused);
                    }

                    private void capture(Tree node, com.sun.source.util.TreePath path) {
                        Integer id =
                                declarationView
                                        ? declarationIds.get(unit).get(node)
                                        : ids.get(node);
                        if (id == null) return;
                        long start = trees.getSourcePositions().getStartPosition(unit, node);
                        long end = trees.getSourcePositions().getEndPosition(unit, node);
                        Long first = errors == null ? null : errors.ceiling(Long.valueOf(start));
                        boolean complete = first == null || first.longValue() >= end;
                        try {
                            Element element = trees.getElement(path);
                            TypeMirror expression = trees.getTypeMirror(path);
                            if (expression == null) return;
                            boolean erroneous =
                                    expression.getKind() == TypeKind.ERROR
                                            || element != null
                                                    && element.asType().getKind() == TypeKind.ERROR;
                            complete = complete && !erroneous;
                            // A missing result type does not invalidate a known selected member.
                            // Recovery class elements still cannot stand in for selected methods.
                            if (erroneous
                                    && !(element instanceof ExecutableElement)
                                    && !(element instanceof VariableElement)) return;
                            Element enclosing =
                                    element == null ? null : element.getEnclosingElement();
                            String owner =
                                    enclosing instanceof TypeElement
                                            ? ((TypeElement) enclosing)
                                                    .getQualifiedName()
                                                    .toString()
                                            : "";
                            List<String> params = new ArrayList<String>();
                            String returns = "";
                            String signature = "";
                            boolean varargs = false;
                            boolean declarationResolved = element != null && !erroneous;
                            List<String> flags = new ArrayList<String>(),
                                    thrown = new ArrayList<String>();
                            if (element != null)
                                for (Modifier flag : element.getModifiers())
                                    flags.add(flag.toString());
                            if (element instanceof ExecutableElement) {
                                ExecutableElement method = (ExecutableElement) element;
                                for (VariableElement param : method.getParameters())
                                    params.add(types.erasure(param.asType()).toString());
                                returns = types.erasure(method.getReturnType()).toString();
                                varargs = method.isVarArgs();
                                StringBuilder descriptor = new StringBuilder("(");
                                boolean descriptorAvailable = true;
                                declarationResolved = enclosing instanceof TypeElement;
                                for (VariableElement param : method.getParameters())
                                    try {
                                        descriptor.append(
                                                descriptor(
                                                        types.erasure(param.asType()), elements));
                                    } catch (IllegalArgumentException unavailable) {
                                        descriptorAvailable = false;
                                        complete = false;
                                    }
                                try {
                                    signature =
                                            descriptor
                                                    .append(')')
                                                    .append(
                                                            descriptor(
                                                                    types.erasure(
                                                                            method.getReturnType()),
                                                                    elements))
                                                    .toString();
                                } catch (IllegalArgumentException unavailable) {
                                    complete = false;
                                }
                                if (!descriptorAvailable) signature = "";
                                for (TypeMirror exception : method.getThrownTypes())
                                    thrown.add(types.erasure(exception).toString());
                            } else if (element instanceof VariableElement) {
                                declarationResolved =
                                        enclosing instanceof TypeElement || !erroneous;
                                try {
                                    signature =
                                            descriptor(types.erasure(element.asType()), elements);
                                } catch (IllegalArgumentException unavailable) {
                                    complete = false;
                                }
                            }
                            if (declarationResolved
                                    && (element instanceof ExecutableElement
                                            || element instanceof VariableElement
                                                    && enclosing instanceof TypeElement))
                                declarationResolved =
                                        !selectionError(
                                                node,
                                                unit,
                                                trees,
                                                selectionErrors.get(unit.getSourceFile().toUri()));
                            if (declarationResolved && element instanceof ExecutableElement)
                                declarationResolved =
                                        overloadEvidence.selectionKnown(
                                                node, path, (ExecutableElement) element);
                            bindings.put(
                                    id,
                                    new Binding(
                                            id.intValue(),
                                            element == null ? "" : element.getKind().name(),
                                            owner,
                                            element == null
                                                    ? ""
                                                    : element.getSimpleName().toString(),
                                            params,
                                            returns,
                                            expression.toString(),
                                            varargs,
                                            flags,
                                            thrown,
                                            signature,
                                            semanticTypes.add(expression),
                                            semanticTypes.add(
                                                    element == null ? null : element.asType()),
                                            semanticTypes.add(
                                                    node
                                                                    instanceof
                                                                    com.sun.source.tree
                                                                            .MethodInvocationTree
                                                            ? trees.getTypeMirror(
                                                                    new com.sun.source.util
                                                                            .TreePath(
                                                                            path,
                                                                            ((com.sun.source.tree
                                                                                                    .MethodInvocationTree)
                                                                                            node)
                                                                                    .getMethodSelect()))
                                                            : null),
                                            complete,
                                            enclosing instanceof TypeElement
                                                    ? elements.getBinaryName(
                                                                    (TypeElement) enclosing)
                                                            .toString()
                                                    : "",
                                            enclosing instanceof TypeElement
                                                    ? enclosing.getKind().name()
                                                    : "",
                                            declarationResolved));
                        } catch (WorkerTypes.BudgetExceeded exceeded) {
                            throw exceeded;
                        } catch (RuntimeException ignored) {
                            /* Recovery trees are not confirmed bindings. */
                        }
                    }
                }.scan(unit, null);
                bindingNanos += System.nanoTime() - bindingStarted;
                long declarationStarted = System.nanoTime();
                byte[] inventory =
                        new WorkerDeclarations(
                                        unit,
                                        trees,
                                        types,
                                        elements,
                                        semanticTypes,
                                        originalTrees,
                                        declarationErrors.get(unit.getSourceFile().toUri()),
                                        declarationOnly)
                                .encode();
                declarationNanos += System.nanoTime() - declarationStarted;
                long emitStarted = System.nanoTime();
                write(out, path);
                write(out, sourceHashes.get(path));
                out.writeBoolean(declarationOnly);
                emitTree(out, syntax.get(unitIndex++), sourceTokens.get(path));
                // Preserve processor-mutated declarations for a separately marked, conservative
                // partial-evidence path when an unrelated source prevents class generation.
                out.writeByte(compile ? 1 : 0);
                Map<String, Integer> dictionary = new LinkedHashMap<String, Integer>();
                for (Binding binding : bindings.values()) binding.intern(dictionary);
                semanticTypes.intern(dictionary);
                out.writeInt(dictionary.size());
                for (String entry : dictionary.keySet()) write(out, entry);
                semanticTypes.write(out, dictionary);
                out.writeInt(bindings.size());
                for (Binding binding : bindings.values()) binding.write(out, dictionary);
                out.writeInt(inventory.length);
                out.write(inventory);
                emitNanos += System.nanoTime() - emitStarted;
            }
            if (compile && success) {
                try {
                    task.generate();
                } catch (RuntimeException ex) {
                    success = false;
                    failure = ex.getClass().getName() + ": " + ex.getMessage();
                }
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics())
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) success = false;
            }
            out.writeBoolean(!compile || success);
            write(out, failure);
            if (diagnostics.getDiagnostics().size() + parseFailures.size() > DIAGNOSTIC_LIMIT)
                throw new IOException("javac diagnostics exceed task budget: " + DIAGNOSTIC_LIMIT);
            out.writeInt(diagnostics.getDiagnostics().size() + parseFailures.size());
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                write(
                        out,
                        d.getSource() == null
                                ? ""
                                : new File(d.getSource().toUri()).getAbsolutePath());
                write(out, d.getKind().name());
                write(out, d.getCode());
                out.writeLong(d.getLineNumber());
                out.writeLong(d.getColumnNumber());
                write(out, d.getMessage(Locale.ROOT));
            }
            for (Map.Entry<String, String> parseFailure : parseFailures.entrySet()) {
                write(out, parseFailure.getKey());
                write(out, "ERROR");
                write(out, "JAVAC_PARSE_FAILED");
                out.writeLong(-1);
                out.writeLong(-1);
                write(out, parseFailure.getValue());
            }
            timings.put("javac_binding", Long.valueOf(bindingNanos));
            timings.put("javac_declaration", Long.valueOf(declarationNanos));
            timings.put("javac_syntax_encode", Long.valueOf(syntaxNanos));
            timings.put("javac_emit", Long.valueOf(emitNanos));
            out.writeInt(timings.size());
            for (Map.Entry<String, Long> timing : timings.entrySet()) {
                write(out, timing.getKey());
                out.writeLong(timing.getValue().longValue());
            }
            out.writeInt(0x414A4544);
            out.flush();
            return !failure.isEmpty() || compile && !success;
        }
    }

    private Path spoolTree(Path spool, CompilationUnitTree unit) throws Exception {
        long started = System.nanoTime();
        Path file = Files.createTempFile(spool, "tree-", ".bin");
        syntaxDictionary = new LinkedHashMap<String, Integer>();
        int bodyLength;
        try (DataOutputStream data =
                new DataOutputStream(
                        new BufferedOutputStream(
                                new BoundedOutput(
                                        Files.newOutputStream(file),
                                        Math.min(LIMIT, TOTAL_LIMIT - spoolBytes))))) {
            data.writeInt(0);
            tree(data, unit, unit, 0);
            bodyLength = data.size() - 4;
            data.writeInt(syntaxDictionary.size());
            for (String text : syntaxDictionary.keySet()) write(data, text);
        } finally {
            syntaxDictionary = null;
        }
        try (RandomAccessFile header = new RandomAccessFile(file.toFile(), "rw")) {
            header.writeInt(bodyLength);
        }
        syntaxNanos += System.nanoTime() - started;
        spoolBytes += Files.size(file);
        return file;
    }

    private void emitTree(DataOutputStream out, Path file, String expected) throws Exception {
        long size = Files.size(file);
        String token =
                declarationOnly && expected != null && !expected.isEmpty() ? digest(file) : "";
        if (!token.isEmpty() && token.equals(expected)) {
            out.writeInt(-1);
            write(out, token);
        } else {
            out.writeInt((int) size);
            Files.copy(file, out);
        }
        Files.delete(file);
        spoolBytes -= size;
    }

    private static String digest(byte[] bytes) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : hash) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static String digest(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private void tree(DataOutputStream out, CompilationUnitTree unit, Tree node, int depth)
            throws Exception {
        if (depth > 512) throw new IOException("source tree exceeds depth budget");
        Integer id = ids.get(node);
        if (id == null) {
            id = Integer.valueOf(ids.size());
            ids.put(node, id);
        }
        out.writeByte(1);
        if (declarationOnly) {
            IdentityHashMap<Tree, Integer> local = declarationIds.get(unit);
            if (local == null) {
                local = new IdentityHashMap<Tree, Integer>();
                declarationIds.put(unit, local);
            }
            Integer localId = local.get(node);
            if (localId == null) {
                localId = Integer.valueOf(local.size());
                local.put(node, localId);
            }
            out.writeInt(localId.intValue());
        } else out.writeInt(id.intValue());
        syntaxText(out, node.getKind().name());
        out.writeLong(trees.getSourcePositions().getStartPosition(unit, node));
        out.writeLong(trees.getSourcePositions().getEndPosition(unit, node));
        List<Method> getters = getters(node.getKind().asInterface());
        out.writeInt(getters.size() + 1);
        syntaxText(out, "originalSourceTree");
        value(out, unit, originalTrees == null || originalTrees.contains(node), depth + 1);
        for (Method getter : getters) {
            syntaxText(out, getter.getName());
            boolean generated = originalTrees != null && !originalTrees.contains(node);
            Object member =
                    generated
                                    && (getter.getName().equals("getBody")
                                            || getter.getName().equals("getInitializer"))
                            ? null
                            : getter.invoke(node);
            if (declarationOnly
                    && getter.getName().equals("getStatements")
                    && node.getKind() == Tree.Kind.BLOCK) member = Collections.emptyList();
            if (declarationOnly
                    && getter.getName().equals("getMembers")
                    && member instanceof Collection<?>) {
                List<Object> declarations = new ArrayList<Object>();
                for (Object value : (Collection<?>) member)
                    if (!(value instanceof Tree) || ((Tree) value).getKind() != Tree.Kind.BLOCK)
                        declarations.add(value);
                member = declarations;
            }
            value(out, unit, member, depth + 1);
        }
    }

    private static List<Method> getters(Class<?> nodeType) {
        List<Method> cached = TREE_GETTERS.get(nodeType);
        if (cached != null) return cached;
        Method[] all = nodeType.getMethods();
        Arrays.sort(
                all,
                new Comparator<Method>() {
                    public int compare(Method left, Method right) {
                        return left.getName().compareTo(right.getName());
                    }
                });
        List<Method> getters = new ArrayList<Method>();
        Set<String> names = new HashSet<String>();
        for (Method method : all) {
            String name = method.getName();
            if (method.getParameterTypes().length == 0
                    && (name.startsWith("get") || name.startsWith("is"))
                    && !Arrays.asList("getKind", "getSourceFile", "getLineMap").contains(name)
                    && names.add(name)) getters.add(method);
        }
        List<Method> result = Collections.unmodifiableList(getters);
        TREE_GETTERS.put(nodeType, result);
        return result;
    }

    private void value(DataOutputStream out, CompilationUnitTree unit, Object value, int depth)
            throws Exception {
        if (value == null) out.writeByte(0);
        else if (value instanceof Tree) tree(out, unit, (Tree) value, depth);
        else if (value instanceof Collection<?>) {
            out.writeByte(2);
            Collection<?> values = (Collection<?>) value;
            out.writeInt(values.size());
            for (Object item : values) value(out, unit, item, depth + 1);
        } else {
            out.writeByte(3);
            syntaxText(out, String.valueOf(value));
        }
    }

    private void syntaxText(DataOutputStream out, String text) throws IOException {
        Integer reference = syntaxDictionary.get(text);
        if (reference == null) {
            if (syntaxDictionary.size() >= 100000)
                throw new IOException("syntax dictionary exceeds budget");
            reference = Integer.valueOf(syntaxDictionary.size());
            syntaxDictionary.put(text, reference);
        }
        out.writeInt(reference.intValue());
    }

    private static int bounded(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("protocol count outside budget");
        return value;
    }

    private static String read(DataInputStream input) throws IOException {
        byte[] bytes = new byte[bounded(input.readInt(), LIMIT)];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("protocol string exceeds budget");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static final class BoundedDiagnostics implements DiagnosticListener<JavaFileObject> {
        private final DiagnosticCollector<JavaFileObject> collector =
                new DiagnosticCollector<JavaFileObject>();
        private int count;
        private boolean exceeded;

        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            if (++count > DIAGNOSTIC_LIMIT) {
                exceeded = true;
                throw new IllegalStateException("javac diagnostic budget exceeded");
            }
            collector.report(diagnostic);
        }

        List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
            return collector.getDiagnostics();
        }
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private long remaining = TOTAL_LIMIT;

        BoundedOutput(OutputStream output, long maximum) {
            super(output);
            remaining = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            if (--remaining < 0)
                throw new IOException("worker output exceeds total protocol budget");
            out.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if ((remaining -= length) < 0)
                throw new IOException("worker output exceeds total protocol budget");
            out.write(bytes, offset, length);
        }
    }

    private static final class Binding {
        final int id, expressionType, declaredType, selectedType;
        final String kind, owner, name, returns, type, descriptor, binaryOwner, ownerKind;
        final List<String> params, flags, thrown;
        final boolean varargs, evidenceComplete, declarationResolved;

        Binding(
                int id,
                String kind,
                String owner,
                String name,
                List<String> params,
                String returns,
                String type,
                boolean varargs,
                List<String> flags,
                List<String> thrown,
                String descriptor,
                int expressionType,
                int declaredType,
                int selectedType,
                boolean evidenceComplete,
                String binaryOwner,
                String ownerKind,
                boolean declarationResolved) {
            this.declarationResolved = declarationResolved;
            this.evidenceComplete = evidenceComplete;
            this.binaryOwner = binaryOwner;
            this.ownerKind = ownerKind;
            this.expressionType = expressionType;
            this.declaredType = declaredType;
            this.selectedType = selectedType;
            this.descriptor = descriptor;
            this.flags = flags;
            this.thrown = thrown;
            this.id = id;
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.params = params;
            this.returns = returns;
            this.type = type;
            this.varargs = varargs;
        }

        void intern(Map<String, Integer> dictionary) {
            for (String value :
                    Arrays.asList(
                            kind, owner, name, returns, type, descriptor, binaryOwner, ownerKind))
                intern(dictionary, value);
            for (String value : params) intern(dictionary, value);
            for (String value : flags) intern(dictionary, value);
            for (String value : thrown) intern(dictionary, value);
        }

        private static void intern(Map<String, Integer> dictionary, String value) {
            if (!dictionary.containsKey(value))
                dictionary.put(value, Integer.valueOf(dictionary.size()));
        }

        void write(DataOutputStream out, Map<String, Integer> dictionary) throws IOException {
            out.writeInt(id);
            out.writeInt(dictionary.get(kind).intValue());
            out.writeInt(dictionary.get(owner).intValue());
            out.writeInt(dictionary.get(name).intValue());
            out.writeInt(params.size());
            for (String param : params) out.writeInt(dictionary.get(param).intValue());
            out.writeInt(dictionary.get(returns).intValue());
            out.writeInt(dictionary.get(type).intValue());
            out.writeBoolean(varargs);
            out.writeInt(flags.size());
            for (String flag : flags) out.writeInt(dictionary.get(flag).intValue());
            out.writeInt(thrown.size());
            for (String exception : thrown) out.writeInt(dictionary.get(exception).intValue());
            out.writeInt(dictionary.get(descriptor).intValue());
            out.writeInt(expressionType);
            out.writeInt(declaredType);
            out.writeInt(selectedType);
            out.writeBoolean(evidenceComplete);
            out.writeInt(dictionary.get(binaryOwner).intValue());
            out.writeInt(dictionary.get(ownerKind).intValue());
            out.writeBoolean(declarationResolved);
        }
    }

    private static boolean selectionError(
            Tree node, CompilationUnitTree unit, Trees trees, NavigableSet<Long> errors) {
        if (errors == null) return false;
        Tree selected = node;
        if (node instanceof com.sun.source.tree.MethodInvocationTree)
            selected = ((com.sun.source.tree.MethodInvocationTree) node).getMethodSelect();
        else if (node instanceof com.sun.source.tree.NewClassTree)
            selected = ((com.sun.source.tree.NewClassTree) node).getIdentifier();
        long start = trees.getSourcePositions().getStartPosition(unit, selected);
        long end = trees.getSourcePositions().getEndPosition(unit, selected);
        Long first = errors.ceiling(Long.valueOf(start));
        return first != null && first.longValue() < end;
    }

    private static boolean blocksSelection(String code) {
        return code.contains("ambiguous")
                || code.contains("cant.apply")
                || code.contains("cant.resolve")
                || code.contains("access")
                || code.contains("non-static")
                || code.contains("invalid.mref");
    }

    private static String descriptor(TypeMirror type, Elements elements) {
        switch (type.getKind()) {
            case VOID:
                return "V";
            case BOOLEAN:
                return "Z";
            case BYTE:
                return "B";
            case CHAR:
                return "C";
            case SHORT:
                return "S";
            case INT:
                return "I";
            case LONG:
                return "J";
            case FLOAT:
                return "F";
            case DOUBLE:
                return "D";
            case ARRAY:
                return "[" + descriptor(((ArrayType) type).getComponentType(), elements);
            case DECLARED:
                return "L"
                        + elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement())
                                .toString()
                                .replace('.', '/')
                        + ";";
            default:
                throw new IllegalArgumentException("no descriptor for " + type);
        }
    }
}
