package dev.codeana.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.*;

import dev.codeana.common.Json;
import dev.codeana.common.ModelArchive;
import dev.codeana.common.ModelArchive.RowSpool;
import dev.codeana.common.TypeGraph;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;

/** Java-8-only compiler process. No compiler objects leave this class or this JVM. */
public final class CompilerBridge implements AutoCloseable {
    private final RowSpool syntax;
    private final IdentityHashMap<Tree, Integer> treeIds = new IdentityHashMap<Tree, Integer>();
    private final IdentityHashMap<Tree, List<? extends AnnotationTree>> modifierAnnotations =
            new IdentityHashMap<Tree, List<? extends AnnotationTree>>();
    private final RowSpool declarations;
    private final RowSpool observations;
    private final Map<String,List<Object>> declarationAnnotations = new HashMap<String,List<Object>>();
    private final Map<String, Map<String, Object>> writtenExecutableTypes =
            new HashMap<String, Map<String, Object>>();
    private final Map<String, Object> external = new TreeMap<String, Object>();
    private final Map<String, TypeElement> annotationTypes = new TreeMap<String, TypeElement>();
    private final List<Object> diagnostics = new ArrayList<Object>();
    private final Set<String> typeBudgetDiagnostics = new HashSet<String>();
    private final Map<String, Map<String, Object>> sources =
            new HashMap<String, Map<String, Object>>();
    private final IdentityHashMap<Tree, Boolean> original = new IdentityHashMap<Tree, Boolean>();
    private final IdentityHashMap<Element,TreePath> elementPaths = new IdentityHashMap<Element,TreePath>();
    private Trees trees;
    private Elements elements;
    private Types types;
    private WorkerOverloadEvidence overloadEvidence;
    private boolean graphTypes;
    private final Map<String,CompilerTypeGraph> typeGraphs = new HashMap<String,CompilerTypeGraph>();
    private BoundedDiagnostics listener;
    private String phase = "parse";
    private int records;
    private long recordLimit = 100000;
    private String context = "", currentSource = "";
    private boolean fullAuxiliaryModel;
    private final Map<String,Map<String,Integer>> captureNumbers = new HashMap<String,Map<String,Integer>>();
    private static final java.util.regex.Pattern CAPTURE = java.util.regex.Pattern.compile("capture#([0-9]+)");

    private String stableType(TypeMirror type) {
        if (type == null) return "null";
        java.util.regex.Matcher matcher = CAPTURE.matcher(type.toString());
        StringBuffer output = new StringBuffer();
        Map<String,Integer> numbers = captureNumbers.computeIfAbsent(currentSource, ignored -> new LinkedHashMap<String,Integer>());
        while (matcher.find()) {
            Integer number = numbers.get(matcher.group(1));
            if (number == null) { number = numbers.size()+1; numbers.put(matcher.group(1),number); }
            matcher.appendReplacement(output,"capture#"+number);
        }
        matcher.appendTail(output); return output.toString();
    }

