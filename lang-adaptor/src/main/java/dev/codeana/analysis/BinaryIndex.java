package dev.codeana.analysis;

import dev.codeana.common.Json;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

/** Reads declarations only from frozen JAR inputs; no project code is loaded or executed. */
public final class BinaryIndex implements AutoCloseable {
    private static Object constant(Object value) {
        if (value instanceof Double number && !Double.isFinite(number)
                || value instanceof Float floating && !Float.isFinite(floating))
            return Json.object("non_finite", value.toString());
        return value;
    }

    private static final int MAX_OPEN_ARCHIVES = 8, MAX_CACHED_CLASSES = 64;
    private static final long MAX_CLASS_CACHE_WEIGHT = 8L << 20;
    private static final int MAX_PACKAGE_LINKS = 262144, MAX_PACKAGE_CHARACTERS = 8 * 1024 * 1024;
    private final List<Path> archives = new ArrayList<>();
    private final Map<Integer, Map<String, Object>> artifactOrigins = new HashMap<>();
    private final int classpathSize;
    private final boolean typeGraph;
    private final Map<Integer, FrozenArchive> openArchives = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, Map<String, Object>> classes = new LinkedHashMap<>(64, .75f, true);
    private final Map<String, Long> classWeights = new HashMap<>();
    private final long maximumClassWeight;
    private long retainedClassWeight;
    private final Map<String, List<Integer>> packages = new HashMap<>();
    private final Map<String, Map<String, Object>> loading = new HashMap<>();
    private boolean packageIndexComplete = true, closed;

    BinaryIndex(Map<String, Object> context, Path javaHome) throws IOException {
        this(context, javaHome, MAX_CLASS_CACHE_WEIGHT);
    }

    BinaryIndex(Map<String, Object> context, Path javaHome, long cacheWeight) throws IOException {
        if (cacheWeight < 0 || cacheWeight > MAX_CLASS_CACHE_WEIGHT)
            throw new IllegalArgumentException("BINARY_CACHE_BUDGET");
        maximumClassWeight = cacheWeight;
        typeGraph = Boolean.TRUE.equals(context.get("type_graph"));
        for (Object item : Json.list(context.get("classpath")))
            archives.add(Path.of((String) item));
        classpathSize = archives.size();
        Path platform = javaHome.resolve("jre/lib/rt.jar");
        if (java.nio.file.Files.isRegularFile(platform)) archives.add(platform);
        indexPackages();
    }

    /** Compact ordered package catalogue; overflow falls back to exact sequential lookup. */
    private void indexPackages() throws IOException {
        int links = 0, characters = 0;
        for (int i = 0; i < archives.size(); i++) {
            check();
            try (FrozenArchive archive = new FrozenArchive(archives.get(i))) {
                if (!packageIndexComplete) continue;
                Set<String> seen = new HashSet<>();
                var entries = archive.entries();
                int visited = 0;
                while (entries.hasMoreElements()) {
                    if ((visited++ & 255) == 0) check();
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class")) continue;
                    int slash = name.lastIndexOf('/');
                    String owner = slash < 0 ? "" : name.substring(0, slash);
                    if (seen.contains(owner)) continue;
                    // Count every link's characters too, bounding the per-archive dedup set.
                    if (++links > MAX_PACKAGE_LINKS
                            || (characters += owner.length()) > MAX_PACKAGE_CHARACTERS) {
                        packages.clear();
                        packageIndexComplete = false;
                        break;
                    }
                    seen.add(owner);
                    packages.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(i);
                }
            }
        }
    }

