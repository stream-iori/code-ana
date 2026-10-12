package com.anatomist.core.asmsolver;

import static org.junit.jupiter.api.Assertions.*;

import com.anatomist.core.java.JavaType;
import com.anatomist.core.java.JavaTypeNames;

import org.junit.jupiter.api.Test;

import java.util.List;

class AsmSignatureParserTest {
    @Test
    void classTypeParams_singleUnbounded() {
        assertEquals(
                List.of("E"), hierarchy("<E:Ljava/lang/Object;>Ljava/lang/Object;").parameters());
    }

    @Test
    void classTypeParams_multipleParams() {
        assertEquals(
                List.of("K", "V"),
                hierarchy("<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;")
                        .parameters());
    }

    @Test
    void classTypeParams_withBound() {
        var shape = hierarchy("<T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;");
        assertEquals(List.of("T"), shape.parameters());
        var variable =
                shape.types().entries().stream()
                        .filter(type -> type.kind() == JavaType.Kind.TYPEVAR)
                        .findFirst()
                        .orElseThrow();
        var bound = shape.types().get(variable.upperBounds().getFirst());
        assertEquals("java.lang.Comparable", bound.name());
        assertEquals(variable, shape.types().get(bound.arguments().getFirst()));
    }

    @Test
    void classTypeParams_nullSignature() {
        assertTrue(hierarchy(null).parameters().isEmpty());
    }

    @Test
    void methodReturnType_nullSignatureFallsBackToDescriptor() {
        var shape = method("()I", null);
        assertEquals("int", JavaTypeNames.erased(shape.types(), executable(shape).returns()));
    }

    @Test
    void methodReturnType_primitive() {
        var shape = method("()J", "()J");
        assertEquals(JavaType.Kind.LONG, returns(shape).kind());
    }

    @Test
    void methodReturnType_parameterizedReturn() {
        var shape = method("()Ljava/util/stream/Stream;", "()Ljava/util/stream/Stream<TE;>;");
        assertEquals("java.util.stream.Stream", returns(shape).name());
        assertEquals(1, returns(shape).arguments().size());
        assertEquals("E", shape.types().get(returns(shape).arguments().getFirst()).name());
    }

    @Test
    void methodReturnType_rawReturn() {
        assertEquals(
                "java.lang.String",
                returns(method("()Ljava/lang/String;", "()Ljava/lang/String;")).name());
    }

    @Test
    void methodReturnType_withTypeParamsAndReturn() {
        var shape =
                method(
                        "(Ljava/lang/Object;)Ljava/util/Optional;",
                        "<T:Ljava/lang/Object;>(TT;)Ljava/util/Optional<TT;>;");
        assertEquals("java.util.Optional", returns(shape).name());
        assertEquals(List.of("T"), shape.methodParameters());
        assertEquals(
                executable(shape).parameters().getFirst(), returns(shape).arguments().getFirst());
    }

    @Test
    void methodReturnType_doubleStream() {
        var shape =
                method(
                        "(Ljava/util/function/ToDoubleFunction;)Ljava/util/stream/DoubleStream;",
                        "(Ljava/util/function/ToDoubleFunction<-TT;>;)Ljava/util/stream/DoubleStream;");
        assertEquals("java.util.stream.DoubleStream", returns(shape).name());
        var parameter = shape.types().get(executable(shape).parameters().getFirst());
        var wildcard = shape.types().get(parameter.arguments().getFirst());
        assertEquals(JavaType.Kind.WILDCARD, wildcard.kind());
        assertEquals("T", shape.types().get(wildcard.lowerBound()).name());
    }

    @Test
    void methodParamTypes_simple() {
        var shape =
                method(
                        "(Ljava/util/function/Predicate;)Ljava/util/stream/Stream;",
                        "(Ljava/util/function/Predicate<-TT;>;)Ljava/util/stream/Stream<TT;>;");
        assertEquals(1, executable(shape).parameters().size());
        var parameter = shape.types().get(executable(shape).parameters().getFirst());
        assertEquals("java.util.function.Predicate", parameter.name());
        assertEquals(1, parameter.arguments().size());
    }

    @Test
    void methodParamTypes_nullSignatureFallsBackToDescriptor() {
        var shape = method("(I)V", null);
        assertEquals(
                JavaType.Kind.INT,
                shape.types().get(executable(shape).parameters().getFirst()).kind());
    }

    @Test
    void methodParamTypes_mapPut() {
        var shape =
                method("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", "(TK;TV;)TV;");
        assertEquals(
                List.of("K", "V"),
                executable(shape).parameters().stream()
                        .map(reference -> shape.types().get(reference).name())
                        .toList());
        assertTrue(
                executable(shape).parameters().stream()
                        .allMatch(
                                reference ->
                                        shape.types().get(reference).kind()
                                                == JavaType.Kind.TYPEVAR));
        assertEquals(executable(shape).parameters().getLast(), executable(shape).returns());
    }

    @Test
    void methodReturnType_typeVariable() {
        var shape = method("(Ljava/lang/Object;)Ljava/lang/Object;", "(Ljava/lang/Object;)TV;");
        assertEquals(JavaType.Kind.TYPEVAR, returns(shape).kind());
        assertEquals("V", returns(shape).name());
    }

    @Test
    void methodParamTypes_void() {
        var shape = method("()V", "()V");
        assertTrue(executable(shape).parameters().isEmpty());
        assertEquals(JavaType.Kind.VOID, returns(shape).kind());
    }

    private static JavaBinaryTypes.ClassShape hierarchy(String signature) {
        return JavaBinaryTypes.hierarchy(BinarySignatureTestSupport.owner(signature));
    }

    private static JavaBinaryTypes.MethodShape method(String descriptor, String signature) {
        return BinarySignatureTestSupport.method(descriptor, signature);
    }

    private static JavaType executable(JavaBinaryTypes.MethodShape shape) {
        return shape.types().get(shape.executable());
    }

    private static JavaType returns(JavaBinaryTypes.MethodShape shape) {
        return shape.types().get(executable(shape).returns());
    }
}
