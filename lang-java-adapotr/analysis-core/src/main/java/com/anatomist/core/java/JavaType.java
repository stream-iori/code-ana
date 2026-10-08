package com.anatomist.core.java;

import java.util.List;
import java.util.Objects;

/**
 * A node in a unit-local type graph. References allow recursive bounds without recursive objects.
 */
public record JavaType(
        Kind kind,
        String name,
        String display,
        int enclosing,
        List<Integer> arguments,
        List<Integer> upperBounds,
        int lowerBound,
        int component,
        List<Integer> parameters,
        int returns,
        List<Integer> exceptions,
        List<JavaAnnotation> annotations,
        String binaryName) {
    public enum Kind {
        BOOLEAN,
        BYTE,
        SHORT,
        INT,
        LONG,
        CHAR,
        FLOAT,
        DOUBLE,
        VOID,
        NULL,
        NONE,
        ARRAY,
        DECLARED,
        ERROR,
        TYPEVAR,
        WILDCARD,
        EXECUTABLE,
        INTERSECTION,
        UNION,
        PACKAGE,
        MODULE,
        OTHER
    }

    public JavaType {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(name);
        Objects.requireNonNull(display);
        Objects.requireNonNull(binaryName);
        arguments = List.copyOf(arguments);
        upperBounds = List.copyOf(upperBounds);
        parameters = List.copyOf(parameters);
        exceptions = List.copyOf(exceptions);
        annotations = List.copyOf(annotations);
    }

    public JavaType(
            Kind kind,
            String name,
            String display,
            int enclosing,
            List<Integer> arguments,
            List<Integer> upperBounds,
            int lowerBound,
            int component,
            List<Integer> parameters,
            int returns,
            List<Integer> exceptions,
            List<JavaAnnotation> annotations) {
        this(
                kind,
                name,
                display,
                enclosing,
                arguments,
                upperBounds,
                lowerBound,
                component,
                parameters,
                returns,
                exceptions,
                annotations,
                "");
    }

    public JavaType(
            Kind kind,
            String name,
            String display,
            int enclosing,
            List<Integer> arguments,
            List<Integer> upperBounds,
            int lowerBound,
            int component,
            List<Integer> parameters,
            int returns,
            List<Integer> exceptions) {
        this(
                kind,
                name,
                display,
                enclosing,
                arguments,
                upperBounds,
                lowerBound,
                component,
                parameters,
                returns,
                exceptions,
                List.of());
    }
}