    private static void check() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
    }

    private FrozenArchive archive(int position) throws IOException {
        check();
        if (closed) throw new IOException("BINARY_INDEX_CLOSED");
        FrozenArchive existing = openArchives.get(position);
        if (existing != null) return existing;
        if (openArchives.size() >= MAX_OPEN_ARCHIVES) {
            var oldest = openArchives.entrySet().iterator();
            FrozenArchive evicted = oldest.next().getValue();
            oldest.remove();
            evicted.close();
        }
        FrozenArchive opened = new FrozenArchive(archives.get(position));
        openArchives.put(position, opened);
        return opened;
    }

    private void remember(String name, Map<String, Object> result, long weight) {
        if (weight > maximumClassWeight) return;
        while (classes.size() >= MAX_CACHED_CLASSES || retainedClassWeight > maximumClassWeight - weight) {
            String oldest = classes.keySet().iterator().next();
            classes.remove(oldest);
            retainedClassWeight -= classWeights.remove(oldest);
        }
        classes.put(name, result);
        classWeights.put(name, weight);
        retainedClassWeight += weight;
    }

    public static void enrich(Map<String, Object> context, Map<String, Object> model, Path javaHome)
            throws IOException {
        try (BinaryIndex index = new BinaryIndex(context, javaHome)) {
            for (Object raw : Json.list(model.get("external"))) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
                Map<String, Object> external = Json.map(raw);
                String signature = Json.string(external, "signature");
                int separator = signature.indexOf('#');
                String className = separator < 0 ? signature : signature.substring(0, separator);
                Map<String, Object> binary = index.readClass(className);
                if (binary == null) continue;
                Map<String, Object> attributes = Json.map(external.get("attributes"));
                attributes.put("artifact_origin", binary.get("artifact_origin"));
                attributes.put("binary_class", binary.get("declaration"));
                if (Boolean.FALSE.equals(
                        Json.map(binary.get("declaration")).get("members_complete")))
                    attributes.put("binary_members_partial", true);
                if (separator >= 0) {
                    Map<String, Object> member =
                            Json.map(binary.get("members")).get(signature) instanceof Map
                                    ? Json.map(Json.map(binary.get("members")).get(signature))
                                    : null;
                    if (member != null) attributes.put("binary_member", member);
                }
            }
        }
    }

    Map<String, Object> readClass(String name) throws IOException {
        String candidate = name;
        while (true) {
            Map<String, Object> result = readExactClass(candidate);
            if (result != null) return result;
            int separator = candidate.lastIndexOf('.');
            if (separator < 0) return null;
            candidate = candidate.substring(0, separator) + "$" + candidate.substring(separator + 1);
        }
    }

    private Map<String, Object> readExactClass(String name) throws IOException {
        check();
        if (closed) throw new IOException("BINARY_INDEX_CLOSED");
        if (loading.containsKey(name)) return loading.get(name);
        if (classes.containsKey(name)) return classes.get(name);
        String entryName = name.replace('.', '/') + ".class";
        int slash = entryName.lastIndexOf('/');
        List<Integer> candidates =
                packageIndexComplete
                        ? packages.getOrDefault(
                                slash < 0 ? "" : entryName.substring(0, slash), List.of())
                        : null;
        int count = candidates == null ? archives.size() : candidates.size();
        for (int i = 0; i < count; i++) {
            int position = candidates == null ? i : candidates.get(i);
            FrozenArchive archive = archive(position);
            ZipEntry entry = archive.getEntry(entryName);
            if (entry == null) continue;
            if (entry.getSize() > 2 * 1024 * 1024) throw new IOException("BINARY_CLASS_BUDGET");
            byte[] bytes;
            try (InputStream input = archive.getInputStream(entry)) {
                bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            }
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("BINARY_CLASS_BUDGET");
            Map<String, Object> result = parse(bytes);
            if (!name.equals(Json.map(result.get("declaration")).get("name")))
                throw new IOException("BINARY_CLASS_NAME_MISMATCH: " + name);
            Map<String, Object> origin = artifactOrigins.get(position);
            if (origin == null) {
                Path path = archives.get(position);
                origin =
                        Json.object(
                                "kind",
                                position < classpathSize ? "classpath" : "platform",
                                "name",
                                path.getFileName().toString(),
                                "sha256",
                                Json.sha(path));
                if (position < classpathSize) origin.put("index", position);
                artifactOrigins.put(position, origin);
            }
            result.put("artifact_origin", origin);
            if (loading.size() >= 64) throw new IOException("BINARY_ENCLOSING_BUDGET");
            loading.put(name, result);
            try {
                resolveEnclosing(result);
            } finally {
                loading.remove(name);
            }
            remember(name, result, Math.max(4096L, bytes.length * 8L));
            return result;
        }
        remember(name, null, 4096L);
        return null;
    }

    private void resolveEnclosing(Map<String, Object> result) throws IOException {
        if (typeGraph) { resolveGraph(result); return; }
        Map<String, Object> declaration = Json.map(result.get("declaration"));
        Map<String, String> scope = new HashMap<>();
        Object enclosing = declaration.get("enclosing_type");
        if (enclosing instanceof String && !Boolean.TRUE.equals(declaration.get("static_nested"))) {
            Map<String, Object> outer = readClass((String) enclosing);
            if (outer != null) {
                collectVariables(
                        Json.map(Json.map(outer.get("declaration")).get("type_model")), scope);
                Object callable = declaration.get("enclosing_callable");
                if (callable instanceof String
                        && Json.map(outer.get("members")).get(callable) instanceof Map)
                    collectVariables(
                            Json.map(
                                    Json.map(Json.map(outer.get("members")).get(callable))
                                            .get("type_model")),
                            scope);
            }
        }
        Map<String, Object> type = Json.map(declaration.get("type_model"));
        BinaryTypes.owners(type, Json.string(declaration, "name"), scope);
        collectVariables(type, scope);
        for (var entry : Json.map(result.get("members")).entrySet()) {
            Map<String, Object> member = Json.map(entry.getValue());
            BinaryTypes.owners(Json.map(member.get("type_model")), entry.getKey(), scope);
        }
    }

    private void resolveGraph(Map<String,Object> result) throws IOException {
        Map<String,Object> declaration=Json.map(result.get("declaration"));
        Map<String,Map<String,Object>> scope=new HashMap<>();
        Object enclosing=declaration.get("enclosing_type");
        if(enclosing instanceof String && !Boolean.TRUE.equals(declaration.get("static_nested"))) {
            Map<String,Object> outer=readClass((String)enclosing);
            if(outer!=null) {
                collectGraphFormals(Json.map(Json.map(outer.get("declaration")).get("type_model")).get("type_parameters"),scope);
                Object callable=declaration.get("enclosing_callable");
                if(callable instanceof String)collectGraphFormals(Json.map(Json.map(outer.get("members")).get(callable)).get("type_parameters"),scope);
            }
        }
        BinarySignatureGraph graph=new BinarySignatureGraph(Json.string(declaration,"name"),
                (String)declaration.get("generic_signature"),(String)declaration.remove("graph_superclass"),
                Json.list(declaration.remove("graph_interfaces")).stream().map(String.class::cast).toList(),scope);
        Map<String,Object> members=Json.map(result.get("members"));
        for(var entry:members.entrySet()) {
            Map<String,Object> member=Json.map(entry.getValue());String signature=(String)member.get("generic_signature"),descriptor=(String)member.get("descriptor");
            if("FIELD".equals(member.get("kind")))graph.field(entry.getKey(),signature,descriptor);
            else graph.method(entry.getKey(),signature,descriptor);
        }
        Map<String,Object> projected=graph.finish();declaration.put("type_model",projected.get("declaration"));
        for(var entry:members.entrySet()) {
            Map<String,Object> member=Json.map(entry.getValue());
            if("FIELD".equals(member.get("kind")))member.put("type_model",Json.map(projected.get("fields")).get(entry.getKey()));
            else {Map<String,Object> method=Json.map(Json.map(projected.get("methods")).get(entry.getKey()));member.put("type_model",method.get("type_model"));member.put("type_parameters",method.get("type_parameters"));}
        }
    }
    private static void collectGraphFormals(Object raw,Map<String,Map<String,Object>> scope) {
        for(Object value:Json.list(raw)){Map<String,Object> formal=Json.map(value);scope.put(Json.string(formal,"name"),formal);}
    }

    private static void collectVariables(Map<String, Object> model, Map<String, String> scope) {
        if (!(model.get("type_parameters") instanceof List)) return;
        for (Object raw : Json.list(model.get("type_parameters"))) {
            Map<String, Object> formal = Json.map(raw);
            scope.put(Json.string(formal, "name"), Json.string(formal, "owner"));
        }
    }

    private Map<String, Object> parse(byte[] bytes) {
        Map<String, Object> declaration = Json.object();
        Map<String, Object> members = Json.object();
        BinaryAnnotations metadata = new BinaryAnnotations();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    String binaryName;
                    final Map<String, String> variables = new HashMap<>();

                    @Override
                    public void visitInnerClass(
                            String name, String outerName, String innerName, int access) {
                        if (name.replace('/', '.').equals(binaryName) && outerName != null) {
                            declaration.put("enclosing_type", outerName.replace('/', '.'));
                            declaration.put("static_nested", (access & Opcodes.ACC_STATIC) != 0);
                        }
                    }

                    @Override
                    public void visitOuterClass(String owner, String name, String descriptor) {
                        declaration.put("enclosing_type", owner.replace('/', '.'));
                        if (name != null && descriptor != null) {
                            StringBuilder key =
                                    new StringBuilder(owner.replace('/', '.'))
                                            .append('#')
                                            .append(name)
                                            .append('(');
                            Type[] parameters = Type.getArgumentTypes(descriptor);
                            for (int i = 0; i < parameters.length; i++) {
                                if (i > 0) key.append(',');
                                key.append(parameters[i].getClassName());
                            }
                            declaration.put("enclosing_callable", key.append(')').toString());
                        }
                    }

                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        binaryName = name.replace('/', '.');
                        List<String> parents = new ArrayList<>();
                        if (superName != null) parents.add(superName.replace('/', '.'));
                        for (String parent : interfaces) parents.add(parent.replace('/', '.'));
                        declaration.put("version", 1);
                        declaration.put("name", binaryName);
                        declaration.put("access", access);
                        declaration.put("generic_signature", signature);
                        declaration.put("parents", parents);
                        if (typeGraph) {
                            declaration.put("graph_superclass", superName == null ? null : superName.replace('/', '.'));
                            declaration.put("graph_interfaces", List.of(interfaces).stream().map(value -> value.replace('/', '.')).toList());
                        }
                        Map<String, Object> model = typeGraph ? Json.object("type_parameters", List.of())
                                : BinaryTypes.declaration(signature, superName, interfaces);
                        if (!typeGraph) BinaryTypes.owners(model, binaryName, Map.of());
                        for (Object formal : Json.list(model.get("type_parameters")))
                            variables.put(Json.string(Json.map(formal), "name"), binaryName);
                        declaration.put("type_model", model);
                        declaration.put("members_complete", true);
                        declaration.put("members_omitted", 0);
                        declaration.put("annotations", new ArrayList<String>());
                        declaration.put("annotation_metadata", new ArrayList<Object>());
                        declaration.put("type_annotations", new ArrayList<Object>());
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        @SuppressWarnings("unchecked")
                        List<String> annotations = (List<String>) declaration.get("annotations");
                        annotations.add(Type.getType(descriptor).getClassName());
                        return metadata.annotation(descriptor, visible,
                                a -> Json.list(declaration.get("annotation_metadata")).add(a));
                    }

                    @Override
                    public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
                        return metadata.typeAnnotation(ref, path, descriptor, visible, Json.list(declaration.get("type_annotations")));
                    }

                    @Override
                    public FieldVisitor visitField(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            Object value) {
                        Map<String, Object> member =
                                Json.object(
                                        "kind",
                                        "FIELD",
                                        "descriptor",
                                        descriptor,
                                        "generic_signature",
                                        signature,
                                        "access",
                                        access,
                                        "constant",
                                        constant(value),
                                        "annotations",
                                        new ArrayList<String>());
                        Map<String, Object> model = typeGraph ? Json.object() : BinaryTypes.field(signature, descriptor);
                        if (!typeGraph) BinaryTypes.owners(model, binaryName, variables);
                        member.put("type_model", model);
                        member.put("annotation_metadata", new ArrayList<Object>());
                        member.put("type_annotations", new ArrayList<Object>());
                        members.put(binaryName + "#" + name, member);
                        return new FieldVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(
                                    String descriptor, boolean visible) {
                                @SuppressWarnings("unchecked")
                                List<String> annotations = (List<String>) member.get("annotations");
                                annotations.add(Type.getType(descriptor).getClassName());
                                return metadata.annotation(descriptor, visible,
                                        a -> Json.list(member.get("annotation_metadata")).add(a));
                            }
                            @Override
                            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
                                return metadata.typeAnnotation(ref, path, descriptor, visible, Json.list(member.get("type_annotations")));
                            }
                        };
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access,
                            String name,
                            String descriptor,
                            String signature,
                            String[] exceptions) {
                        StringBuilder key =
                                new StringBuilder(binaryName).append('#').append(name).append('(');
                        Type[] arguments = Type.getArgumentTypes(descriptor);
                        for (int i = 0; i < arguments.length; i++) {
                            if (i > 0) key.append(',');
                            key.append(arguments[i].getClassName());
                        }
                        key.append(')');
                        Map<String, Object> member =
                                Json.object(
                                        "kind",
                                        "METHOD",
                                        "descriptor",
                                        descriptor,
                                        "generic_signature",
                                        signature,
                                        "access",
                                        access,
                                        "exceptions",
                                        exceptions == null ? List.of() : List.of(exceptions),
                                        "annotations",
                                        new ArrayList<String>());
                        Map<String, Object> model = typeGraph ? Json.object() : BinaryTypes.method(signature, descriptor);
                        if (!typeGraph) BinaryTypes.owners(model, key.toString(), variables);
                        member.put("type_model", model);
                        member.put("annotation_metadata", new ArrayList<Object>());
                        member.put("type_annotations", new ArrayList<Object>());
                        member.put("parameters", new ArrayList<Object>());
                        member.put("parameter_annotations", new ArrayList<Object>());
                        members.put(key.toString(), member);
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public AnnotationVisitor visitAnnotation(
                                    String descriptor, boolean visible) {
                                @SuppressWarnings("unchecked")
                                List<String> annotations = (List<String>) member.get("annotations");
                                annotations.add(Type.getType(descriptor).getClassName());
                                return metadata.annotation(descriptor, visible,
                                        a -> Json.list(member.get("annotation_metadata")).add(a));
                            }
                            @Override
                            public void visitParameter(String name, int access) {
                                Json.list(member.get("parameters")).add(Json.object("name", name, "access", access));
                            }
                            @Override
                            public void visitAnnotableParameterCount(int count, boolean visible) {
                                member.put(visible ? "visible_parameter_count" : "invisible_parameter_count", count);
                            }
                            @Override
                            public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
                                return metadata.typeAnnotation(ref, path, descriptor, visible, Json.list(member.get("type_annotations")));
                            }
                            @Override
                            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                                return metadata.annotation(descriptor, visible, a -> Json.list(member.get("parameter_annotations"))
                                        .add(Json.object("parameter", parameter, "annotation", a)));
                            }
                            @Override
                            public AnnotationVisitor visitAnnotationDefault() {
                                return metadata.defaultValue(value -> member.put("annotation_default", value));
                            }
                        };
                    }

                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return Json.object("declaration", declaration, "members", members);
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException error = null;
        for (FrozenArchive archive : openArchives.values()) {
            try {
                archive.close();
            } catch (IOException e) {
                error = e;
            }
        }
        openArchives.clear();
        classes.clear();
        classWeights.clear();
        retainedClassWeight = 0;
        packages.clear();
        loading.clear();
        if (error != null) throw error;
    }
}
