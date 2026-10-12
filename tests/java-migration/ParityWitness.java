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
    static final Map<String,List<String>> dumpRows=new TreeMap<>();
    static boolean dumping;
    static Path sourceRoot;
    static String observedModule, observedScope;
    static JavaTypes observedTypes;
    static int observedSyntaxBase;
    static void observeUnit(JavaUnit unit) {
        observedTypes=unit.types();
        observedSyntaxBase=minimumSyntaxId(unit.tree());
        if(observedSyntaxBase==Integer.MAX_VALUE)observedSyntaxBase=0;
    }
    static int minimumSyntaxId(Object value) {
        int result=Integer.MAX_VALUE;
        if(value instanceof JavaNode node)result=Math.min(node.id(),minimumSyntaxId(node.fields()));
        else if(value instanceof Map<?,?> map)for(Object v:map.values())result=Math.min(result,minimumSyntaxId(v));
        else if(value instanceof Iterable<?> list)for(Object v:list)result=Math.min(result,minimumSyntaxId(v));
        return result;
    }
    static Object localSyntax(Object value) {
        if(value instanceof JavaNode node) {
            var row=new TreeMap<String,Object>();row.put("id",node.id()-observedSyntaxBase);
            row.put("kind",node.kind());row.put("start",node.start());row.put("end",node.end());row.put("fields",localSyntax(node.fields()));return row;
        }
        if(value instanceof Map<?,?> map) {
            var row=new TreeMap<String,Object>();map.forEach((k,v)->row.put(k.toString(),localSyntax(v)));return row;
        }
        if(value instanceof Iterable<?> list) {
            var rows=new ArrayList<Object>();list.forEach(v->rows.add(localSyntax(v)));return rows;
        }
        return detach(value);
    }
    static final List<Map<String,Object>> unitEvidence=new ArrayList<>();
    static final List<Object> diagnosticEvidence=new ArrayList<>();
    static boolean observe(JavaAnalysisContext context) {
        typeRows.clear();
        return observedModule==null || observedModule.equals(context.module()) && observedScope.equals(context.scope().name());
    }
    static void unitEvidence(JavaUnit unit) {
        if(System.getenv("CODEANA_MODEL_ROW_EVIDENCE")==null)return;
        Map<String,Object> row=new TreeMap<>();
        row.put("source",detach(unit.path()));
        for(String domain:CATEGORIES)if(domain.startsWith("raw.") && !domain.equals("raw.diagnostics")) {
            var values=domains.get(domain);row.put(domain,values.get(values.size()-1));
        }
        unitEvidence.add(row);
        String selected=System.getenv("CODEANA_MODEL_DUMP_SOURCE");
        if(selected!=null && selected.equals(row.get("source"))) {
            Path directory=Path.of(System.getenv("CODEANA_MODEL_ROW_EVIDENCE"));
            try {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("selected-types.json"),Json.writeCompact(canonical("raw.types",unit.types())));
                Files.writeString(directory.resolve("selected-bindings.json"),Json.writeCompact(canonical("raw.bindings",unit.bindings())));
            }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        }
    }
    static final List<String> CATEGORIES=List.of("raw.syntax","raw.bindings","raw.types","raw.declarations","raw.extensions","raw.source_hash","raw.diagnostics","facts.nodes","facts.edges","facts.annotations","facts.annotationMetaRelations","facts.semanticAnnotations","facts.declarations","facts.unresolvedCallSites");
    static void add(String domain,Object value) {
        String hash = value instanceof JavaTypes ? typeRows.get(value) : null;
        if(hash==null || dumping) {
            String row=Json.writeCompact(canonical(domain,value));
            hash=ContentHash.sha256(row);
            if(value instanceof JavaTypes) typeRows.put(value,hash);
            if(dumping) dumpRows.computeIfAbsent(domain,k->new ArrayList<>()).add(row);
        }
        domains.computeIfAbsent(domain,k->new ArrayList<>()).add(hash);
    }
    // Independent witness normalization operates on known type fields before JSON
    // encoding. Syntax IDs are rebased per unit together with binding keys/IDs;
    // annotation values, source literals and numerical type graph indices stay exact.
    @SuppressWarnings("unchecked")
    static Object canonical(String domain,Object value) {
        Object detached=domain.equals("raw.syntax") ? localSyntax(value) : detach(value);
        if(domain.equals("raw.types")) {
            var types=(JavaTypes)value;
            var entries=(List<?>)((Map<?,?>)detached).get("entries");
            for(int i=0;i<entries.size();i++) {
                var row=(Map<String,Object>)entries.get(i);
                for(String key:List.of("name","display"))row.put(key,graphSpelling(types,i,(String)row.get(key)));
            }
        } else if(domain.equals("raw.bindings")) {
            var local=new TreeMap<String,Object>();
            for(var entry:((Map<?,?>)detached).entrySet()) {
                var binding=(Map<String,Object>)entry.getValue();
                int id=((Number)binding.get("id")).intValue();
                if(id!=Integer.parseInt(entry.getKey().toString()))throw new IllegalArgumentException("BINDING_SYNTAX_ID_MISMATCH");
                binding.put("id",id-observedSyntaxBase);local.put(Integer.toString(id-observedSyntaxBase),binding);
            }
            detached=local;
            for(Object entry:local.values()) {
                var row=(Map<String,Object>)entry;
                int declared=((Number)row.get("declaredType")).intValue();
                row.put("type",graphSpelling(observedTypes,((Number)row.get("expressionType")).intValue(),(String)row.get("type")));
                if(declared>=0) {
                    var type=observedTypes.get(declared);
                    row.put("returns",graphSpelling(observedTypes,type.returns(),(String)row.get("returns")));
                    for(String key:List.of("parameters","exceptions")) {
                        var values=(List<?>)row.get(key);var refs=key.equals("parameters")?type.parameters():type.exceptions();
                        var mapped=new ArrayList<String>();
                        for(int i=0;i<values.size();i++)mapped.add(graphSpelling(observedTypes,i<refs.size()?refs.get(i):-1,(String)values.get(i)));
                        row.put(key,mapped);
                    }
                }
            }
        }
        return detached;
    }
    // Independent witness uses the original numerical graph, not product encoding.
    static String graphSpelling(JavaTypes types,int root,String text) {
        if(types==null||root<0||!text.contains("capture#"))return text;
        var references=new ArrayList<Integer>();
        collectCaptures(types,root,new HashSet<>(),references,new int[]{0});
        var tokens=java.util.regex.Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|(capture#\\d+)").matcher(text);
        var result=new StringBuilder();int at=0;
        while(tokens.find()) {
            if(tokens.group(1)==null)continue;
            while(at<references.size()) {
                int reference=references.get(at++);
                var printed=java.util.regex.Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|(capture#\\d+)").matcher(types.get(reference).display());
                String label=null;while(printed.find())if(printed.group(1)!=null){label=printed.group(1);break;}
                if(tokens.group(1).equals(label)){tokens.appendReplacement(result,"capture#"+reference);break;}
            }
        }
        return tokens.appendTail(result).toString();
    }
    static void collectCaptures(JavaTypes types,int root,Set<Integer> active,List<Integer> result,int[] work) {
        if(root<0)return;
        if(++work[0]>100000)throw new IllegalArgumentException("WITNESS_TYPE_SPELLING_BUDGET");
        var type=types.get(root);
        boolean capture=type.kind()==JavaType.Kind.TYPEVAR&&(type.name().equals("<captured wildcard>")||type.name().startsWith("capture#"));
        if(capture)result.add(root);
        if(!active.add(root))return;
        switch(type.kind()) {
            case ARRAY -> collectCaptures(types,type.component(),active,result,work);
            case DECLARED,ERROR -> {
                collectCaptures(types,type.enclosing(),active,result,work);
                for(int child:type.arguments())collectCaptures(types,child,active,result,work);
            }
            case EXECUTABLE -> {
                for(int child:type.parameters())collectCaptures(types,child,active,result,work);
                collectCaptures(types,type.returns(),active,result,work);
            }
            case UNION -> {for(int child:type.arguments())collectCaptures(types,child,active,result,work);}
            case INTERSECTION -> {for(int child:type.upperBounds())collectCaptures(types,child,active,result,work);}
            case WILDCARD,TYPEVAR -> {
                if(type.kind()==JavaType.Kind.WILDCARD||capture) {
                    if(type.lowerBound()>=0&&types.get(type.lowerBound()).kind()!=JavaType.Kind.NULL&&types.get(type.lowerBound()).kind()!=JavaType.Kind.NONE)
                        collectCaptures(types,type.lowerBound(),active,result,work);
                    else for(int child:type.upperBounds())collectCaptures(types,child,active,result,work);
                }
            }
            default -> {}
        }
        active.remove(root);
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
        var types=new JavaTypes(List.of(
            new JavaType(JavaType.Kind.DECLARED,"Pair","Pair<capture#934,capture#934,capture#934>",-1,List.of(1,2,1),List.of(),-1,-1,List.of(),-1,List.of(),List.of()),
            new JavaType(JavaType.Kind.TYPEVAR,"<captured wildcard>","capture#934 of ?",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of(),List.of()),
            new JavaType(JavaType.Kind.TYPEVAR,"<captured wildcard>","capture#934 of ?",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of(),List.of())));
        String graph=graphSpelling(types,0,"@Tag(\"capture#123\") Pair<capture#934,capture#934,capture#934>");
        if(!graph.equals("@Tag(\"capture#123\") Pair<capture#1,capture#2,capture#1>"))
            throw new IllegalStateException("WITNESS_CAPTURE_GRAPH_IDENTITY");
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
        dumping=args.length>5 && !args[5].equals("-");
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
            observedModule=module; observedScope=scope.name();
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
        if(args[4].equals("captured")) {
            long decodeBytes=Long.parseLong(System.getProperty("codeana.javac.decode.bytes","536870912"));
            backend.getClass().getMethod("setDecodeBudgetBytes",long.class).invoke(backend,decodeBytes);
        }
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
                if(visible.stream().noneMatch(file-> {
                    var identity=identities.get(file);
                    return identity.module().equals(c.get("module"))&&identity.scope().name().equals(c.get("scope"));
                }))continue;
                Set<String> deps=new LinkedHashSet<>((List<String>)c.getOrDefault("dependency_modules",List.of()));
                contexts.add(type.getConstructor(String.class,SourceScope.class,List.class,Set.class,Set.class).newInstance(c.get("module"),SourceScope.valueOf((String)c.get("scope")),visible,selected,deps));
            }
            backend.getClass().getMethod("setCapturedInputs",List.class,JavaSourcePlan.class,JavacMavenInputs.class).invoke(backend,contexts,new JavaSourcePlan(publish,files,roots,identities),inputs);
        }
        for(String domain:CATEGORIES)domains.put(domain,new ArrayList<>());
        ExtractionResult staged=new ExtractionResult();
        backend.analyzeFiles(publish,context->{
            if(!observe(context))return;
            for(var unit:context.units()) {
                observeUnit(unit);
                add("raw.syntax",unit.tree());add("raw.bindings",unit.bindings());add("raw.types",unit.types());add("raw.declarations",unit.declarationInventory());add("raw.extensions",unit.extensions());add("raw.source_hash",unit.sourceHash());
                unitEvidence(unit);
            }
            context.diagnostics().forEach(d->{add("raw.diagnostics",d);if(System.getenv("CODEANA_MODEL_ROW_EVIDENCE")!=null)diagnosticEvidence.add(detach(d));});
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
        String evidenceDirectory=System.getenv("CODEANA_MODEL_ROW_EVIDENCE");
        if(evidenceDirectory!=null)try {
            Path directory=Path.of(evidenceDirectory);Files.createDirectories(directory);
            Files.writeString(directory.resolve("model-units.json"),Json.writeCompact(unitEvidence));
            Files.writeString(directory.resolve("model-diagnostics.json"),Json.writeCompact(diagnosticEvidence));
        }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        Map<String,Object> evidence=new TreeMap<>();
        for(String domain:CATEGORIES) {
            var rows=domains.getOrDefault(domain,List.of()).stream().sorted().toList();
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                for(int i=0;i<rows.size();i++) {
                    if(i>0) digest.update((byte)'\n');
                    digest.update(rows.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                evidence.put(domain,Map.of("rows",rows.size(),"sha256",java.util.HexFormat.of().formatHex(digest.digest()),"encoding","sorted-row-sha256-v2-graph-captures"));
                if(output!=null) {Files.createDirectories(output);Files.writeString(output.resolve(domain+".json"),Json.writeCompact(dumpRows.getOrDefault(domain,List.of()).stream().sorted().toList()));}
            } catch(java.security.NoSuchAlgorithmException|java.io.IOException e) {throw new IllegalStateException(e);}
        }
        return evidence;
    }
}
