import com.anatomist.config.ProjectConfig;
import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.core.javac.*;
import com.anatomist.extract.JavaModelExtractor;
import com.anatomist.framework.*;
import com.anatomist.application.FrameworkPlugins;
import com.anatomist.model.*;
import com.anatomist.json.Json;
import java.nio.file.*;
import java.util.*;

/** Test-only independent process witness; no code-ana fact projection is used as an oracle. */
public final class ParityWitness {
    static final Map<String,List<String>> domains=new TreeMap<>();
    static final IdentityHashMap<Object,String> typeRows=new IdentityHashMap<>();
    static Path sourceRoot;
    static final List<String> CATEGORIES=List.of("raw.syntax","raw.bindings","raw.types","raw.declarations","raw.extensions","raw.source_hash","raw.diagnostics","facts.nodes","facts.edges","facts.annotations","facts.annotationMetaRelations","facts.semanticAnnotations","facts.declarations","facts.unresolvedCallSites");
    static void add(String domain,Object value) {
        String row = value instanceof JavaTypes ? typeRows.computeIfAbsent(value, k -> Json.writeCompact(canonical(domain,k))) : Json.writeCompact(canonical(domain,value));
        domains.computeIfAbsent(domain,k->new ArrayList<>()).add(row);
    }
    // Independent witness normalization operates on known type fields before JSON
    // encoding. Annotation values, source literals and all graph indices stay exact.
    @SuppressWarnings("unchecked")
    static Object canonical(String domain,Object value) {
        Object detached=detach(value);
        Map<String,Integer> captures=new LinkedHashMap<>();
        if(domain.equals("raw.types")) {
            for(Object entry:(List<?>)((Map<?,?>)detached).get("entries"))
                canonicalFields((Map<String,Object>)entry,Set.of("name","display"),captures);
        } else if(domain.equals("raw.bindings")) {
            for(Object entry:((Map<?,?>)detached).values())
                canonicalFields((Map<String,Object>)entry,Set.of("parameters","returns","type","exceptions"),captures);
        }
        return detached;
    }
    static void canonicalFields(Map<String,Object> row,Set<String> fields,Map<String,Integer> captures) {
        // Detached records are TreeMaps, so field traversal is deterministic.
        for(var entry:row.entrySet()) if(fields.contains(entry.getKey())) {
            Object value=entry.getValue();
            if(value instanceof String text)entry.setValue(captureSpelling(text,captures));
            else if(value instanceof List<?> list)entry.setValue(list.stream().map(v->captureSpelling((String)v,captures)).toList());
        }
    }
    static String captureSpelling(String text,Map<String,Integer> captures) {
        // Tokenize quoted Java spellings as whole tokens, including escaped quotes.
        // This deliberately does not call the product's projection normalizer.
        var tokens=java.util.regex.Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|(capture#\\d+)").matcher(text);
        return tokens.replaceAll(match->match.group(1)==null ? java.util.regex.Matcher.quoteReplacement(match.group()) :
                "capture#"+captures.computeIfAbsent(match.group(1),ignored->captures.size()));
    }
    static void verifyCaptureNormalization() {
        Map<String,Integer> captures=new LinkedHashMap<>();
        String actual=captureSpelling("@Tag(\"prefix \\\"capture#123\\\"\") Pair<capture#934,capture#11,capture#934>",captures);
        String expected="@Tag(\"prefix \\\"capture#123\\\"\") Pair<capture#0,capture#1,capture#0>";
        if(!actual.equals(expected)||captures.size()!=2)throw new IllegalStateException("WITNESS_CAPTURE_NORMALIZATION");
    }
    static Object detach(Object value) {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        if(value instanceof Path path) return sourceRoot != null && path.startsWith(sourceRoot) ? sourceRoot.relativize(path).toString() : path.toString();
        if(value instanceof Character||value instanceof Enum<?>)return value.toString();
        if(value instanceof Map<?,?> map){Map<String,Object> result=new TreeMap<>();map.forEach((k,v)->result.put(k.toString(),detach(v)));return result;}
        if(value instanceof Collection<?> list)return list.stream().map(ParityWitness::detach).toList();
        Map<String,Object> fields=new TreeMap<>();
        try {
            if(value.getClass().isRecord())for(var component:value.getClass().getRecordComponents())fields.put(component.getName(),detach(component.getAccessor().invoke(value)));
            else for(var field:value.getClass().getFields())if(!java.lang.reflect.Modifier.isStatic(field.getModifiers())){
                Object data=field.get(value);
                if(Set.of("metadata","attributes").contains(field.getName())&&data instanceof String text&&!text.isBlank())data=Json.parseTree(text);
                fields.put(field.getName(),detach(data));
            }
        }catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
        return fields;
    }
    public static void main(String[] args) throws Exception {
        verifyCaptureNormalization();
        Path root=Path.of(args[0]), home=Path.of(args[1]), cache=Path.of(args[2]); sourceRoot=root;
        List<Path> files; List<ProjectResource> resources; List<Path> publish; List<Path> classpath;
        List<SourceRoot> roots; Map<Path,SourceIdentity> identities=new LinkedHashMap<>();
        String module="."; SourceScope scope=SourceScope.MAIN; Set<String> dependencies=new LinkedHashSet<>();
        List<JavacMavenInputs.Module> modules=new ArrayList<>();
        List<Map<String,Object>> capturedContexts=new ArrayList<>();
        if(args.length>6) {
            var request=(Map<String,Object>)Json.parseTree(Files.readString(Path.of(args[6])));
            if(request.containsKey("contexts")) {
                for(Object row:(List<?>)request.get("contexts"))capturedContexts.add((Map<String,Object>)row);
                request=capturedContexts.stream().filter(c->!Boolean.TRUE.equals(c.get("prerequisite"))).findFirst().orElseThrow();
            } else capturedContexts.add(request);
            module=(String)request.get("module"); scope=SourceScope.valueOf((String)request.get("scope"));
            files=new ArrayList<>(); publish=new ArrayList<>(); roots=new ArrayList<>();
            Map<String,List<Path>> mains=new LinkedHashMap<>(), tests=new LinkedHashMap<>();
            Set<Path> seen=new LinkedHashSet<>();
            for(var captured:capturedContexts)for(Object row:(List<?>)captured.get("sources")) {
                Map<?,?> input=(Map<?,?>)row; Path file=Path.of((String)input.get("input"));
                if(!ContentHash.sha256(file).equals(input.get("hash"))) throw new IllegalArgumentException("FROZEN_SOURCE_DRIFT");
                try {java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file)));}
                catch(java.nio.charset.CharacterCodingException invalid) {continue;}
                String owner=(String)input.get("module"); SourceScope ss=SourceScope.valueOf((String)input.get("scope"));
                if(!Boolean.TRUE.equals(captured.get("prerequisite"))&&Boolean.TRUE.equals(input.get("publish"))) publish.add(file);
                if(!seen.add(file))continue;
                files.add(file);
                roots.add(new SourceRoot(file,owner,ss)); identities.put(file,new SourceIdentity(owner,ss));
                (ss==SourceScope.TEST?tests:mains).computeIfAbsent(owner,k->new ArrayList<>()).add(file);
                if(!owner.equals(module)) dependencies.add(owner);
            }
            classpath=((List<?>)request.get("classpath")).stream().map(v->Path.of((String)v)).toList();
            Set<String> names=new LinkedHashSet<>(mains.keySet());names.addAll(tests.keySet());names.add(module);
            for(String name:names) {
                var mainContext=capturedContexts.stream().filter(c->name.equals(c.get("module"))&&"MAIN".equals(c.get("scope"))).findFirst().orElse(null);
                var testContext=capturedContexts.stream().filter(c->name.equals(c.get("module"))&&"TEST".equals(c.get("scope"))).findFirst().orElse(null);
                modules.add(new JavacMavenInputs.Module(name,root.resolve(name),name,8,mains.getOrDefault(name,List.of()),tests.getOrDefault(name,List.of()),contextDependencies(mainContext),contextDependencies(testContext),""));
            }
            resources=List.of();
        } else {
            try(var paths=Files.walk(root)){files=paths.filter(p->p.toString().endsWith(".java")).sorted().toList();}
            try(var paths=Files.walk(root)){resources=paths.filter(p->p.toString().endsWith(".xml")).sorted().map(p->new ProjectResource(p,root.relativize(p).toString(),"spring-xml")).toList();}
            publish=files; classpath=List.of(); roots=List.of(new SourceRoot(root,".",SourceScope.MAIN));
            files.forEach(p->identities.put(p,new SourceIdentity(".",SourceScope.MAIN)));
            modules.add(new JavacMavenInputs.Module(".",root,".",8,List.of(root),List.of(),List.of(),List.of(),""));
        }
        var config=new ProjectConfig();config.setJavaVersion(8);config.setLombokMode(args[3]);
        var options=new JavacOptions(home,true,true,false,false,cache,1,512);
        var backend=new JavacBackend(root,JavacToolchain.resolve(options,8,null),classpath,roots,options);
        var framework=new JavaFrameworkContext(root,files,config,!resources.isEmpty());
        var registry=FrameworkPlugins.builtIns().prepareDetached(framework);
        backend.setDeclarationOverlay(registry.overlays());
        var inputs=new JavacMavenInputs(modules,Map.of(),"frozen-context",true);
        if(args.length>6) {
            backend.setMavenInputs(inputs);
            var signatures=backend.getClass().getDeclaredMethod("setSourceSignatures",boolean.class); signatures.setAccessible(true); signatures.invoke(backend,true);
            if(args[4].equals("reference")) {
                // The frozen backend discovers directories, not individual file roots.
                // Supply the exact immutable source plan to its existing planner so
                // prerequisites are not silently dropped. No compiler algorithm changes.
                var plan=backend.getClass().getDeclaredField("sourcePlan");plan.setAccessible(true);
                plan.set(backend,new JavaSourcePlan(publish,files,roots,identities));
            }
        }
        if(args[4].equals("captured")) {
            Class<?> type=Class.forName("com.anatomist.core.javac.JavacBackend$CapturedContext");
            List<Object> contexts=new ArrayList<>();
            if(capturedContexts.isEmpty())contexts.add(type.getConstructor(String.class,SourceScope.class,List.class,Set.class,Set.class).newInstance(module,scope,files,Set.copyOf(publish),dependencies));
            else for(var c:capturedContexts) {
                List<Path> visible=new ArrayList<>();Set<Path> selected=new LinkedHashSet<>();
                for(Object row:(List<?>)c.get("sources")) {
                    var input=(Map<?,?>)row;Path file=Path.of((String)input.get("input"));
                    if(!identities.containsKey(file))continue;visible.add(file);
                    if(!Boolean.TRUE.equals(c.get("prerequisite"))&&Boolean.TRUE.equals(input.get("publish")))selected.add(file);
                }
                Set<String> deps=new LinkedHashSet<>((List<String>)c.getOrDefault("dependency_modules",List.of()));
                contexts.add(type.getConstructor(String.class,SourceScope.class,List.class,Set.class,Set.class).newInstance(c.get("module"),SourceScope.valueOf((String)c.get("scope")),visible,selected,deps));
            }
            backend.getClass().getMethod("setCapturedInputs",List.class,JavaSourcePlan.class,JavacMavenInputs.class).invoke(backend,contexts,new JavaSourcePlan(publish,files,roots,identities),inputs);
        }
        for(String domain:CATEGORIES)domains.put(domain,new ArrayList<>());
        ExtractionResult staged=new ExtractionResult();
        backend.analyzeFiles(publish,context->{
            for(var unit:context.units()) {
                add("raw.syntax",unit.tree());add("raw.bindings",unit.bindings());add("raw.types",unit.types());add("raw.declarations",unit.declarationInventory());add("raw.extensions",unit.extensions());add("raw.source_hash",unit.sourceHash());
            }
            context.diagnostics().forEach(d->add("raw.diagnostics",d));
            try(var binaries=backend.openBinaryRepository(context)) {
                new JavaModelExtractor(framework,binaries,registry).extract(context,unit->{
                    var facts=unit.facts();staged.addFacts(facts);
                    facts.nodes.forEach(v->add("facts.nodes",v));facts.edges.forEach(v->add("facts.edges",v));facts.annotations.forEach(v->add("facts.annotations",v));facts.annotationMetaRelations.forEach(v->add("facts.annotationMetaRelations",v));facts.semanticAnnotations.forEach(v->add("facts.semanticAnnotations",v));facts.declarations.forEach(v->add("facts.declarations",v));facts.unresolvedCallSites.forEach(v->add("facts.unresolvedCallSites",v));
                });
            }
        });
        if(!resources.isEmpty()) {
            var known=staged.nodes.stream().map(n->n.id).collect(java.util.stream.Collectors.toSet());
            var symbols=staged.nodes.stream().map(n->new SymbolFact(n.id,n.id,n.kind,n.module,n.scope,n.sourceFile,n.metadata)).toList();
            var result=new ExtractionResult();
            new ProjectAnalysisRunner().run(registry,framework,resources,new DefaultProjectFactView(known,Map.of(),symbols),result,new ExtensionReport());
            result.nodes.forEach(v->add("facts.nodes",v));result.edges.forEach(v->add("facts.edges",v));
        }
        System.out.println(Json.writeCompact(finish(args.length>5 && !args[5].equals("-") ? Path.of(args[5]) : null)));
    }
    static List<JavacMavenInputs.Dependency> contextDependencies(Map<String,Object> context) {
        if(context==null)return List.of();
        List<JavacMavenInputs.Dependency> result=new ArrayList<>();
        for(Object module:(List<?>)context.getOrDefault("dependency_modules",List.of()))result.add(new JavacMavenInputs.ReactorDependency((String)module,(String)module));
        for(Object jar:(List<?>)context.get("classpath"))result.add(new JavacMavenInputs.ExternalDependency("","","jar",Path.of((String)jar)));
        return result;
    }
    static Map<String,Object> finish(Path output) {
        Map<String,Object> evidence=new TreeMap<>();
        for(String domain:CATEGORIES) {
            var rows=domains.getOrDefault(domain,List.of()).stream().sorted().toList();
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                for(int i=0;i<rows.size();i++) {
                    if(i>0) digest.update((byte)'\n');
                    digest.update(rows.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                evidence.put(domain,Map.of("rows",rows.size(),"sha256",java.util.HexFormat.of().formatHex(digest.digest())));
                if(output!=null) {Files.createDirectories(output);Files.writeString(output.resolve(domain+".json"),Json.writeCompact(rows));}
            } catch(java.security.NoSuchAlgorithmException|java.io.IOException e) {throw new IllegalStateException(e);}
        }
        return evidence;
    }
}
