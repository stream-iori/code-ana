import com.anatomist.core.java.*;
import com.anatomist.model.*;
import dev.codeana.adapter.*;
import dev.codeana.common.Json;
import java.nio.file.*;

/** Test executable observes the same AnalysisSession used by the production shared library. */
public final class NativeModelWitness {
    public static void main(String[] args) throws Exception {
        ParityWitness.verifyCaptureNormalization();
        var request=Json.map(Json.parse(Files.readAllBytes(Path.of(args[0]))));
        try(var session=new AnalysisSession(request,new AnalysisObserver() {
            public void model(JavaAnalysisContext context) {
                for(var unit:context.units()) {
                    Path path=unit.path();
                    while(path!=null && !path.getFileName().toString().equals("anatomist-inputs")) path=path.getParent();
                    if(path==null) throw new IllegalArgumentException("MODEL_CAPTURE_ROOT");
                    ParityWitness.sourceRoot=path;
                    ParityWitness.add("raw.syntax",unit.tree()); ParityWitness.add("raw.bindings",unit.bindings());
                    ParityWitness.add("raw.types",unit.types()); ParityWitness.add("raw.declarations",unit.declarationInventory());
                    ParityWitness.add("raw.extensions",unit.extensions()); ParityWitness.add("raw.source_hash",unit.sourceHash());
                }
                context.diagnostics().forEach(d->ParityWitness.add("raw.diagnostics",d));
            }
            public void extraction(ExtractionResult facts) {
                facts.nodes.forEach(v->ParityWitness.add("facts.nodes",v)); facts.edges.forEach(v->ParityWitness.add("facts.edges",v));
                facts.annotations.forEach(v->ParityWitness.add("facts.annotations",v)); facts.annotationMetaRelations.forEach(v->ParityWitness.add("facts.annotationMetaRelations",v));
                facts.semanticAnnotations.forEach(v->ParityWitness.add("facts.semanticAnnotations",v)); facts.declarations.forEach(v->ParityWitness.add("facts.declarations",v));
                facts.unresolvedCallSites.forEach(v->ParityWitness.add("facts.unresolvedCallSites",v));
            }
        })) { session.analyze(); }
        System.out.println(com.anatomist.json.Json.writeCompact(ParityWitness.finish(null)));
    }
}
