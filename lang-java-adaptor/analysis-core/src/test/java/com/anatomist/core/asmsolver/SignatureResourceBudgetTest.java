package com.anatomist.core.asmsolver;

import com.anatomist.core.*;
import com.anatomist.core.java.*;
import com.anatomist.core.java.JavaBinaryType.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SignatureResourceBudgetTest {
    private static AnalysisContext budget(long work,long bytes,int depth) {
        return new AnalysisContext(new AnalysisContext.Limits(work,1000000,bytes,depth));
    }
    private static AnalysisContext normal() {return new AnalysisContext(AnalysisContext.Limits.defaults());}
    private static JavaDeclaration declaration(String name,String signature,List<JavaDeclaration> members) {
        return new JavaDeclaration(new JavaSymbol("CLASS","",name,"L"+name+";",-1,List.of("public"),false),name,name,signature,SourceSpan.ABSENT,JavaDeclaration.Origin.SOURCE,-1,List.of(),List.of(),List.of(),null,null,members,List.of());
    }
    private static JavaUnit unit(JavaDeclaration declaration,JavaTypes types) {
        return new JavaUnit(Path.of("Box.java"),new JavaNode(0,"COMPILATION_UNIT",-1,-1,Map.of()),JavaProcessingState.NONE,Map.of(),types,false,List.of(declaration));
    }
    private static JavaUnit unit() {return unit(declaration("Box","Ljava/lang/Object;",List.of()),JavaTypes.EMPTY);}
    private static JavaBinaryType type() {
        return ((JavaSignatureTypes.Complete)JavaSignatureTypes.project(List.of(unit()),8)).types().getFirst();
    }
    private static long used(AnalysisContext budget,String field) {return ((Number)budget.usage().get(field)).longValue();}
    @Test void sharedAccountContinuesThroughContractViewProjectionAndAsmWithoutChangingBytes() {
        var unit=unit();var budget=normal();
        assertEquals(JavaContract.fingerprint(unit),JavaContract.fingerprint(unit,budget));
        long first=used(budget,"payload_bytes");
        var compact=JavaDeclarationView.of(unit,budget);
        assertEquals(JavaContract.fingerprint(unit),JavaContract.fingerprint(compact,budget));
        var projected=(JavaSignatureTypes.Complete)JavaSignatureTypes.project(List.of(compact),8,budget);
        assertArrayEquals(JavaBinaryWriter.signature(type()),JavaBinaryWriter.signature(projected.types().getFirst(),budget));
        assertTrue(used(budget,"payload_bytes")>first);assertNull(budget.usage().get("failure"));
    }
    @Test void exhaustedLedgerCannotStartASecondProjectionOrWriter() {
        var budget=budget(100000,1,256);
        assertThrows(HardResourceLimit.class,()->JavaSignatureTypes.project(List.of(unit()),8,budget));
        assertEquals(0,used(budget,"payload_bytes"));
        assertThrows(HardResourceLimit.class,()->JavaBinaryWriter.signature(type(),budget));
        assertThrows(HardResourceLimit.class,()->JavaContract.fingerprint(unit(),budget));
    }
    @Test void asmAdmissionRejectsLargeAnnotationBeforeAsmUtf8AllocationOrEncoding() {
        var source=type();var value=new Annotation("LMark;",true,Map.of("text",new Scalar("x".repeat(100000))));
        var type=new JavaBinaryType(source.version(),source.access(),source.name(),source.signature(),source.superclass(),source.interfaces(),List.of(),List.of(),List.of(value),List.of(),List.of(),List.of(),List.of(),null,List.of(),null);
        var budget=budget(100000,16384,256);
        assertThrows(HardResourceLimit.class,()->JavaBinaryWriter.signature(type,budget));
        assertTrue(used(budget,"payload_bytes")<=16384);
        var failure=(Map<?,?>)((Map<?,?>)budget.usage().get("failure")).get("Limit");
        assertEquals("payload_bytes",failure.get("resource"));
    }
    @Test void annotationDepthUsesTaskLimitBeforeRecursingIntoAsm() {
        Value value=new Scalar(1);
        for(int i=0;i<40;i++)value=new ArrayValue(List.of(value));
        var source=type();var annotation=new Annotation("LMark;",true,Map.of("value",value));
        var type=new JavaBinaryType(source.version(),source.access(),source.name(),source.signature(),source.superclass(),List.of(),List.of(),List.of(),List.of(annotation),List.of(),List.of(),List.of(),List.of(),null,List.of(),null);
        var budget=budget(100000,1<<20,8);
        assertThrows(HardResourceLimit.class,()->JavaBinaryWriter.signature(type,budget));
        assertEquals(8,used(budget,"max_depth"));
    }
    @Test void nestedDeclarationsStopAtSharedDepthBeforeBuildingProjectionCopies() {
        JavaDeclaration declaration=declaration("Leaf","Ljava/lang/Object;",List.of());
        for(int i=0;i<40;i++)declaration=declaration("Box"+i,"Ljava/lang/Object;",List.of(declaration));
        var unit=unit(declaration,JavaTypes.EMPTY);var budget=budget(100000,1<<20,8);
        assertThrows(HardResourceLimit.class,()->JavaSignatureTypes.project(List.of(unit),8,budget));
        assertThrows(HardResourceLimit.class,()->JavaDeclarationView.of(unit,budget));
    }
    @Test void asmSignatureParserCannotBypassTaskDepthWithNestedTypeArguments() {
        var unit=unit(declaration("Box","Ljava/util/List<".repeat(100)+"Ljava/lang/String;"+">;".repeat(100),List.of()),JavaTypes.EMPTY);
        var budget=budget(100000,1<<20,8);
        assertThrows(HardResourceLimit.class,()->JavaSignatureTypes.project(List.of(unit),8,budget));
    }
    @Test void cyclicExceptionErasureTerminatesWithTypedFailureInsteadOfStackOverflow() {
        var variable=new JavaType(JavaType.Kind.TYPEVAR,"T","",-1,List.of(),List.of(0),-1,-1,List.of(),-1,List.of());
        var executable=new JavaType(JavaType.Kind.EXECUTABLE,"","",-1,List.of(),List.of(),-1,-1,List.of(),-1,List.of(0));
        var method=new JavaDeclaration(new JavaSymbol("METHOD","Box","f","()V",1,List.of(),false),"Box#f","","()V",SourceSpan.ABSENT,JavaDeclaration.Origin.SOURCE,-1,List.of(),List.of(),List.of(),null,null,List.of(),List.of());
        var unit=unit(declaration("Box","Ljava/lang/Object;",List.of(method)),new JavaTypes(List.of(variable,executable)));
        var budget=normal();assertThrows(HardResourceLimit.class,()->JavaSignatureTypes.project(List.of(unit),8,budget));
    }
    @Test void cancellationReachesAllThreeSignatureStages() {
        var budget=normal();budget.cancel();
        assertThrows(FatalAnalysisFailure.class,()->JavaContract.fingerprint(unit(),budget));
        assertThrows(FatalAnalysisFailure.class,()->JavaDeclarationView.of(unit(),budget));
        assertThrows(FatalAnalysisFailure.class,()->JavaBinaryWriter.signature(type(),budget));
        assertEquals(0,used(budget,"payload_bytes"));
    }
}
