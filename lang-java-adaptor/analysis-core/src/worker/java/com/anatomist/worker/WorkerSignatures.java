package com.anatomist.worker;

import java.util.List;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** JVM names/descriptors/signatures from declarations, never javac display strings. */
final class WorkerSignatures {
    private final Types types;
    private final Elements elements;

    WorkerSignatures(Types types, Elements elements) {
        this.types = types;
        this.elements = elements;
    }

    String descriptor(Element element) {
        if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            StringBuilder result = new StringBuilder("(");
            for (VariableElement parameter : method.getParameters())
                result.append(descriptor(types.erasure(parameter.asType()), elements));
            return result.append(')')
                    .append(descriptor(types.erasure(method.getReturnType()), elements))
                    .toString();
        }
        return descriptor(types.erasure(element.asType()), elements);
    }

    String signature(Element element) {
        if (element instanceof TypeElement) {
            TypeElement type = (TypeElement) element;
            StringBuilder result = new StringBuilder(parameters(type.getTypeParameters()));
            TypeMirror parent = type.getSuperclass();
            result.append(parent.getKind() == TypeKind.NONE ? "Ljava/lang/Object;" : type(parent));
            for (TypeMirror implemented : type.getInterfaces()) result.append(type(implemented));
            return result.toString();
        }
        if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            StringBuilder result =
                    new StringBuilder(parameters(method.getTypeParameters())).append('(');
            for (VariableElement parameter : method.getParameters())
                result.append(type(parameter.asType()));
            result.append(')').append(type(method.getReturnType()));
            for (TypeMirror exception : method.getThrownTypes())
                result.append('^').append(type(exception));
            return result.toString();
        }
        return type(element.asType());
    }

    private String parameters(List<? extends TypeParameterElement> parameters) {
        if (parameters.isEmpty()) return "";
        StringBuilder result = new StringBuilder("<");
        for (TypeParameterElement parameter : parameters) {
            result.append(parameter.getSimpleName()).append(':');
            List<? extends TypeMirror> bounds = parameter.getBounds();
            for (int i = 0; i < bounds.size(); i++) {
                TypeMirror bound = bounds.get(i);
                Element declaration = types.asElement(bound);
                if (i > 0 || declaration != null && declaration.getKind().isInterface())
                    result.append(':');
                result.append(type(bound));
            }
        }
        return result.append('>').toString();
    }

    private String type(TypeMirror mirror) {
        switch (mirror.getKind()) {
            case TYPEVAR:
                return "T" + ((TypeVariable) mirror).asElement().getSimpleName() + ";";
            case ARRAY:
                return "[" + type(((ArrayType) mirror).getComponentType());
            case WILDCARD:
                WildcardType wildcard = (WildcardType) mirror;
                if (wildcard.getExtendsBound() != null)
                    return "+" + type(wildcard.getExtendsBound());
                if (wildcard.getSuperBound() != null) return "-" + type(wildcard.getSuperBound());
                return "*";
            case DECLARED:
                DeclaredType declared = (DeclaredType) mirror;
                TypeElement owner = (TypeElement) declared.asElement();
                TypeMirror enclosing = declared.getEnclosingType();
                String prefix;
                if (enclosing.getKind() == TypeKind.DECLARED
                        && !owner.getModifiers().contains(Modifier.STATIC)) {
                    String outer = type(enclosing);
                    prefix = outer.substring(0, outer.length() - 1) + "." + owner.getSimpleName();
                } else prefix = "L" + elements.getBinaryName(owner).toString().replace('.', '/');
                StringBuilder result = new StringBuilder(prefix);
                if (!declared.getTypeArguments().isEmpty()) {
                    result.append('<');
                    for (TypeMirror argument : declared.getTypeArguments())
                        result.append(type(argument));
                    result.append('>');
                }
                return result.append(';').toString();
            default:
                return descriptor(mirror, elements);
        }
    }

    static String canonicalName(TypeElement type) {
        for (Element owner = type; owner != null; owner = owner.getEnclosingElement()) {
            if (owner instanceof TypeElement) {
                NestingKind nesting = ((TypeElement) owner).getNestingKind();
                if (nesting == NestingKind.LOCAL || nesting == NestingKind.ANONYMOUS) return "";
            }
        }
        return type.getQualifiedName().toString();
    }

    static String descriptor(TypeMirror mirror, Elements elements) {
        switch (mirror.getKind()) {
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
                return "[" + descriptor(((ArrayType) mirror).getComponentType(), elements);
            case DECLARED:
                return "L"
                        + elements.getBinaryName((TypeElement) ((DeclaredType) mirror).asElement())
                                .toString()
                                .replace('.', '/')
                        + ";";
            default:
                throw new IllegalArgumentException("no descriptor for " + mirror);
        }
    }
}