    private CompilerBridge(Path work, long archiveBytes) throws IOException {
        RowSpool d = null, o = null, s = null;
        try {
            d = new RowSpool(work, "declarations", archiveBytes); o = new RowSpool(work, "observations", archiveBytes); s = new RowSpool(work, "syntax", archiveBytes);
        } catch (IOException failure) {
            for (RowSpool row : new RowSpool[]{d,o,s}) if (row != null)
                try { row.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        declarations = d; observations = o; syntax = s;
    }
    public void close() throws IOException {
        IOException failure = null;
        for (RowSpool row : new RowSpool[]{declarations,observations,syntax}) {
            try { row.close(); } catch (IOException cleanup) {
                if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("java.specification.version").equals("1.8"))
            throw new IllegalArgumentException("TARGET_JDK_8_REQUIRED");
        if (args.length == 1 && "--session".equals(args[0])) {
            session();
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("request result required");
        Map<String, Object> request = Json.map(Json.parse(Json.read(Paths.get(args[0]))));
        Path result = Paths.get(args[1]);
        long bytes;
        long archiveBytes = ModelArchive.byteLimit(request);
        try (CompilerBridge analysis = new CompilerBridge(result.toAbsolutePath().getParent(), archiveBytes)) {
            bytes = ModelArchive.writeAndReleaseRows(result, analysis.analyze(request), archiveBytes);
        }
        System.out.println(
                Json.write(
                        Json.object(
                                "version",
                                7,
                                "context",
                                request.get("context"),
                                "bytes",
                                bytes,
                                "sha256",
                                Json.sha(result))));
    }

    /** Each command gets a fresh javac context/file manager; only the JVM is reused. */
    private static void session() throws Exception {
        DataInputStream input = new DataInputStream(new BufferedInputStream(System.in));
        while (true) {
            int size;
            try { size = input.readInt(); } catch (EOFException closed) { return; }
            if (size < 1 || size > 65536) throw new IOException("COMPILER_COMMAND_BUDGET");
            byte[] commandBytes = new byte[size];
            input.readFully(commandBytes);
            Map<String, Object> command = Json.map(Json.parse(commandBytes));
            Path result = Paths.get(Json.string(command, "result"));
            Path receipt = Paths.get(Json.string(command, "receipt"));
            Map<String, Object> end = Json.object("version", 7, "nonce", command.get("nonce"));
            CompilerBridge analysis = null;
            try {
                Map<String, Object> request = Json.map(Json.parse(Json.read(Paths.get(Json.string(command, "request")))));
                long archiveBytes = ModelArchive.byteLimit(request);
                analysis = new CompilerBridge(result.toAbsolutePath().getParent(), archiveBytes);
                long bytes = ModelArchive.writeAndReleaseRows(result, analysis.analyze(request), archiveBytes);
                end.put("model_archive_bytes", archiveBytes);
                end.put("context", request.get("context"));
                end.put("bytes", bytes);
                end.put("sha256", Json.sha(result));
            } catch (Exception failed) {
                String message = failed.getClass().getName() + ": " + failed.getMessage();
                if (analysis != null) message += " context=" + analysis.context + " phase=" + analysis.phase + " source=" + analysis.currentSource;
                int frames = 0;
                for (StackTraceElement frame : failed.getStackTrace()) {
                    if (++frames > 12 || message.length() > 3500) break;
                    message += " | " + frame;
                }
                end.put("error", message.substring(0, Math.min(message.length(), 4096)));
            }
            finally { if (analysis != null) analysis.close(); }
            Path temporary = receipt.resolveSibling(receipt.getFileName() + ".tmp");
            Files.write(temporary, Json.write(end).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void budget() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("CANCELLED");
        if (++records > recordLimit)
            throw new IllegalStateException("MODEL_RECORD_BUDGET context=" + context + " phase=" + phase
                    + " source=" + currentSource + " count=" + records + " limit=" + recordLimit);
    }

    private Map<String, Object> analyze(Map<String, Object> request) throws Exception {
        if (Json.number(request, "version") != 7)
            throw new IOException("COMPILER_PROTOCOL_VERSION");
        context = Json.string(request,"context");
        fullAuxiliaryModel = Boolean.TRUE.equals(request.get("full_auxiliary_model"));
        graphTypes = Boolean.TRUE.equals(request.get("type_graph"));
        if (request.containsKey("model_record_limit")) {
            try { recordLimit = new java.math.BigDecimal(request.get("model_record_limit").toString()).longValueExact(); }
            catch (RuntimeException invalid) { throw new IOException("JAVA_MODEL_RECORD_LIMIT_RANGE",invalid); }
        }
        if(recordLimit<1 || recordLimit>5000000) throw new IOException("JAVA_MODEL_RECORD_LIMIT_RANGE");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IOException("COMPILER_UNAVAILABLE");
        listener = new BoundedDiagnostics();
        List<File> files = new ArrayList<File>();
        for (Object raw : Json.list(request.get("sources"))) {
            Map<String, Object> s = Json.map(raw);
            Path path = Paths.get(Json.string(s, "input")).toRealPath();
            byte[] bytes = Json.read(path);
            if (!Json.sha(bytes).equals(Json.string(s, "hash")))
                throw new IOException("SOURCE_HASH_MISMATCH");
            // Reject malformed input instead of silently replacing bytes in the compiler view.
            try {
                StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            } catch (java.nio.charset.CharacterCodingException invalid) {
                budget();
                diagnostics.add(Json.object("code","SOURCE_ENCODING_INVALID","severity","ERROR",
                        "source",s.get("path"),"start",-1,"end",-1,"phase","input_decode",
                        "context",context,"encoding","UTF-8","sha256",s.get("hash"),
                        "message","Captured source is not valid UTF-8; original bytes retained"));
                continue;
            }
            sources.put(path.toString(), s);
            files.add(path.toFile());
        }
        if(files.isEmpty())return detached(request,Collections.<Object>emptyList());
        try (StandardJavaFileManager fm =
                compiler.getStandardFileManager(listener, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<File> cp = new ArrayList<File>();
            for (Object item : Json.list(request.get("classpath"))) cp.add(new File((String) item));
            fm.setLocation(StandardLocation.CLASS_PATH, cp);
            fm.setLocation(StandardLocation.SOURCE_PATH, Collections.<File>emptyList());
            List<String> options =
                    Arrays.asList(
                            "-source",
                            "8",
                            "-target",
                            "8",
                            "-proc:none",
                            "-implicit:none",
                            "-encoding",
                            "UTF-8",
                            "-Xmaxerrs",
                            "10001",
                            "-Xmaxwarns",
                            "10001");
            JavacTask task =
                    (JavacTask)
                            compiler.getTask(
                                    new PrintWriter(System.err),
                                    fm,
                                    listener,
                                    options,
                                    null,
                                    fm.getJavaFileObjectsFromFiles(files));
            trees = Trees.instance(task);
            elements = task.getElements();
            types = task.getTypes();
            overloadEvidence = new WorkerOverloadEvidence(trees, elements);
            List<CompilationUnitTree> units = new ArrayList<CompilationUnitTree>();
            for (CompilationUnitTree unit : task.parse()) {
                currentSource = Json.string(sources.get(Paths.get(unit.getSourceFile().toUri()).toRealPath().toString()),"path");
                units.add(unit);
                new TreeScanner<Void, Void>() {
                    int nextId;
                    @Override
                    public Void visitVariable(VariableTree node, Void p) {
                        modifierAnnotations.put(node, new ArrayList<AnnotationTree>(node.getModifiers().getAnnotations()));
                        return super.visitVariable(node, p);
                    }
                    @Override
                    public Void scan(Tree node, Void p) {
                        if (node != null) {
                            budget();
                            original.put(node, Boolean.TRUE);
                            treeIds.put(node, ++nextId);
                        }
                        return super.scan(node, p);
                    }
                }.scan(unit, null);
            }
            phase = "analyze";
            task.analyze();
            if (listener.exceeded) throw new IOException("DIAGNOSTIC_BUDGET");
            phase = "declarations";
            for (CompilationUnitTree unit : units) {
                currentSource = Json.string(sources.get(Paths.get(unit.getSourceFile().toUri()).toRealPath().toString()),"path");
                new Scanner(unit, true).scan(unit, null);
                if(graphTypes) {CompilerTypeGraph graph=typeGraphs.remove(currentSource);if(graph!=null)graph.snapshot();}
            }
            phase = "observations";
            for (CompilationUnitTree unit : units) {
                currentSource = Json.string(sources.get(Paths.get(unit.getSourceFile().toUri()).toRealPath().toString()),"path");
                new Scanner(unit, false).scan(unit, null);
                if(graphTypes) {CompilerTypeGraph graph=typeGraphs.remove(currentSource);if(graph!=null)graph.snapshot();}
            }
            for (Diagnostic<? extends JavaFileObject> d : listener.items) {
                Map<String, Object> s =
                        d.getSource() == null
                                ? null
                                : sources.get(
                                        Paths.get(d.getSource().toUri())
                                                .toAbsolutePath()
                                                .normalize()
                                                .toString());
                diagnostics.add(
                        Json.object(
                                "code",
                                d.getCode(),
                                "severity",
                                d.getKind().name(),
                                "phase",
                                listener.phases.get(d),
                                "source",
                                s == null ? "" : s.get("path"),
                                "start",
                                d.getStartPosition(),
                                "end",
                                d.getEndPosition(),
                                "message",
                                d.getMessage(Locale.ROOT),
                                "count",
                                1));
            }
        }
        List<Object> annotationHeaders = new ArrayList<Object>();
        phase = "annotation_types"; currentSource = "";
        Set<String> visitedAnnotations = new HashSet<String>();
        while (visitedAnnotations.size() < annotationTypes.size()) {
            if (annotationTypes.size() > 1024) throw new IOException("ANNOTATION_TYPE_BUDGET");
            for (Map.Entry<String, TypeElement> item : new TreeMap<String, TypeElement>(annotationTypes).entrySet()) {
                if (!visitedAnnotations.add(item.getKey())) continue;
                budget();
                TypeElement type = item.getValue();
                List<Object> members = new ArrayList<Object>();
                for (Element member : type.getEnclosedElements()) {
                    if (!(member instanceof ExecutableElement)) continue;
                    ExecutableElement method = (ExecutableElement) member;
                    Map<String, Object> header = Json.object("name", method.getSimpleName().toString(),
                            "annotations", annotations(method));
                    if (method.getDefaultValue() != null) header.put("default", annotationValue(method.getDefaultValue(), 0));
                    members.add(header);
                }
                annotationHeaders.add(Json.object("name", item.getKey(), "annotations", annotations(type),
                        "members", members, "complete", type.asType().getKind() != TypeKind.ERROR));
            }
        }
        // Detached rows no longer need the compiler graph while the final archive is assembled.
        for(CompilerTypeGraph graph:typeGraphs.values())graph.snapshot();
        typeGraphs.clear();
        elementPaths.clear(); writtenExecutableTypes.clear(); trees = null; elements = null; types = null; listener = null;
        original.clear(); treeIds.clear(); modifierAnnotations.clear(); annotationTypes.clear(); declarationAnnotations.clear();
        return detached(request,annotationHeaders);
    }

    private Map<String,Object> detached(Map<String,Object> request,List<Object> annotationHeaders) {
        return Json.object(
                "version",
                6,
                "context",
                request.get("context"),
                "sources",
                request.get("sources"),
                "declarations",
                declarations,
                "observations",
                observations,
                "syntax",
                syntax,
                "external",
                new ArrayList<Object>(external.values()),
                "annotation_types", annotationHeaders,
                "diagnostics",
                diagnostics);
    }

    private String signature(Element element) {
        if (element == null) return "";
        if (element instanceof TypeElement)
            return elements.getBinaryName((TypeElement) element).toString();
        if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            // javac uses typeless synthetic methods for source initializer scopes.
            if (method.asType() == null) return "";
            StringBuilder out =
                    new StringBuilder(signature(method.getEnclosingElement()))
                            .append('#')
                            .append(method.getSimpleName())
                            .append('(');
            boolean first = true;
            for (VariableElement p : method.getParameters()) {
                if (!first) out.append(',');
                first = false;
                out.append(erasedIdentity(types.erasure(p.asType())));
            }
            return out.append(')').toString();
        }
        if (element instanceof VariableElement
                && (element.getKind() == ElementKind.FIELD
                        || element.getKind() == ElementKind.ENUM_CONSTANT))
            return signature(element.getEnclosingElement()) + '#' + element.getSimpleName();
        if (element instanceof VariableElement) {
            TreePath path = elementPath(element);
            if (path != null) {
                String enclosing = localOwner(element, path);
                if (enclosing.isEmpty()) return "";
                return enclosing
                        + "#local:"
                        + element.getSimpleName()
                        + "@"
                        + trees.getSourcePositions()
                                .getStartPosition(path.getCompilationUnit(), path.getLeaf());
            }
        }
        return "";
    }

    /** Javac's element path lookup traverses source trees; declarations seed the exact path once. */
    private TreePath elementPath(Element element) {
        if (elementPaths.containsKey(element)) return elementPaths.get(element);
        TreePath path = trees.getPath(element);
        elementPaths.put(element, path);
        return path;
    }

    private String localOwner(Element element, TreePath path) {
        Element enclosing = element.getEnclosingElement();
        if (!(enclosing instanceof ExecutableElement) || enclosing.asType() != null)
            return signature(enclosing);
        return lexicalOwner(path.getParentPath(), 0);
    }

    /** Match the source owners created by Scanner, without querying synthetic method parameters. */
    private String lexicalOwner(TreePath path, int depth) {
        if (depth > 64) return "";
        for (TreePath current = path; current != null; current = current.getParentPath()) {
            Tree tree = current.getLeaf();
            TreePath parent = current.getParentPath();
            long start = trees.getSourcePositions().getStartPosition(current.getCompilationUnit(), tree);
            if (tree instanceof LambdaExpressionTree) {
                String enclosing = lexicalOwner(parent, depth + 1);
                return enclosing.isEmpty() ? "" : enclosing + "#<lambda@" + start + ">()";
            }
            if (tree instanceof BlockTree && parent != null && parent.getLeaf() instanceof ClassTree) {
                String enclosing = signature(trees.getElement(parent));
                return enclosing.isEmpty() ? "" : enclosing + "#"
                        + (((BlockTree) tree).isStatic() ? "<clinit@" : "<init-block@") + start + ">()";
            }
            if (tree instanceof VariableTree) {
                Element variable = trees.getElement(current);
                if (variable != null && (variable.getKind() == ElementKind.FIELD
                        || variable.getKind() == ElementKind.ENUM_CONSTANT)) {
                    return signature(variable);
                }
            }
            if (tree instanceof MethodTree || tree instanceof ClassTree) return signature(trees.getElement(current));
        }
        return "";
    }

    /** Callable keys use binary names, including nested component types in arrays. */
    private String erasedIdentity(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY)
            return erasedIdentity(((ArrayType) type).getComponentType()) + "[]";
        if (type.getKind() == TypeKind.DECLARED) {
            Element declaration = ((DeclaredType) type).asElement();
            if (declaration instanceof TypeElement) return signature(declaration);
        }
        // An ERROR mirror has no proven binary identity; preserve javac's diagnostic spelling.
        return stableType(type);
    }

    private Map<String, Object> describe(Element element) {
        Map<String, Object> a =
                Json.object(
                        "element_kind",
                        element.getKind().name(),
                        "declared_type",
                        stableType(element.asType()),
                        "type_model",
                        typeModel(element.asType()),
                        "modifiers",
                        strings(element.getModifiers()));
        a.put("annotations", annotations(element));
        if (element instanceof VariableElement) {
            TreePath path = elementPath(element);
            List<Object> written = new ArrayList<Object>();
            if (path != null && modifierAnnotations.containsKey(path.getLeaf()))
                for (AnnotationTree annotation : modifierAnnotations.get(path.getLeaf())) {
                    Element type = trees.getElement(new TreePath(new TreePath(path, annotation), annotation.getAnnotationType()));
                    if (type instanceof TypeElement) written.add(signature(type));
                    else a.put("written_type_complete", false);
                }
            a.put("modifier_annotation_types", written);
        }
        a.put("jvm_descriptor", descriptor(element.asType()));
        if (element instanceof TypeElement) {
            TypeElement type = (TypeElement) element;
            a.put("binary_name", elements.getBinaryName(type).toString());
            a.put("source_name", type.getQualifiedName().toString());
            a.put("type_parameters", strings(type.getTypeParameters()));
            a.put("type_parameter_models", formalModels(type.getTypeParameters()));
            a.put("nesting_kind", type.getNestingKind().name());
            a.put("enclosing_type", signature(type.getEnclosingElement()));
            StringBuilder signature = new StringBuilder(formals(type.getTypeParameters()));
            signature.append(type.getSuperclass().getKind() == TypeKind.NONE
                    ? "Ljava/lang/Object;" : generic(type.getSuperclass()));
            for (TypeMirror parent : type.getInterfaces()) signature.append(generic(parent));
            a.put("jvm_signature", signature.toString());
            a.put("superclass_type_model", typeModel(type.getSuperclass()));
            a.put("interface_type_models", typeModels(type.getInterfaces(), new HashSet<String>(), 0));
        }
        if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            Map<String, Object> originalExecutable = new LinkedHashMap<String, Object>(Json.map(a.get("type_model")));
            Object compilerExecutable = a.get("type_model");
            a.put("return_type", stableType(method.getReturnType()));
            a.put("return_type_model", typeModel(method.getReturnType()));
            List<Object> parameters = new ArrayList<Object>();
            for (VariableElement parameter : method.getParameters())
                parameters.add(typeModel(parameter.asType()));
            a.put("parameter_type_models", parameters);
            a.put("varargs", method.isVarArgs());
            a.put("type_parameters", strings(method.getTypeParameters()));
            a.put("type_parameter_models", formalModels(method.getTypeParameters()));
            StringBuilder signature = new StringBuilder(formals(method.getTypeParameters())).append('(');
            List<Object> parameterAnnotations = new ArrayList<Object>();
            List<String> parameterNames = new ArrayList<String>();
            for (VariableElement parameter : method.getParameters()) {
                signature.append(generic(parameter.asType()));
                parameterAnnotations.add(annotations(parameter));
                parameterNames.add(parameter.getSimpleName().toString());
            }
            signature.append(')').append(generic(method.getReturnType()));
            List<String> exceptions = new ArrayList<String>();
            for (TypeMirror exception : method.getThrownTypes()) {
                signature.append('^').append(generic(exception));
                exceptions.add(descriptor(exception));
            }
            a.put("jvm_signature", signature.toString());
            a.put("parameter_annotations", parameterAnnotations);
            a.put("parameter_names", parameterNames);
            a.put("exceptions", exceptions);
            if (method.getDefaultValue() != null)
                a.put("annotation_default", annotationValue(method.getDefaultValue(), 0));
            TreePath path = elementPath(element);
            if (path != null && path.getLeaf() instanceof MethodTree) {
                MethodTree syntax = (MethodTree) path.getLeaf();
                try {
                    if (syntax.getReturnType() != null)
                        a.put("return_type_model", sourceModifierAnnotations(sourceType(new TreePath(path, syntax.getReturnType()), 0), path));
                    List<Object> writtenParameters = new ArrayList<Object>();
                    for (VariableTree parameter : syntax.getParameters()) {
                        TreePath parameterPath = new TreePath(path, parameter);
                        writtenParameters.add(sourceModifierAnnotations(sourceType(new TreePath(parameterPath, parameter.getType()), 0), parameterPath));
                    }
                    a.put("parameter_type_models", writtenParameters);
                    Map<String, Object> executable = new LinkedHashMap<String, Object>(Json.map(a.get("type_model")));
                    a.put("type_model", executable);
                    executable.put("parameters", writtenParameters);
                    executable.put("return", a.get("return_type_model"));
                    List<Object> exceptionsWritten = new ArrayList<Object>();
                    for (Tree exception : syntax.getThrows()) exceptionsWritten.add(sourceType(new TreePath(path, exception), 0));
                    executable.put("throws", exceptionsWritten);
                    if (syntax.getReceiverParameter() != null) {
                        VariableTree receiver = syntax.getReceiverParameter();
                        TreePath receiverPath = new TreePath(path, receiver);
                        a.put("receiver_type_model", sourceModifierAnnotations(sourceType(new TreePath(receiverPath, receiver.getType()), 0), receiverPath));
                    }
                    if (!originalExecutable.equals(executable) || hasTypeAnnotations(executable, 0))
                        writtenExecutableTypes.put(signature(element), executable);
                    else a.put("type_model", compilerExecutable);
                } catch (WorkerSourceAnnotations.Unavailable incomplete) {
                    a.put("written_type_complete", false);
                }
            }
        }
        if (element instanceof VariableElement) {
            VariableElement variable = (VariableElement) element;
            TreePath path = elementPath(element);
            if (path != null && path.getLeaf() instanceof VariableTree) {
                a.put("has_initializer", ((VariableTree) path.getLeaf()).getInitializer() != null);
                Tree written = ((VariableTree) path.getLeaf()).getType();
                if (written != null) {
                    try {
                        a.put("type_model", sourceModifierAnnotations(sourceType(new TreePath(path, written), 0), path));
                    } catch (WorkerSourceAnnotations.Unavailable incomplete) {
                        a.put("written_type_complete", false);
                    }
                }
            }
            a.put("jvm_signature", generic(variable.asType()));
            if (variable.getConstantValue() != null)
                a.put("constant_value", constant(variable.getConstantValue()));
        }
        return a;
    }

    /** Adapted from Anatomist WorkerTypes: Java 8 loses written type-use wrappers on mirrors. */
    private Map<String, Object> sourceType(TreePath path, int depth) {
        if (depth > 128) throw new IllegalArgumentException("SOURCE_TYPE_DEPTH_BUDGET");
        if(graphTypes && !hasSourceAnnotations(path.getLeaf(),0)) return typeModel(trees.getTypeMirror(path));
        Map<String, Object> out = mutableType(
                graphTypes ? typeModel(trees.getTypeMirror(path)) : typeModel(trees.getTypeMirror(path), new HashSet<String>(), new int[1], depth / 2));
        if (out.containsKey("reason")) return out;
        if (hasSourceAnnotations(path.getLeaf(), graphTypes ? 0 : depth)) sourceStructure(out, path, graphTypes ? depth : depth + 1);
        return out;
    }

    private static boolean hasSourceAnnotations(Tree syntax, int depth) {
        if (depth > 128) throw new IllegalArgumentException("SOURCE_TYPE_DEPTH_BUDGET");
        if (syntax instanceof AnnotatedTypeTree) return true;
        if (syntax instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree type = (ParameterizedTypeTree) syntax;
            if (hasSourceAnnotations(type.getType(), depth + 1)) return true;
            for (Tree argument : type.getTypeArguments())
                if (hasSourceAnnotations(argument, depth + 1)) return true;
        } else if (syntax instanceof ArrayTypeTree)
            return hasSourceAnnotations(((ArrayTypeTree) syntax).getType(), depth + 1);
        else if (syntax instanceof WildcardTree)
            return hasSourceAnnotations(((WildcardTree) syntax).getBound(), depth + 1);
        else if (syntax instanceof MemberSelectTree)
            return hasSourceAnnotations(((MemberSelectTree) syntax).getExpression(), depth + 1);
        else if (syntax instanceof IntersectionTypeTree) {
            for (Tree bound : ((IntersectionTypeTree) syntax).getBounds())
                if (hasSourceAnnotations(bound, depth + 1)) return true;
        } else if (syntax instanceof UnionTypeTree) {
            for (Tree alternative : ((UnionTypeTree) syntax).getTypeAlternatives())
                if (hasSourceAnnotations(alternative, depth + 1)) return true;
        }
        return false;
    }

    private void sourceStructure(Map<String, Object> out, TreePath path, int depth) {
        if (depth > 128) throw new IllegalArgumentException("SOURCE_TYPE_DEPTH_BUDGET");
        Tree syntax = path.getLeaf();
        if (syntax instanceof AnnotatedTypeTree) {
            mergeTypeAnnotations(out, WorkerSourceAnnotations.typeUses(path, trees));
            sourceStructure(out, new TreePath(path, ((AnnotatedTypeTree) syntax).getUnderlyingType()), graphTypes ? depth : depth + 1);
        } else if (syntax instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree type = (ParameterizedTypeTree) syntax;
            List<Object> arguments = new ArrayList<Object>();
            for (Tree argument : type.getTypeArguments()) arguments.add(sourceType(new TreePath(path, argument), depth + 1));
            out.put("arguments", arguments);
            sourceStructure(out, new TreePath(path, type.getType()), graphTypes ? depth : depth + 1);
        } else if (syntax instanceof ArrayTypeTree) {
            out.put("component", sourceType(new TreePath(path, ((ArrayTypeTree) syntax).getType()), depth + 1));
        } else if (syntax instanceof WildcardTree) {
            Tree bound = ((WildcardTree) syntax).getBound();
            if (bound != null) out.put(syntax.getKind() == Tree.Kind.EXTENDS_WILDCARD ? "extends" : "super", sourceType(new TreePath(path, bound), depth + 1));
        } else if (syntax instanceof MemberSelectTree && out.get("enclosing") instanceof Map) {
            out.put("enclosing", sourceType(new TreePath(path, ((MemberSelectTree) syntax).getExpression()), depth + 1));
        } else if (syntax instanceof IntersectionTypeTree || syntax instanceof UnionTypeTree) {
            List<Object> bounds = new ArrayList<Object>();
            List<? extends Tree> syntaxBounds = syntax instanceof IntersectionTypeTree
                    ? ((IntersectionTypeTree) syntax).getBounds() : ((UnionTypeTree) syntax).getTypeAlternatives();
            for (Tree bound : syntaxBounds) bounds.add(sourceType(new TreePath(path, bound), depth + 1));
            out.put(syntax instanceof IntersectionTypeTree ? "bounds" : "alternatives", bounds);
        }
    }

    private Map<String, Object> sourceModifierAnnotations(Map<String, Object> type, TreePath path) {
        List<AnnotationMirror> annotations = WorkerSourceAnnotations.typeUses(path, trees);
        if (annotations.isEmpty()) return type;
        type = mutableType(type);
        Map<String, Object> base = type;
        for (int depth = 0; depth <= 64; depth++) {
            String edge = "ARRAY".equals(base.get("kind")) ? "component"
                    : "DECLARED".equals(base.get("kind")) && base.get("enclosing") instanceof Map ? "enclosing" : "";
            if (edge.isEmpty()) { mergeTypeAnnotations(base, annotations); return type; }
            Map<String, Object> child = mutableType(Json.map(base.get(edge)));
            base.put(edge, child); base = child;
        }
        throw new IllegalArgumentException("SOURCE_TYPE_DEPTH_BUDGET");
    }

    private static Map<String,Object> mutableType(Map<String,Object> type) {
        Map<String,Object> copy=new LinkedHashMap<String,Object>(type);
        // The legacy traversal view hides javac's null lower bound. Written annotation
        // overlays still need that real graph edge when their root becomes a detached map.
        if(TypeGraph.isView(type)) {
            TypeGraph graph=TypeGraph.owner(type);
            Object lower=graph.node(TypeGraph.reference(type)).get("lower");
            if(lower instanceof Number && ((Number)lower).intValue()>=0)
                copy.put("lower",graph.view(((Number)lower).intValue()));
        }
        return copy;
    }

    private boolean hasTypeAnnotations(Object value, int depth) {
        if (depth > Json.MAX_DEPTH) throw new IllegalArgumentException("SOURCE_TYPE_DEPTH_BUDGET");
        if (value instanceof Map) {
            Map<String, Object> type = Json.map(value);
            if (type.get("annotations") instanceof List && !Json.list(type.get("annotations")).isEmpty()) return true;
            for (Object child : type.values()) if (hasTypeAnnotations(child, depth + 1)) return true;
        } else if (value instanceof List) {
            for (Object child : Json.list(value)) if (hasTypeAnnotations(child, depth + 1)) return true;
        }
        return false;
    }

    private void mergeTypeAnnotations(Map<String, Object> type, List<AnnotationMirror> annotations) {
        List<Object> names = new ArrayList<Object>(type.containsKey("annotations") ? Json.list(type.get("annotations")) : Collections.emptyList());
        List<Object> values = new ArrayList<Object>(type.containsKey("annotation_models") ? Json.list(type.get("annotation_models")) : Collections.emptyList());
        for (AnnotationMirror value : annotations) {
            String name = signature(value.getAnnotationType().asElement());
            if (!names.contains(name)) { names.add(name); values.add(annotation(value, 0)); }
        }
        if (!names.isEmpty()) { type.put("annotations", names); type.put("annotation_models", values); }
    }

    private Object constant(Object value) {
        if (value instanceof Character) return (int) ((Character) value).charValue();
        if (value instanceof Float && !Float.isFinite((Float) value)
                || value instanceof Double && !Double.isFinite((Double) value))
            return Json.object("non_finite", value.toString());
        return value;
    }

    private List<Object> annotations(Element element) {
        List<Object> values = new ArrayList<Object>();
        for (AnnotationMirror annotation : element.getAnnotationMirrors())
            values.add(annotation(annotation, 0));
        return values;
    }

    private Map<String, Object> annotation(AnnotationMirror annotation, int depth) {
        if (depth > 16) return Json.object("complete", false);
        Element annotationType = annotation.getAnnotationType().asElement();
        if (annotationType instanceof TypeElement)
            annotationTypes.put(signature(annotationType), (TypeElement) annotationType);
        Map<String, Object> explicit = new TreeMap<String, Object>(), effective = new TreeMap<String, Object>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> item : annotation.getElementValues().entrySet())
            explicit.put(item.getKey().getSimpleName().toString(), annotationValue(item.getValue(), depth + 1));
        for (Element member : annotationType.getEnclosedElements())
            if (member instanceof ExecutableElement) {
                ExecutableElement method = (ExecutableElement) member;
                AnnotationValue value = annotation.getElementValues().get(method);
                if (value == null) value = method.getDefaultValue();
                if (value != null) effective.put(method.getSimpleName().toString(), annotationValue(value, depth + 1));
                else effective.put(method.getSimpleName().toString(), Json.object("kind", "unknown", "complete", false));
            }
        String retention = "CLASS";
        for (AnnotationMirror meta : annotation.getAnnotationType().asElement().getAnnotationMirrors())
            if (meta.getAnnotationType().toString().equals("java.lang.annotation.Retention"))
                for (AnnotationValue value : meta.getElementValues().values()) {
                    if (value.getValue() instanceof VariableElement)
                        retention = ((VariableElement) value.getValue()).getSimpleName().toString();
                }
        return Json.object("type", signature(annotation.getAnnotationType().asElement()),
                "retention", retention, "explicit", explicit, "effective", effective,
                "complete", annotation.getAnnotationType().getKind() != TypeKind.ERROR
                        && annotationValuesComplete(explicit) && annotationValuesComplete(effective));
    }

    private boolean annotationValuesComplete(Object value) {
        if (value instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) value;
            if (Boolean.FALSE.equals(values.get("complete"))) return false;
            for (Object item : values.values()) if (!annotationValuesComplete(item)) return false;
        } else if (value instanceof List) {
            for (Object item : (List<?>) value) if (!annotationValuesComplete(item)) return false;
        }
        return true;
    }

    private Object annotationValue(AnnotationValue annotation, int depth) {
        Object value = annotation.getValue();
        if (depth > 16) return Json.object("kind", "unknown", "complete", false);
        // javac's error value visits as the ordinary string "<error>". Inspect its
        // compiler-owned kind here so a legitimate string literal stays distinguishable.
        if (annotation instanceof com.sun.tools.javac.code.Attribute.Error)
            return Json.object("kind", "unknown", "complete", false);
        Boolean known = annotation.accept(new SimpleAnnotationValueVisitor8<Boolean, Void>(Boolean.TRUE) {
            @Override public Boolean visitUnknown(AnnotationValue value, Void unused) { return Boolean.FALSE; }
        }, null);
        if (!known) return Json.object("kind", "unknown", "complete", false);
        if (value instanceof TypeMirror)
            return Json.object("kind", "class", "name", value.toString(), "descriptor", descriptor((TypeMirror) value),
                    "complete", ((TypeMirror) value).getKind() != TypeKind.ERROR);
        if (value instanceof VariableElement)
            return Json.object("kind", "enum", "type", signature(((VariableElement) value).getEnclosingElement()),
                    "name", ((VariableElement) value).getSimpleName().toString());
        if (value instanceof AnnotationMirror)
            return Json.object("kind", "annotation", "value", annotation((AnnotationMirror) value, depth + 1));
        if (value instanceof List) {
            List<Object> items = new ArrayList<Object>();
            for (Object item : (List<?>) value) items.add(annotationValue((AnnotationValue) item, depth + 1));
            return Json.object("kind", "array", "values", items);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Character
                || value instanceof Long || value instanceof Float || value instanceof Double)
            return Json.object("kind", "primitive", "type", value.getClass().getSimpleName().toLowerCase(Locale.ROOT),
                    "value", constant(value));
        return constant(value);
    }

    private List<Object> formalModels(List<? extends TypeParameterElement> parameters) {
        List<Object> out = new ArrayList<Object>();
        for (TypeParameterElement parameter : parameters)
            out.add(Json.object("name", parameter.getSimpleName().toString(),
                    "owner", signature(parameter.getGenericElement()), "annotations", annotations(parameter),
                    "bounds", typeModels(parameter.getBounds(), new HashSet<String>(), 0)));
        return out;
    }

    private String descriptor(TypeMirror type) {
        if (type == null) return "";
        if (type instanceof ExecutableType) {
            ExecutableType executable = (ExecutableType) type;
            StringBuilder out = new StringBuilder("(");
            for (TypeMirror parameter : executable.getParameterTypes()) out.append(descriptor(parameter));
            return out.append(')').append(descriptor(executable.getReturnType())).toString();
        }
        switch (type.getKind()) {
            case BOOLEAN: return "Z"; case BYTE: return "B"; case CHAR: return "C";
            case SHORT: return "S"; case INT: return "I"; case LONG: return "J";
            case FLOAT: return "F"; case DOUBLE: return "D"; case VOID: return "V";
            case ARRAY: return "[" + descriptor(((ArrayType) type).getComponentType());
            case DECLARED: return "L" + signature(((DeclaredType) type).asElement()).replace('.', '/') + ";";
            case TYPEVAR: case INTERSECTION: return descriptor(types.erasure(type));
            default: return "";
        }
    }

    private String generic(TypeMirror type) {
        if (type.getKind() == TypeKind.TYPEVAR) return "T" + ((TypeVariable) type).asElement().getSimpleName() + ";";
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            return wildcard.getExtendsBound() != null ? "+" + generic(wildcard.getExtendsBound())
                    : wildcard.getSuperBound() != null ? "-" + generic(wildcard.getSuperBound()) : "*";
        }
        if (type.getKind() == TypeKind.ARRAY) return "[" + generic(((ArrayType) type).getComponentType());
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) type;
            TypeElement element = (TypeElement) declared.asElement();
            StringBuilder out;
            TypeMirror enclosing = declared.getEnclosingType();
            if (enclosing instanceof DeclaredType && !element.getModifiers().contains(Modifier.STATIC)) {
                String outer = generic(enclosing);
                out = new StringBuilder(outer.substring(0, outer.length() - 1)).append('.').append(element.getSimpleName());
            } else out = new StringBuilder("L").append(signature(element).replace('.', '/'));
            if (!declared.getTypeArguments().isEmpty()) {
                out.append('<');
                for (TypeMirror argument : declared.getTypeArguments()) out.append(generic(argument));
                out.append('>');
            }
            return out.append(';').toString();
        }
        return descriptor(type);
    }

    private String formals(List<? extends TypeParameterElement> parameters) {
        if (parameters.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<");
        for (TypeParameterElement parameter : parameters) {
            out.append(parameter.getSimpleName()).append(':');
            boolean first = true;
            for (TypeMirror bound : parameter.getBounds()) {
                if (!first || types.asElement(bound) != null && types.asElement(bound).getKind().isInterface())
                    out.append(':');
                out.append(generic(bound));
                first = false;
            }
        }
        return out.append('>').toString();
    }

    /** A bounded, compiler-independent type tree. Recursive F-bounds remain explicit. */
    // Nested maps + argument lists must also fit Rust's bounded 128-container decoder.
    private static final int TYPE_MODEL_DEPTH = 48;
    private static final int TYPE_MODEL_NODES = 4096;

    private Map<String, Object> typeModel(TypeMirror type) {
        if (!graphTypes) return typeModel(type, new HashSet<String>(), new int[1], 0);
        if (type == null) return Json.object("version",3,"kind","UNKNOWN","complete",false);
        CompilerTypeGraph graph=typeGraphs.get(currentSource);
        if(graph==null) {
            graph=new CompilerTypeGraph(elements,this::typeMetadata);
            typeGraphs.put(currentSource,graph);
        }
        try { return graph.view(graph.add(type)); }
        catch(CompilerTypeGraph.Budget exhausted) {
            String reason="TYPE_GRAPH_BUDGET";
            if(typeBudgetDiagnostics.add(currentSource+":"+reason))
                diagnostics.add(Json.object("code",reason,"severity","WARNING","source",currentSource,
                        "start",-1,"end",-1,"phase","type_model","message","Detached type graph exceeded budget"));
            return Json.object("version",3,"kind","UNKNOWN","complete",false,"reason",reason);
        }
    }

    /** Scalar metadata only: graph edges are constructed once by CompilerTypeGraph. */
    private Map<String,Object> typeMetadata(TypeMirror type) {
        Map<String,Object> out=Json.object("display",stableType(type),"descriptor",descriptor(type),"signature",generic(type));
        if(type.getKind()==TypeKind.TYPEVAR) {
            TypeVariable variable=(TypeVariable)type;
            out.put("name",variable.asElement().getSimpleName().toString());
            if(type instanceof com.sun.tools.javac.code.Type.CapturedType) {
                String capture=stableType(type);int bound=capture.indexOf(" of ");
                out.put("owner",currentSource+"#"+(bound<0?capture:capture.substring(0,bound)));out.put("capture",true);
            } else out.put("owner",signature(((TypeParameterElement)variable.asElement()).getGenericElement()));
        }
        List<String> annotations=new ArrayList<String>();List<Object> models=new ArrayList<Object>();
        for(AnnotationMirror mirror:type.getAnnotationMirrors()) {
            annotations.add(mirror.getAnnotationType().toString());models.add(annotation(mirror,0));
        }
        if(!annotations.isEmpty()) {out.put("annotations",annotations);out.put("annotation_models",models);}
        return out;
    }

    private Map<String, Object> typeModel(TypeMirror type, Set<String> visiting, int[] nodes, int depth) {
        if (type == null) return Json.object("kind", "UNKNOWN", "complete", false);
        String kind = type.getKind().name();
        Map<String, Object> out =
                Json.object("version", 2, "kind", kind, "display", stableType(type),
                        "complete", type.getKind() != TypeKind.ERROR);
        String identity = kind + ":" + stableType(type);
        if (type.getKind() == TypeKind.TYPEVAR) {
            TypeVariable variable = (TypeVariable) type;
            out.put("name", variable.asElement().getSimpleName().toString());
            if (type instanceof com.sun.tools.javac.code.Type.CapturedType) {
                String capture = stableType(type);
                int bound = capture.indexOf(" of ");
                out.put("owner", currentSource + "#" + (bound < 0 ? capture : capture.substring(0,bound)));
                out.put("capture", true);
            } else out.put("owner", signature(((TypeParameterElement) variable.asElement()).getGenericElement()));
            identity = kind + ":" + out.get("owner") + ":" + out.get("name");
        }
        boolean truncated = depth >= TYPE_MODEL_DEPTH || ++nodes[0] > TYPE_MODEL_NODES;
        if (truncated || !visiting.add(identity)) {
            if (type.getKind() == TypeKind.DECLARED) {
                Element declaration = ((DeclaredType) type).asElement();
                out.put("element", signature(declaration));
                out.put("element_kind", declaration.getKind().name());
            }
            if (truncated) {
                String reason = depth >= TYPE_MODEL_DEPTH
                        ? "TYPE_MODEL_DEPTH_BUDGET" : "TYPE_MODEL_NODE_BUDGET";
                out.put("reason", reason);
                if (typeBudgetDiagnostics.add(currentSource + ":" + reason))
                    diagnostics.add(Json.object("code", reason, "severity", "WARNING",
                            "source", currentSource, "start", -1, "end", -1,
                            "phase", "type_model", "message", "Detached type model exceeded its bounded representation",
                            "limit", depth >= TYPE_MODEL_DEPTH ? TYPE_MODEL_DEPTH : TYPE_MODEL_NODES));
            }
            else out.put("recursive", true);
            out.put("complete", !truncated);
            return out;
        }
        try {
            if (type.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) type;
                out.put("element", signature(declared.asElement()));
                out.put("element_kind", declared.asElement().getKind().name());
                out.put("arguments", typeModels(declared.getTypeArguments(), visiting, nodes, depth + 1));
                if (declared.getEnclosingType().getKind() != TypeKind.NONE)
                    out.put("enclosing", typeModel(declared.getEnclosingType(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.ARRAY) {
                out.put("component", typeModel(((ArrayType) type).getComponentType(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.WILDCARD) {
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null)
                    out.put("extends", typeModel(wildcard.getExtendsBound(), visiting, nodes, depth + 1));
                if (wildcard.getSuperBound() != null)
                    out.put("super", typeModel(wildcard.getSuperBound(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.TYPEVAR) {
                TypeVariable variable = (TypeVariable) type;
                List<? extends TypeMirror> bounds = ((TypeParameterElement) variable.asElement()).getBounds();
                TypeMirror upper = type instanceof com.sun.tools.javac.code.Type.CapturedType
                        ? variable.getUpperBound() : bounds.size() == 1 ? bounds.get(0) : variable.getUpperBound();
                out.put("upper", typeModel(upper, visiting, nodes, depth + 1));
                TypeMirror lower = variable.getLowerBound();
                if (lower == null) {
                    out.put("complete", false);
                    out.put("reason", "TYPE_VARIABLE_LOWER_BOUND_UNAVAILABLE");
                } else if (lower.getKind() != TypeKind.NULL)
                    out.put("lower", typeModel(lower, visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.INTERSECTION) {
                out.put("bounds", typeModels(((IntersectionType) type).getBounds(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.UNION) {
                out.put("alternatives", typeModels(((UnionType) type).getAlternatives(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.EXECUTABLE) {
                ExecutableType executable = (ExecutableType) type;
                out.put("return", typeModel(executable.getReturnType(), visiting, nodes, depth + 1));
                out.put("parameters", typeModels(executable.getParameterTypes(), visiting, nodes, depth + 1));
                out.put("throws", typeModels(executable.getThrownTypes(), visiting, nodes, depth + 1));
                out.put("type_variables", typeModels(executable.getTypeVariables(), visiting, nodes, depth + 1));
            } else if (type.getKind() == TypeKind.ERROR) {
                out.put("element", signature(((ErrorType) type).asElement()));
            }
            List<String> annotations = new ArrayList<String>();
            for (AnnotationMirror annotation : type.getAnnotationMirrors())
                annotations.add(annotation.getAnnotationType().toString());
            if (!annotations.isEmpty()) {
                out.put("annotations", annotations);
                List<Object> annotationModels = new ArrayList<Object>();
                for (AnnotationMirror mirror : type.getAnnotationMirrors())
                    annotationModels.add(annotation(mirror, 0));
                out.put("annotation_models", annotationModels);
            }
            out.put("descriptor", descriptor(type));
            out.put("signature", generic(type));
            for (Object child : out.values()) {
                if (child instanceof Map && Boolean.FALSE.equals(Json.map(child).get("complete")))
                    out.put("complete", false);
                else if (child instanceof List)
                    for (Object item : Json.list(child))
                        if (item instanceof Map && Boolean.FALSE.equals(Json.map(item).get("complete")))
                            out.put("complete", false);
            }
            return out;
        } finally {
            visiting.remove(identity);
        }
    }

    private List<Object> typeModels(Iterable<? extends TypeMirror> types, Set<String> visiting, int depth) {
        return typeModels(types, visiting, new int[1], depth);
    }

    private List<Object> typeModels(Iterable<? extends TypeMirror> types, Set<String> visiting, int[] nodes, int depth) {
        List<Object> out = new ArrayList<Object>();
        for (TypeMirror type : types) {
            out.add(typeModel(type, visiting, nodes, depth));
            if (nodes[0] > TYPE_MODEL_NODES) break;
        }
        return out;
    }

    private Map<String, Object> sam(TypeMirror target) {
        if (target instanceof IntersectionType) {
            Map<String, Object> selected = null;
            for (TypeMirror bound : ((IntersectionType) target).getBounds()) {
                Map<String, Object> candidate = sam(bound);
                if (!Boolean.TRUE.equals(candidate.get("complete"))) continue;
                if (selected != null && !Objects.equals(selected.get("method"), candidate.get("method")))
                    return Json.object("complete", false, "reason", "SAM_INTERSECTION_AMBIGUOUS");
                selected = candidate;
            }
            return selected == null
                    ? Json.object("complete", false, "reason", "SAM_INTERSECTION_UNRESOLVED")
                    : selected;
        }
        if (!(target instanceof DeclaredType))
            return Json.object("complete", false, "reason", "SAM_TARGET_NOT_DECLARED");
        DeclaredType declared = (DeclaredType) target;
        if (!(declared.asElement() instanceof TypeElement))
            return Json.object("complete", false, "reason", "SAM_TARGET_NOT_TYPE");
        TypeElement type = (TypeElement) declared.asElement();
        Map<String, ExecutableElement> abstractMethods = new LinkedHashMap<String, ExecutableElement>();
        for (Element member : elements.getAllMembers(type)) {
            if (!(member instanceof ExecutableElement)
                    || member.getKind() != ElementKind.METHOD
                    || !member.getModifiers().contains(Modifier.ABSTRACT)
                    || member.getModifiers().contains(Modifier.STATIC)) continue;
            ExecutableElement method = (ExecutableElement) member;
            boolean objectMethod = false;
            for (Element objectMember : elements.getTypeElement("java.lang.Object").getEnclosedElements())
                if (objectMember instanceof ExecutableElement
                        && elements.overrides(method, (ExecutableElement) objectMember, type)) {
                    objectMethod = true;
                    break;
                }
            if (objectMethod) continue;
            StringBuilder key = new StringBuilder(method.getSimpleName()).append('(');
            for (VariableElement parameter : method.getParameters())
                key.append(types.erasure(parameter.asType())).append(';');
            key.append(')');
            abstractMethods.putIfAbsent(key.toString(), method);
        }
        if (abstractMethods.size() != 1)
            return Json.object("complete", false, "reason", "SAM_METHOD_COUNT", "count", abstractMethods.size());
        ExecutableElement method = abstractMethods.values().iterator().next();
        TypeMirror instantiated = types.asMemberOf(declared, method);
        external(method);
        return Json.object("complete", !incomplete(instantiated, 0), "method", signature(method),
                "instantiated_type", typeModel(instantiated));
    }

    private static List<String> strings(Iterable<?> values) {
        List<String> out = new ArrayList<String>();
        for (Object v : values) out.add(v.toString());
        return out;
    }

    private void external(Element element) {
        String key = signature(element);
        if (key.isEmpty() || elementPath(element) != null || external.containsKey(key)) return;
        budget();
        external.put(
                key,
                Json.object(
                        "signature",
                        key,
                        "name",
                        element.getSimpleName().toString(),
                        "attributes",
                        describe(element)));
    }

    private boolean incomplete(TypeMirror t, int depth) {
        if (t == null || depth > 16) return true;
        if (t.getKind() == TypeKind.ERROR) return true;
        if (t instanceof ArrayType)
            return incomplete(((ArrayType) t).getComponentType(), depth + 1);
        if (t instanceof ExecutableType) {
            ExecutableType e = (ExecutableType) t;
            if (incomplete(e.getReturnType(), depth + 1)) return true;
            for (TypeMirror p : e.getParameterTypes()) if (incomplete(p, depth + 1)) return true;
        }
        return false;
    }

    /** Child roles retain source syntax needed by later passes without holding compiler trees. */
    private Map<String,Object> roles(Tree tree) {
        Map<String,Object> roles = new LinkedHashMap<String,Object>();
        if (tree instanceof CompilationUnitTree) {
            CompilationUnitTree n=(CompilationUnitTree)tree;
            role(roles,"types",n.getTypeDecls()); role(roles,"imports",n.getImports()); role(roles,"package",n.getPackageName());
        } else if (tree instanceof ClassTree) {
            ClassTree n=(ClassTree)tree;
            role(roles,"modifiers",n.getModifiers()); role(roles,"type_parameters",n.getTypeParameters());
            role(roles,"extends",n.getExtendsClause()); role(roles,"implements",n.getImplementsClause()); role(roles,"members",n.getMembers());
        } else if (tree instanceof MethodTree) {
            MethodTree n=(MethodTree)tree;
            role(roles,"modifiers",n.getModifiers()); role(roles,"return_type",n.getReturnType());
            role(roles,"type_parameters",n.getTypeParameters()); role(roles,"parameters",n.getParameters());
            role(roles,"receiver",n.getReceiverParameter()); role(roles,"throws",n.getThrows());
            role(roles,"body",n.getBody()); role(roles,"default",n.getDefaultValue());
        } else if (tree instanceof VariableTree) {
            VariableTree n=(VariableTree)tree; role(roles,"modifiers",n.getModifiers()); role(roles,"type",n.getType()); role(roles,"initializer",n.getInitializer());
        } else if (tree instanceof BlockTree) role(roles,"statements",((BlockTree)tree).getStatements());
        else if (tree instanceof ExpressionStatementTree) role(roles,"expression",((ExpressionStatementTree)tree).getExpression());
        else if (tree instanceof ReturnTree) role(roles,"expression",((ReturnTree)tree).getExpression());
        else if (tree instanceof ThrowTree) role(roles,"expression",((ThrowTree)tree).getExpression());
        else if (tree instanceof IfTree) {
            IfTree n=(IfTree)tree; role(roles,"condition",n.getCondition()); role(roles,"then",n.getThenStatement()); role(roles,"else",n.getElseStatement());
        } else if (tree instanceof ForLoopTree) {
            ForLoopTree n=(ForLoopTree)tree; role(roles,"initializer",n.getInitializer()); role(roles,"condition",n.getCondition()); role(roles,"update",n.getUpdate()); role(roles,"body",n.getStatement());
        } else if (tree instanceof EnhancedForLoopTree) {
            EnhancedForLoopTree n=(EnhancedForLoopTree)tree; role(roles,"variable",n.getVariable()); role(roles,"expression",n.getExpression()); role(roles,"body",n.getStatement());
        } else if (tree instanceof WhileLoopTree) {
            WhileLoopTree n=(WhileLoopTree)tree; role(roles,"condition",n.getCondition()); role(roles,"body",n.getStatement());
        } else if (tree instanceof DoWhileLoopTree) {
            DoWhileLoopTree n=(DoWhileLoopTree)tree; role(roles,"condition",n.getCondition()); role(roles,"body",n.getStatement());
        } else if (tree instanceof SwitchTree) {
            SwitchTree n=(SwitchTree)tree; role(roles,"expression",n.getExpression()); role(roles,"cases",n.getCases());
        } else if (tree instanceof CaseTree) {
            CaseTree n=(CaseTree)tree; role(roles,"expression",n.getExpression()); role(roles,"statements",n.getStatements());
        } else if (tree instanceof TryTree) {
            TryTree n=(TryTree)tree; role(roles,"resources",n.getResources()); role(roles,"body",n.getBlock()); role(roles,"catches",n.getCatches()); role(roles,"finally",n.getFinallyBlock());
        } else if (tree instanceof CatchTree) {
            CatchTree n=(CatchTree)tree; role(roles,"parameter",n.getParameter()); role(roles,"body",n.getBlock());
        } else if (tree instanceof SynchronizedTree) {
            SynchronizedTree n=(SynchronizedTree)tree; role(roles,"expression",n.getExpression()); role(roles,"body",n.getBlock());
        } else if (tree instanceof AssertTree) {
            AssertTree n=(AssertTree)tree; role(roles,"condition",n.getCondition()); role(roles,"detail",n.getDetail());
        } else if (tree instanceof LabeledStatementTree) role(roles,"body",((LabeledStatementTree)tree).getStatement());
        else if (tree instanceof MethodInvocationTree) {
            MethodInvocationTree n=(MethodInvocationTree)tree; role(roles,"selection",n.getMethodSelect()); role(roles,"arguments",n.getArguments()); role(roles,"type_arguments",n.getTypeArguments());
        } else if (tree instanceof NewClassTree) {
            NewClassTree n=(NewClassTree)tree; role(roles,"enclosing",n.getEnclosingExpression()); role(roles,"type",n.getIdentifier()); role(roles,"arguments",n.getArguments()); role(roles,"type_arguments",n.getTypeArguments()); role(roles,"body",n.getClassBody());
        } else if (tree instanceof NewArrayTree) {
            NewArrayTree n=(NewArrayTree)tree; role(roles,"type",n.getType()); role(roles,"dimensions",n.getDimensions()); role(roles,"initializers",n.getInitializers());
        } else if (tree instanceof MemberSelectTree) role(roles,"expression",((MemberSelectTree)tree).getExpression());
        else if (tree instanceof MemberReferenceTree) {
            MemberReferenceTree n=(MemberReferenceTree)tree; role(roles,"qualifier",n.getQualifierExpression()); role(roles,"type_arguments",n.getTypeArguments());
        } else if (tree instanceof LambdaExpressionTree) {
            LambdaExpressionTree n=(LambdaExpressionTree)tree; role(roles,"parameters",n.getParameters()); role(roles,"body",n.getBody());
        } else if (tree instanceof AssignmentTree) {
            AssignmentTree n=(AssignmentTree)tree; role(roles,"variable",n.getVariable()); role(roles,"expression",n.getExpression());
        } else if (tree instanceof CompoundAssignmentTree) {
            CompoundAssignmentTree n=(CompoundAssignmentTree)tree; role(roles,"variable",n.getVariable()); role(roles,"expression",n.getExpression());
        } else if (tree instanceof UnaryTree) role(roles,"expression",((UnaryTree)tree).getExpression());
        else if (tree instanceof BinaryTree) {
            BinaryTree n=(BinaryTree)tree; role(roles,"left",n.getLeftOperand()); role(roles,"right",n.getRightOperand());
        } else if (tree instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree n=(ConditionalExpressionTree)tree; role(roles,"condition",n.getCondition()); role(roles,"then",n.getTrueExpression()); role(roles,"else",n.getFalseExpression());
        } else if (tree instanceof TypeCastTree) {
            TypeCastTree n=(TypeCastTree)tree; role(roles,"type",n.getType()); role(roles,"expression",n.getExpression());
        } else if (tree instanceof InstanceOfTree) {
            InstanceOfTree n=(InstanceOfTree)tree; role(roles,"expression",n.getExpression()); role(roles,"type",n.getType());
        } else if (tree instanceof ParenthesizedTree) role(roles,"expression",((ParenthesizedTree)tree).getExpression());
        else if (tree instanceof AnnotationTree) {
            AnnotationTree n=(AnnotationTree)tree; role(roles,"type",n.getAnnotationType()); role(roles,"arguments",n.getArguments());
        } else if (tree instanceof ArrayAccessTree) {
            ArrayAccessTree n=(ArrayAccessTree)tree; role(roles,"expression",n.getExpression()); role(roles,"index",n.getIndex());
        } else if (tree instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree n=(ParameterizedTypeTree)tree; role(roles,"type",n.getType()); role(roles,"type_arguments",n.getTypeArguments());
        } else if (tree instanceof ArrayTypeTree) role(roles,"type",((ArrayTypeTree)tree).getType());
        else if (tree instanceof UnionTypeTree) role(roles,"alternatives",((UnionTypeTree)tree).getTypeAlternatives());
        else if (tree instanceof IntersectionTypeTree) role(roles,"bounds",((IntersectionTypeTree)tree).getBounds());
        else if (tree instanceof TypeParameterTree) role(roles,"bounds",((TypeParameterTree)tree).getBounds());
        else if (tree instanceof WildcardTree) role(roles,"bound",((WildcardTree)tree).getBound());
        else if (tree instanceof ModifiersTree) role(roles,"annotations",((ModifiersTree)tree).getAnnotations());
        else if (tree instanceof AnnotatedTypeTree) {
            AnnotatedTypeTree n=(AnnotatedTypeTree)tree; role(roles,"type",n.getUnderlyingType()); role(roles,"annotations",n.getAnnotations());
        } else if (tree instanceof ImportTree) role(roles,"type",((ImportTree)tree).getQualifiedIdentifier());
        return roles;
    }
    private void role(Map<String,Object> roles,String name,Tree tree) {
        Integer id=treeIds.get(tree); if (id!=null) roles.put(name,id);
    }
    private void role(Map<String,Object> roles,String name,List<? extends Tree> trees) {
        if (trees==null) return;
        List<Object> ids=new ArrayList<Object>();
        for (Tree tree:trees) {Integer id=treeIds.get(tree); if(id!=null) ids.add(id);}
        roles.put(name,ids);
    }

    private final class Scanner extends TreePathScanner<Void, Void> {
        final CompilationUnitTree unit;
        final Map<String, Object> source;
        final java.net.URI sourceUri;
        final boolean declarationsOnly;
        final boolean emitBody;
        String owner = "";
        boolean callbackCreation;
        final Deque<Tree> lexical = new ArrayDeque<Tree>();

        @Override
        public Void scan(Tree tree, Void p) {
            if (tree == null) return null;
            if (!declarationsOnly && emitBody && original.containsKey(tree)) {
                Map<String, Object> record = Json.object("id", treeIds.get(tree), "parent",
                        lexical.isEmpty() ? 0 : treeIds.containsKey(lexical.peek()) ? treeIds.get(lexical.peek()) : 0,
                        "kind", tree.getKind().name(), "source", source.get("path"),
                        "start", start(tree), "end", end(tree), "owner", owner);
                if (tree instanceof LiteralTree)
                    record.put("value", constant(((LiteralTree) tree).getValue()));
                if (tree instanceof IdentifierTree)
                    record.put("name", ((IdentifierTree) tree).getName().toString());
                if (tree instanceof MemberSelectTree)
                    record.put("name", ((MemberSelectTree) tree).getIdentifier().toString());
                if (tree instanceof VariableTree) record.put("name",((VariableTree)tree).getName().toString());
                if (tree instanceof MethodTree) record.put("name",((MethodTree)tree).getName().toString());
                if (tree instanceof ClassTree) record.put("name",((ClassTree)tree).getSimpleName().toString());
                if (tree instanceof MemberReferenceTree) record.put("name",((MemberReferenceTree)tree).getName().toString());
                if (tree instanceof PrimitiveTypeTree) record.put("name", ((PrimitiveTypeTree) tree).getPrimitiveTypeKind().name().toLowerCase(Locale.ROOT));
                record.put("roles", roles(tree));
                TreePath path = tree == unit ? new TreePath(unit) : new TreePath(getCurrentPath(),tree);
                Element symbol = trees.getElement(path);
                if (symbol != null) record.put("symbol",signature(symbol));
                syntax.add(record);
            }
            lexical.push(tree);
            try { return super.scan(tree, p); }
            finally { lexical.pop(); }
        }

        private String control(Tree site) {
            List<String> parts = new ArrayList<String>();
            Tree child = site;
            TreePath path = getCurrentPath().getParentPath();
            while (path != null) {
                Tree parent = path.getLeaf();
                if (parent instanceof MethodTree || parent instanceof ClassTree
                        || parent instanceof LambdaExpressionTree) break;
                String role = null;
                if (parent instanceof IfTree) {
                    IfTree branch = (IfTree) parent;
                    if (branch.getThenStatement() == child) role = "if-then";
                    else if (branch.getElseStatement() == child) role = "if-else";
                } else if (parent instanceof ConditionalExpressionTree) {
                    ConditionalExpressionTree branch = (ConditionalExpressionTree) parent;
                    if (branch.getTrueExpression() == child) role = "ternary-then";
                    else if (branch.getFalseExpression() == child) role = "ternary-else";
                } else if (parent instanceof TryTree) {
                    TryTree branch = (TryTree) parent;
                    if (branch.getBlock() == child) role = "try";
                    else if (branch.getFinallyBlock() == child) role = "finally";
                } else if (parent instanceof ForLoopTree) role = "for";
                else if (parent instanceof EnhancedForLoopTree) role = "foreach";
                else if (parent instanceof WhileLoopTree) role = "while";
                else if (parent instanceof DoWhileLoopTree) role = "do";
                else if (parent instanceof CatchTree) role = "catch";
                else if (parent instanceof SynchronizedTree) role = "synchronized";
                else if (parent instanceof CaseTree)
                    role = ((CaseTree) parent).getExpression() == null ? "default" : "case";
                if (role != null && start(parent) >= 0)
                    parts.add(role + "@L" + unit.getLineMap().getLineNumber(start(parent)));
                child = parent;
                path = path.getParentPath();
            }
            Collections.reverse(parts);
            return String.join(">", parts);
        }

        Scanner(CompilationUnitTree unit, boolean declarationsOnly) {
            this.unit = unit;
            this.sourceUri = listener.sourceUri(unit.getSourceFile());
            this.declarationsOnly = declarationsOnly;
            source =
                    sources.get(
                            Paths.get(unit.getSourceFile().toUri())
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString());
            if (source == null) throw new IllegalStateException("IMPLICIT_SOURCE_REJECTED");
            emitBody = fullAuxiliaryModel || !Boolean.FALSE.equals(source.get("publish"));
        }

        private long start(Tree tree) {
            return original.containsKey(tree)
                    ? trees.getSourcePositions().getStartPosition(unit, tree)
                    : -1;
        }

        private long end(Tree tree) {
            return original.containsKey(tree)
                    ? trees.getSourcePositions().getEndPosition(unit, tree)
                    : -1;
        }

        private Map<String, Object> node(String kind, Tree tree) {
            budget();
            return Json.object(
                    "kind",
                    kind,
                    "source",
                    source.get("path"),
                    "start",
                    start(tree),
                    "end",
                    end(tree),
                    "parent",
                    owner,
                    "attributes",
                    Json.object("tree_id", treeIds.get(tree), "control", control(tree)));
        }

        private Element element() {
            TreePath path = getCurrentPath();
            Element element = trees.getElement(path);
            Tree leaf = path.getLeaf();
            if (element != null && (leaf instanceof ClassTree || leaf instanceof MethodTree || leaf instanceof VariableTree))
                elementPaths.put(element, path);
            return element;
        }

        private String declaration(Tree tree, Element e) {
            String sig = signature(e);
            if (sig.isEmpty()) return owner;
            if (declarationsOnly) {
                Map<String, Object> out = node("declaration", tree);
                out.put("signature", sig);
                out.put("name", e.getSimpleName().toString());
                out.put("attributes", describe(e));
                out.put("generated", !original.containsKey(tree));
                List<Object> annotations = Json.list(Json.map(out.get("attributes")).get("annotations"));
                if (!annotations.isEmpty()) declarationAnnotations.put(sig, annotations);
                declarations.add(out);
            }
            return sig;
        }

        @Override
        public Void visitClass(ClassTree tree, Void p) {
            Element e = element();
            String old = owner;
            if (e != null) owner = declaration(tree, e);
            if (!declarationsOnly && e instanceof TypeElement) {
                TypeElement type = (TypeElement) e;
                List<? extends TypeMirror> parents = types.directSupertypes(type.asType());
                for (TypeMirror parent : parents) {
                    Element target = types.asElement(parent);
                    if (target == null || parent.getKind() == TypeKind.ERROR) continue;
                    String targetName = signature(target);
                    // Match the declaration/syntax graph: Object/Enum implicit roots are
                    // represented by the type model, while an explicit extends Object is an edge.
                    if (targetName.equals("java.lang.Enum")
                            || targetName.equals("java.lang.Object")
                                    && (tree.getExtendsClause() == null
                                        || type.getNestingKind() == NestingKind.ANONYMOUS)) continue;
                    external(target);
                    Map<String, Object> rel = node("relation", tree);
                    rel.put("parent", owner);
                    rel.put("target", signature(target));
                    rel.put("relation", !type.getKind().isInterface() && target.getKind().isInterface()
                            ? "implements" : "extends");
                    if (emitBody) observations.add(rel);
                }
            }
            super.visitClass(tree, p);
            if (declarationsOnly && e != null && e.getKind() == ElementKind.ENUM)
                for (Element member : e.getEnclosedElements()) {
                    if (!(member instanceof ExecutableElement)
                            || !(member.getSimpleName().contentEquals("values")
                                    || member.getSimpleName().contentEquals("valueOf"))
                            || trees.getTree(member) != null) continue;
                    Map<String, Object> declaration = node("declaration", tree);
                    declaration.put("start", -1);
                    declaration.put("end", -1);
                    declaration.put("signature", signature(member));
                    declaration.put("name", member.getSimpleName().toString());
                    declaration.put("generated", true);
                    Map<String, Object> attributes = describe(member);
                    attributes.put("compiler_implicit", true);
                    attributes.put("body_available", false);
                    if (member.getSimpleName().contentEquals("valueOf"))
                        attributes.put("parameter_names", Arrays.asList("name"));
                    declaration.put("attributes", attributes);
                    declarations.add(declaration);
                }
            owner = old;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree tree, Void p) {
            Element e = element();
            String old = owner;
            if (e != null) owner = declaration(tree, e);
            if (!declarationsOnly
                    && e instanceof ExecutableElement
                    && e.getEnclosingElement() instanceof TypeElement) {
                TypeElement type = (TypeElement) e.getEnclosingElement();
                Set<String> seen = new HashSet<String>();
                Deque<TypeMirror> queue =
                        new ArrayDeque<TypeMirror>(types.directSupertypes(type.asType()));
                while (!queue.isEmpty()) {
                    TypeMirror parent = queue.remove();
                    if (!seen.add(parent.toString())) continue;
                    if (seen.size() > 1000) throw new IllegalStateException("INHERITANCE_BUDGET");
                    Element pe = types.asElement(parent);
                    if (!(pe instanceof TypeElement)) continue;
                    for (Element member : pe.getEnclosedElements())
                        if (member instanceof ExecutableElement
                                && elements.overrides(
                                        (ExecutableElement) e, (ExecutableElement) member, type)) {
                            external(member);
                            Map<String, Object> rel = node("relation", tree);
                            rel.put("target", signature(member));
                            rel.put("relation", "overrides");
                            if (emitBody) observations.add(rel);
                        }
                    queue.addAll(types.directSupertypes(parent));
                }
            }
            super.visitMethod(tree, p);
            owner = old;
            return null;
        }

        private String synthetic(Tree tree, String name, String kind) {
            String signature = owner + "#" + name;
            if (declarationsOnly) {
                Map<String, Object> d = node("declaration", tree);
                d.put("signature", signature);
                d.put("name", name);
                d.put("generated", false);
                d.put(
                        "attributes",
                        Json.object(
                                "element_kind",
                                kind,
                                "declared_type",
                                "",
                                "modifiers",
                                Collections.emptyList()));
                declarations.add(d);
            }
            return signature;
        }

        @Override
        public Void visitVariable(VariableTree tree, Void p) {
            Element e = element();
            String old = owner;
            boolean field =
                    e != null
                            && (e.getKind() == ElementKind.FIELD
                                    || e.getKind() == ElementKind.ENUM_CONSTANT);
            String declared = e == null ? "" : declaration(tree, e);
            if (e != null) owner = declared;
            scan(tree.getModifiers(), p);
            // Parameter declarations retain their own annotations/navigation, while
            // their written signature types belong to the method or callback.
            if (e != null && e.getKind() == ElementKind.PARAMETER) owner = old;
            scan(tree.getType(), p);
            if (field && tree.getInitializer() != null) owner = declared;
            else owner = old;
            scan(tree.getInitializer(), p);
            owner = old;
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void p) {
            String old = owner;
            boolean oldCreation = callbackCreation;
            callbackCreation = false;
            owner = synthetic(tree, "<lambda@" + start(tree) + ">()", "LAMBDA");
            if (declarationsOnly && !declarations.isEmpty()) {
                Map<String, Object> declaration = Json.map(declarations.get(declarations.size() - 1));
                Map<String, Object> attributes = Json.map(declaration.get("attributes"));
                TypeMirror target = trees.getTypeMirror(getCurrentPath());
                attributes.put("functional_type_model", typeModel(target));
                attributes.put("sam", sam(target));
            }
            super.visitLambdaExpression(tree, p);
            callbackCreation = oldCreation;
            owner = old;
            return null;
        }

        @Override
        public Void visitBlock(BlockTree tree, Void p) {
            String old = owner;
            TreePath parent = getCurrentPath().getParentPath();
            if (parent != null && parent.getLeaf() instanceof ClassTree)
                owner =
                        synthetic(
                                tree,
                                (tree.isStatic() ? "<clinit@" : "<init-block@")
                                        + start(tree)
                                        + ">()",
                                "INITIALIZER");
            super.visitBlock(tree, p);
            owner = old;
            return null;
        }

        private void call(Tree tree, Tree selection, String mechanism) {
            if (declarationsOnly || !original.containsKey(tree)) return;
            // Enum initializers have a source range, but javac invents their type identifier.
            // Keep real calls in constructor arguments; the synthetic new-expression is no site.
            if (tree instanceof NewClassTree) {
                Tree identifier = ((NewClassTree) tree).getIdentifier();
                if (identifier == null || start(identifier) < 0 || end(identifier) < start(identifier))
                    return;
            }
            TreePath selectedPath =
                    selection == tree
                            ? getCurrentPath()
                            : new TreePath(getCurrentPath(), selection);
            Element selectedDeclaration = trees.getElement(selectedPath);
            Element target = trees.getElement(getCurrentPath());
            if (!emitBody) { if (target != null) external(target); return; }
            TypeMirror selectedType = trees.getTypeMirror(selectedPath);
            boolean resolved = target instanceof ExecutableElement
                    && overloadEvidence.selectionKnown(tree, getCurrentPath(), (ExecutableElement) target);
            boolean complete = !incomplete(selectedType, 0);
            List<String> errors = new ArrayList<String>();
            long begin = start(tree), finish = end(tree);
            Tree selectedTree = tree instanceof NewClassTree ? ((NewClassTree) tree).getIdentifier() : selection;
            long selectedBegin = start(selectedTree), selectedEnd = end(selectedTree);
            for (Diagnostic<? extends JavaFileObject> d : listener.errors(sourceUri, begin, finish)) {
                long pos = d.getPosition();
                // Compiler positions and source intervals use an exclusive end.
                // An outer member's error may sit exactly after this receiver call.
                if (pos >= begin && pos < finish) {
                    errors.add(d.getCode());
                    complete = false;
                    String code = d.getCode();
                    if (pos >= selectedBegin && pos < selectedEnd && (code.contains("ambiguous")
                            || code.contains("cant.apply")
                            || code.contains("invalid.mref")
                            || code.contains("report.access")
                            || code.contains("not.def.access")
                            || code.contains("has.private.access")
                            || code.contains("has.protected.access")
                            || code.contains("non-static.cant.be.ref")
                            || code.contains("cant.resolve")
                            || code.contains("prob.found.req"))) resolved = false;
                }
            }
            Map<String, Object> out = node("call", tree);
            if (tree instanceof MethodInvocationTree) {
                String selectedName = selection instanceof IdentifierTree
                        ? ((IdentifierTree) selection).getName().toString()
                        : selection instanceof MemberSelectTree
                                ? ((MemberSelectTree) selection).getIdentifier().toString() : "";
                TreePath parent = getCurrentPath().getParentPath();
                if ((selectedName.equals("this") || selectedName.equals("super"))
                        && parent != null && parent.getLeaf() instanceof ExpressionStatementTree
                        && start(parent.getLeaf()) >= 0 && end(parent.getLeaf()) >= start(parent.getLeaf())) {
                    out.put("start", start(parent.getLeaf()));
                    out.put("end", end(parent.getLeaf()));
                }
            }
            out.put("target", resolved ? signature(target) : "");
            out.put("name", tree instanceof NewClassTree
                    ? ((NewClassTree) tree).getIdentifier().toString() : selection.toString());
            out.put("resolved", resolved);
            out.put("complete", complete && resolved);
            Json.map(out.get("attributes")).putAll(
                    Json.object(
                            "mechanism",
                            mechanism,
                            "expression_type",
                            stableType(trees.getTypeMirror(getCurrentPath())),
                            "expression_type_model",
                            typeModel(trees.getTypeMirror(getCurrentPath())),
                            "selected_type",
                            selectedType instanceof ExecutableType ? stableType(selectedType) : null,
                            "selected_type_model",
                            typeModel(selectedType),
                            "declared_type",
                            target == null ? "" : stableType(target.asType()),
                            "declared_type_model",
                            target == null ? null : writtenExecutableTypes.containsKey(signature(target))
                                    ? writtenExecutableTypes.get(signature(target)) : typeModel(target.asType()),
                            "binding_origin",
                            "javac"));
            Map<String, Object> attributes = Json.map(out.get("attributes"));
            attributes.put("javac_selected_declaration", selectedDeclaration instanceof ExecutableElement ? signature(selectedDeclaration) : "");
            attributes.put("diagnostic_codes", errors);
            String failure = resolved ? complete ? "none" : "incomplete_type" : "unresolved";
            for (String code : errors) {
                if (code.contains("ambiguous")) { failure = "ambiguous"; break; }
                if (code.contains("report.access") || code.contains("not.def.access")
                        || code.contains("has.private.access") || code.contains("has.protected.access")) failure = "inaccessible";
                else if (code.contains("non-static.cant.be.ref")) failure = "static_context";
                else if (!failure.equals("inaccessible") && (code.contains("cant.apply") || code.contains("prob.found.req") || code.contains("invalid.mref"))) failure = "inapplicable";
                else if (failure.equals("unresolved") && code.contains("cant.resolve")) failure = "missing_symbol";
            }
            attributes.put("binding_failure", failure);
            attributes.put("lookup_name", selection instanceof IdentifierTree ? ((IdentifierTree) selection).getName().toString()
                    : selection instanceof MemberSelectTree ? ((MemberSelectTree) selection).getIdentifier().toString()
                    : tree instanceof NewClassTree ? "<init>" : "");
            List<? extends ExpressionTree> arguments = tree instanceof MethodInvocationTree
                    ? ((MethodInvocationTree) tree).getArguments()
                    : tree instanceof NewClassTree ? ((NewClassTree) tree).getArguments() : Collections.<ExpressionTree>emptyList();
            List<Object> argumentTypes = new ArrayList<Object>();
            for (ExpressionTree argument : arguments)
                argumentTypes.add(typeModel(trees.getTypeMirror(new TreePath(getCurrentPath(), argument))));
            attributes.put("argument_type_models", argumentTypes);
            List<? extends Tree> explicitTypes = tree instanceof MethodInvocationTree
                    ? ((MethodInvocationTree) tree).getTypeArguments()
                    : tree instanceof NewClassTree ? ((NewClassTree) tree).getTypeArguments()
                    : tree instanceof MemberReferenceTree ? ((MemberReferenceTree) tree).getTypeArguments()
                    : Collections.<Tree>emptyList();
            if (explicitTypes != null && !explicitTypes.isEmpty()) {
                List<Object> explicitModels = new ArrayList<Object>();
                for (Tree argument : explicitTypes)
                    explicitModels.add(typeModel(trees.getTypeMirror(new TreePath(getCurrentPath(), argument))));
                attributes.put("explicit_type_argument_models", explicitModels);
            }
            attributes.put("argument_count", tree instanceof MethodInvocationTree || tree instanceof NewClassTree ? arguments.size() : -1);
            for (TreePath lexical = getCurrentPath(); lexical != null; lexical = lexical.getParentPath()) {
                if (!attributes.containsKey("caller_static") && lexical.getLeaf() instanceof MethodTree)
                    attributes.put("caller_static", ((MethodTree) lexical.getLeaf()).getModifiers().getFlags().contains(Modifier.STATIC));
                if (!attributes.containsKey("caller_static") && lexical.getParentPath() != null
                        && lexical.getParentPath().getLeaf() instanceof ClassTree) {
                    if (lexical.getLeaf() instanceof BlockTree)
                        attributes.put("caller_static", ((BlockTree) lexical.getLeaf()).isStatic());
                    if (lexical.getLeaf() instanceof VariableTree)
                        attributes.put("caller_static", ((VariableTree) lexical.getLeaf()).getModifiers().getFlags().contains(Modifier.STATIC));
                }
                if (lexical.getLeaf() instanceof ClassTree) {
                    attributes.put("caller_type", signature(trees.getElement(lexical)));
                    break;
                }
            }
            if (selection instanceof MemberSelectTree) {
                TreePath receiver =
                        new TreePath(selectedPath, ((MemberSelectTree) selection).getExpression());
                TypeMirror receiverType = trees.getTypeMirror(receiver);
                Json.map(out.get("attributes"))
                    .put("receiver_type", receiverType == null ? "" : stableType(receiverType));
                Json.map(out.get("attributes"))
                    .put("receiver_type_model", typeModel(receiverType));
                attributes.put("receiver_type_qualifier", trees.getElement(receiver) instanceof TypeElement);
            } else if (tree instanceof NewClassTree) {
                TreePath identifier = new TreePath(getCurrentPath(), ((NewClassTree) tree).getIdentifier());
                TypeMirror receiverType = trees.getTypeMirror(identifier);
                attributes.put("receiver_type", receiverType == null ? "" : stableType(receiverType));
                attributes.put("receiver_type_model", typeModel(receiverType));
            }
            if (tree instanceof MemberReferenceTree) {
                MemberReferenceTree reference = (MemberReferenceTree) tree;
                TypeMirror functionalType = trees.getTypeMirror(getCurrentPath());
                Json.map(out.get("attributes")).put("functional_type_model", typeModel(functionalType));
                Json.map(out.get("attributes")).put("sam", sam(functionalType));
                Json.map(out.get("attributes")).put("reference_mode", reference.getMode().name());
                TreePath qualifier = new TreePath(getCurrentPath(), reference.getQualifierExpression());
                Json.map(out.get("attributes")).put("qualifier_type_model",
                        typeModel(trees.getTypeMirror(qualifier)));
            }
            if (callbackCreation)
                Json.map(out.get("attributes")).put("evaluation_phase", "callback_creation");
            observations.add(out);
            if (resolved) external(target);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
            call(tree, tree.getMethodSelect(), "invoke");
            return super.visitMethodInvocation(tree, p);
        }

        @Override
        public Void visitNewClass(NewClassTree tree, Void p) {
            call(tree, tree, "constructor");
            return super.visitNewClass(tree, p);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree tree, Void p) {
            String old = owner;
            boolean oldCreation = callbackCreation;
            callbackCreation = false;
            TreePath qualifier = new TreePath(getCurrentPath(), tree.getQualifierExpression());
            TypeMirror qualifierType = trees.getTypeMirror(qualifier);
            boolean intrinsic = tree.getMode() == MemberReferenceTree.ReferenceMode.NEW
                    && qualifierType != null && qualifierType.getKind() == TypeKind.ARRAY;
            owner = synthetic(tree, "<methodref@" + start(tree) + ">()", "METHOD_REF");
            if (declarationsOnly && !declarations.isEmpty()) {
                Map<String, Object> attributes = Json.map(
                        Json.map(declarations.get(declarations.size() - 1)).get("attributes"));
                TypeMirror functionalType = trees.getTypeMirror(getCurrentPath());
                attributes.put("functional_type_model", typeModel(functionalType));
                attributes.put("sam", sam(functionalType));
                attributes.put("reference_mode", tree.getMode().name());
                attributes.put("identifier", tree.getMode() == MemberReferenceTree.ReferenceMode.NEW
                        ? "new" : tree.getName().toString());
                attributes.put("qualifier_type_model", typeModel(qualifierType));
                boolean valid = listener.errors(sourceUri, start(tree), end(tree)).isEmpty();
                boolean resolved = element() instanceof ExecutableElement && valid;
                attributes.put("binding_resolved", intrinsic && valid || resolved);
                attributes.put("navigation_status", intrinsic ? "intrinsic" : resolved ? "resolved" : "unresolved");
                if (intrinsic) {
                    attributes.put("reference_kind", "array_constructor");
                    attributes.put("array_type", tree.getQualifierExpression().toString());
                }
            }
            if (!intrinsic) call(tree, tree, "method_reference");
            // Anatomist assigns qualifier calls to the method-reference callback as well.
            // Record the eager evaluation phase independently of that graph ownership.
            callbackCreation = true;
            scan(tree.getQualifierExpression(), p);
            scan(tree.getTypeArguments(), p);
            callbackCreation = oldCreation;
            owner = old;
            return null;
        }

        private void reference(Tree tree) {
            if (declarationsOnly || !original.containsKey(tree)) return;
            Element e = element();
            String target = signature(e);
            if (target.isEmpty()) return;
            if ((e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT)
                    && (start(tree) < 0 || end(tree) < start(tree))) return;
            if (e.getKind() == ElementKind.FIELD) {
                // javac represents receivers and array length as synthetic field symbols.
                // They are syntax/type evidence, not declarations or real field accesses.
                if (e.getSimpleName().contentEquals("this")
                        || e.getSimpleName().contentEquals("super")) return;
                if (tree instanceof MemberSelectTree
                        && ((MemberSelectTree) tree).getIdentifier().contentEquals("class")) return;
                if (tree instanceof MemberSelectTree
                        && e.getSimpleName().contentEquals("length")) {
                    TypeMirror receiver = trees.getTypeMirror(new TreePath(
                            getCurrentPath(), ((MemberSelectTree) tree).getExpression()));
                    if (receiver != null && receiver.getKind() == TypeKind.ARRAY) return;
                }
            }
            if (!emitBody) { external(e); return; }
            Map<String, Object> out = node("reference", tree);
            out.put("target", target);
            out.put("name", tree.toString());
            out.put("resolved", e.asType().getKind() != TypeKind.ERROR);
            Map<String, Object> attributes = Json.map(out.get("attributes"));
            attributes.put("reference_kind", e instanceof TypeElement ? "type"
                    : e.getKind().name().toLowerCase(Locale.ROOT));
            if (e.getKind() == ElementKind.FIELD || e.getKind() == ElementKind.ENUM_CONSTANT) {
                TreePath parentPath = getCurrentPath().getParentPath();
                Tree parent = parentPath == null ? null : parentPath.getLeaf();
                Tree expression = tree;
                while (parent instanceof ParenthesizedTree
                        && ((ParenthesizedTree) parent).getExpression() == expression) {
                    expression = parent;
                    parentPath = parentPath.getParentPath();
                    parent = parentPath == null ? null : parentPath.getLeaf();
                }
                boolean write = parent instanceof AssignmentTree && ((AssignmentTree) parent).getVariable() == expression;
                boolean both = parent instanceof CompoundAssignmentTree && ((CompoundAssignmentTree) parent).getVariable() == expression;
                if (parent instanceof UnaryTree) {
                    Tree.Kind kind = parent.getKind();
                    both = kind == Tree.Kind.PREFIX_INCREMENT || kind == Tree.Kind.POSTFIX_INCREMENT
                            || kind == Tree.Kind.PREFIX_DECREMENT || kind == Tree.Kind.POSTFIX_DECREMENT;
                }
                attributes.put("field_access", both ? "read_write" : write ? "write" : "read");
            }
            observations.add(out);
            external(e);
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void p) {
            reference(tree);
            return super.visitIdentifier(tree, p);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void p) {
            reference(tree);
            return super.visitMemberSelect(tree, p);
        }

        @Override
        public Void visitAnnotation(AnnotationTree tree, Void p) {
            if (!declarationsOnly) {
                Element e =
                        trees.getElement(new TreePath(getCurrentPath(), tree.getAnnotationType()));
                Map<String, Object> out = node("annotation", tree);
                out.put("name", tree.getAnnotationType().toString());
                out.put("target", signature(e));
                out.put(
                        "attributes",
                        Json.object(
                                "source_arguments",
                                strings(tree.getArguments()),
                                "source_name",
                                tree.getAnnotationType().toString()));
                Map<String, Object> attributes = Json.map(out.get("attributes"));
                for (Object value : declarationAnnotations.getOrDefault(owner, Collections.<Object>emptyList()))
                    if (Objects.equals(Json.map(value).get("type"), signature(e)))
                        attributes.putAll(Json.map(value));
                external(e);
                if (emitBody) observations.add(out);
            }
            return super.visitAnnotation(tree, p);
        }
    }

    private final class BoundedDiagnostics implements DiagnosticListener<JavaFileObject> {
        final List<Diagnostic<? extends JavaFileObject>> items =
                new ArrayList<Diagnostic<? extends JavaFileObject>>();
        final IdentityHashMap<Diagnostic<? extends JavaFileObject>, String> phases =
                new IdentityHashMap<Diagnostic<? extends JavaFileObject>, String>();
        final IdentityHashMap<JavaFileObject, java.net.URI> sourceUris =
                new IdentityHashMap<JavaFileObject, java.net.URI>();
        final Map<java.net.URI, DiagnosticRanges> errors =
                new HashMap<java.net.URI, DiagnosticRanges>();
        boolean exceeded;

        java.net.URI sourceUri(JavaFileObject source) {
            if (source == null) return null;
            java.net.URI uri = sourceUris.get(source);
            if (uri == null) { uri = source.toUri(); sourceUris.put(source, uri); }
            return uri;
        }
        List<Diagnostic<? extends JavaFileObject>> errors(java.net.URI source, long begin, long finish) {
            DiagnosticRanges rows = errors.get(source);
            return rows == null ? Collections.<Diagnostic<? extends JavaFileObject>>emptyList()
                    : rows.between(begin, finish);
        }
        private final class DiagnosticRanges {
            final List<Diagnostic<? extends JavaFileObject>> reported =
                    new ArrayList<Diagnostic<? extends JavaFileObject>>();
            List<Diagnostic<? extends JavaFileObject>> sorted = Collections.emptyList();
            final IdentityHashMap<Diagnostic<? extends JavaFileObject>, Integer> ordinal =
                    new IdentityHashMap<Diagnostic<? extends JavaFileObject>, Integer>();
            void add(Diagnostic<? extends JavaFileObject> item) {
                ordinal.put(item, reported.size()); reported.add(item);
            }
            List<Diagnostic<? extends JavaFileObject>> between(long begin, long finish) {
                if (finish < begin) return Collections.emptyList();
                if (sorted.size() != reported.size()) {
                    sorted = new ArrayList<Diagnostic<? extends JavaFileObject>>(reported);
                    Collections.sort(sorted, new Comparator<Diagnostic<? extends JavaFileObject>>() {
                        public int compare(Diagnostic<? extends JavaFileObject> a, Diagnostic<? extends JavaFileObject> b) {
                            return Long.compare(a.getPosition(), b.getPosition());
                        }
                    });
                }
                int low = 0, high = sorted.size();
                while (low < high) {
                    int middle = (low + high) >>> 1;
                    if (sorted.get(middle).getPosition() < begin) low = middle + 1; else high = middle;
                }
                List<Diagnostic<? extends JavaFileObject>> matches = new ArrayList<Diagnostic<? extends JavaFileObject>>();
                for (int position = low; position < sorted.size(); position++) {
                    Diagnostic<? extends JavaFileObject> diagnostic = sorted.get(position);
                    if (diagnostic.getPosition() > finish) break;
                    matches.add(diagnostic);
                }
                // The range index never changes the compiler's original diagnostic order.
                Collections.sort(matches, new Comparator<Diagnostic<? extends JavaFileObject>>() {
                    public int compare(Diagnostic<? extends JavaFileObject> a, Diagnostic<? extends JavaFileObject> b) {
                        return Integer.compare(ordinal.get(a), ordinal.get(b));
                    }
                });
                return matches;
            }
        }

        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            if (items.size() >= 10000) {
                exceeded = true;
                throw new IllegalStateException("DIAGNOSTIC_BUDGET");
            }
            items.add(diagnostic);
            phases.put(diagnostic, phase);
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getSource() != null) {
                java.net.URI uri = sourceUri(diagnostic.getSource());
                DiagnosticRanges rows = errors.get(uri);
                if (rows == null) { rows = new DiagnosticRanges(); errors.put(uri, rows); }
                rows.add(diagnostic);
            }
        }
    }
}
