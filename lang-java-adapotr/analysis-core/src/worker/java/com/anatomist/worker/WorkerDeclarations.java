package com.anatomist.worker;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Unit-owned declaration inventory, including members absent from the original syntax tree. */
final class WorkerDeclarations {
    private final CompilationUnitTree unit;
    private final Trees trees;
    private final Elements elements;
    private final Types compilerTypes;
    private final Map<TypeElement, List<ExecutableElement>> superMethods =
            new IdentityHashMap<TypeElement, List<ExecutableElement>>();
    private final Map<TypeElement, Set<String>> superFailures =
            new IdentityHashMap<TypeElement, Set<String>>();
    private final WorkerTypes graph;
    private final WorkerSignatures signatures;
    private final Set<Tree> originalTrees;
    private final NavigableSet<Long> errors;
    private final List<Declaration> declarations = new ArrayList<Declaration>();
    private int declarationCount;

    WorkerDeclarations(
            CompilationUnitTree unit,
            Trees trees,
            Types types,
            Elements elements,
            WorkerTypes graph,
            Set<Tree> originalTrees,
            NavigableSet<Long> errors,
            final boolean declarationsOnly) {
        this.unit = unit;
        this.trees = trees;
        this.elements = elements;
        this.compilerTypes = types;
        this.graph = graph;
        this.signatures = new WorkerSignatures(types, elements);
        this.originalTrees = originalTrees;
        this.errors = errors;
        final Set<Element> seen =
                Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                return declarationsOnly ? null : super.visitMethod(node, unused);
            }

            @Override
            public Void visitBlock(BlockTree node, Void unused) {
                return declarationsOnly ? null : super.visitBlock(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                return declarationsOnly ? null : super.visitVariable(node, unused);
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                Element element = WorkerDeclarations.this.trees.getElement(getCurrentPath());
                if (element instanceof TypeElement && seen.add(element))
                    declarations.add(capture(element, 0));
                return super.visitClass(node, unused);
            }
        }.scan(unit, null);
    }

    byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(declarations.size());
            for (Declaration declaration : declarations) write(out, declaration, 0);
        }
        if (bytes.size() > 32 * 1024 * 1024)
            throw new IOException("declaration inventory exceeds frame budget");
        return bytes.toByteArray();
    }

    private Declaration capture(Element element, int depth) {
        if (depth > 128 || ++declarationCount > 100000) throw new WorkerTypes.BudgetExceeded();
        Declaration declaration = new Declaration(element);
        TypeMirror type = element.asType();
        try {
            declaration.declaredType = graph.declarationType(element, trees);
        } catch (WorkerSourceAnnotations.Unavailable unavailable) {
            declaration.declaredType = graph.add(type);
            declaration.reasons.add("type-annotation-unavailable");
        }
        Set<TypeMirror> visited =
                Collections.newSetFromMap(new IdentityHashMap<TypeMirror, Boolean>());
        Set<Element> variables = Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
        inspect(type, declaration.reasons, visited, variables, 0);
        for (AnnotationMirror annotation : element.getAnnotationMirrors())
            inspectAnnotation(annotation, declaration.reasons, visited, variables, 0);
        Tree syntax = declarationSyntax(element);
        if (syntax != null && originalTrees.contains(syntax) && !recordBackingField(element)) {
            long start = trees.getSourcePositions().getStartPosition(unit, syntax);
            long end = trees.getSourcePositions().getEndPosition(unit, syntax);
            if (start >= 0 && end >= start) {
                declaration.start = start;
                declaration.end = end;
                declaration.origin = "SOURCE";
                if (headerError(element, syntax, start, end))
                    declaration.reasons.add("declaration-diagnostic");
            }
        }
        try {
            declaration.descriptor = signatures.descriptor(element);
        } catch (IllegalArgumentException unavailable) {
            declaration.reasons.add("descriptor-unavailable");
        }
        try {
            declaration.signature = signatures.signature(element);
        } catch (IllegalArgumentException unavailable) {
            declaration.reasons.add("signature-unavailable");
        }
        if (element instanceof TypeElement) {
            TypeElement owner = (TypeElement) element;
            declaration.canonicalName = WorkerSignatures.canonicalName(owner);
            declaration.binaryName = elements.getBinaryName(owner).toString();
            if (owner.getSuperclass().getKind() != TypeKind.NONE) {
                declaration.superclass = graph.add(owner.getSuperclass());
                if (syntax instanceof ClassTree
                        && ((ClassTree) syntax).getExtendsClause() != null) {
                    int sourceType =
                            graph.sourceType(
                                    new TreePath(
                                            trees.getPath(element),
                                            ((ClassTree) syntax).getExtendsClause()),
                                    trees,
                                    0);
                    if (sourceType >= 0) declaration.superclass = sourceType;
                }
                inspect(owner.getSuperclass(), declaration.reasons, visited, variables, 0);
            }
            for (TypeMirror implemented : owner.getInterfaces()) {
                declaration.interfaces.add(Integer.valueOf(graph.add(implemented)));
                inspect(implemented, declaration.reasons, visited, variables, 0);
            }
            if (syntax instanceof ClassTree) {
                List<? extends Tree> interfaces = ((ClassTree) syntax).getImplementsClause();
                if (interfaces.size() == declaration.interfaces.size()) {
                    for (int i = 0; i < interfaces.size(); i++) {
                        int sourceType =
                                graph.sourceType(
                                        new TreePath(trees.getPath(element), interfaces.get(i)),
                                        trees,
                                        0);
                        if (sourceType >= 0)
                            declaration.interfaces.set(i, Integer.valueOf(sourceType));
                    }
                }
            }
            for (TypeMirror permitted : permitted(owner)) {
                declaration.permitted.add(Integer.valueOf(graph.add(permitted)));
                inspect(permitted, declaration.reasons, visited, variables, 0);
            }
            for (Element member : owner.getEnclosedElements()) {
                // Nested/local/anonymous types each have one unit-level inventory entry.
                if (member instanceof TypeElement || member instanceof TypeParameterElement)
                    continue;
                declaration.members.add(capture(member, depth + 1));
            }
            for (TypeParameterElement parameter : owner.getTypeParameters())
                declaration.members.add(capture(parameter, depth + 1));
        } else if (element instanceof ExecutableElement) {
            ExecutableElement method = (ExecutableElement) element;
            declaration.variadic = method.isVarArgs();
            for (VariableElement parameter : method.getParameters())
                declaration.members.add(capture(parameter, depth + 1));
            for (TypeParameterElement parameter : method.getTypeParameters())
                declaration.members.add(capture(parameter, depth + 1));
            if (method.getDefaultValue() != null)
                inspectValue(
                        method.getDefaultValue().getValue(),
                        declaration.reasons,
                        visited,
                        variables,
                        0);
            if (element.getKind() == ElementKind.METHOD
                    && !element.getModifiers().contains(Modifier.STATIC)
                    && !element.getModifiers().contains(Modifier.PRIVATE))
                overrides(method, declaration);
        }
        return declaration;
    }

    private void overrides(ExecutableElement method, Declaration declaration) {
        TypeElement owner = enclosingType(method);
        if (owner == null) return;
        try {
            List<ExecutableElement> candidates = superMethods.get(owner);
            if (candidates == null) {
                candidates = new ArrayList<ExecutableElement>();
                Set<String> failures = new LinkedHashSet<String>();
                Set<Element> seen =
                        Collections.newSetFromMap(new IdentityHashMap<Element, Boolean>());
                ArrayDeque<TypeMirror> pending = new ArrayDeque<TypeMirror>();
                pending.addAll(compilerTypes.directSupertypes(owner.asType()));
                while (!pending.isEmpty()) {
                    TypeMirror ancestor = pending.removeFirst();
                    if (ancestor.getKind() == TypeKind.ERROR) {
                        failures.add("override-hierarchy-unavailable:" + ancestor);
                        continue;
                    }
                    Element element = compilerTypes.asElement(ancestor);
                    if (!(element instanceof TypeElement) || !seen.add(element)) continue;
                    if (seen.size() > 10000) throw new WorkerTypes.BudgetExceeded();
                    for (Element member : element.getEnclosedElements()) {
                        if (member.getKind() == ElementKind.METHOD) {
                            if (candidates.size() >= 100000) throw new WorkerTypes.BudgetExceeded();
                            candidates.add((ExecutableElement) member);
                        }
                    }
                    pending.addAll(compilerTypes.directSupertypes(ancestor));
                }
                superMethods.put(owner, candidates);
                superFailures.put(owner, failures);
            }
            declaration.overrideReasons.addAll(superFailures.get(owner));
            for (ExecutableElement candidate : candidates)
                if (candidate.getSimpleName().contentEquals(method.getSimpleName())
                        && elements.overrides(method, candidate, owner))
                    declaration.overrides.add(
                            new OverrideTarget(
                                    candidate,
                                    graph.add(candidate.asType()),
                                    signatures.descriptor(candidate)));
        } catch (WorkerTypes.BudgetExceeded failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            declaration.overrideReasons.add("override-evidence-unavailable");
        }
    }

    private Tree declarationSyntax(Element element) {
        TreePath path = WorkerSourceTrees.path(element, trees);
        Tree syntax = path == null ? null : path.getLeaf();
        if (syntax != null && originalTrees.contains(syntax)
                || !element.getKind().name().equals("RECORD_COMPONENT")) return syntax;
        for (Element sibling : element.getEnclosingElement().getEnclosedElements()) {
            if (sibling.getKind() == ElementKind.FIELD
                    && sibling.getSimpleName().contentEquals(element.getSimpleName())) {
                Tree field = trees.getTree(sibling);
                if (field != null && originalTrees.contains(field)) return field;
            }
        }
        return null;
    }

    private static boolean recordBackingField(Element element) {
        if (element.getKind() != ElementKind.FIELD) return false;
        Element owner = element.getEnclosingElement();
        if (!owner.getKind().name().equals("RECORD")) return false;
        for (Element sibling : owner.getEnclosedElements())
            if (sibling.getKind().name().equals("RECORD_COMPONENT")
                    && sibling.getSimpleName().contentEquals(element.getSimpleName())) return true;
        return false;
    }

    private boolean headerError(Element element, Tree syntax, long start, long end) {
        if (errors == null) return false;
        if (errors.contains(Long.valueOf(-1))) return true;
        long headerEnd = end;
        if (syntax instanceof MethodTree && ((MethodTree) syntax).getBody() != null) {
            headerEnd =
                    trees.getSourcePositions()
                            .getStartPosition(unit, ((MethodTree) syntax).getBody());
        } else if (syntax instanceof VariableTree
                && ((VariableTree) syntax).getInitializer() != null) {
            TypeMirror type = element.asType();
            boolean constantType =
                    type.getKind().isPrimitive()
                            || type.getKind() == TypeKind.DECLARED
                                    && "java.lang.String"
                                            .equals(
                                                    ((TypeElement)
                                                                    ((DeclaredType) type)
                                                                            .asElement())
                                                            .getQualifiedName()
                                                            .toString());
            boolean constantCandidate =
                    element.getModifiers().contains(Modifier.STATIC)
                            && element.getModifiers().contains(Modifier.FINAL)
                            && constantType;
            if (!constantCandidate)
                headerEnd =
                        trees.getSourcePositions()
                                .getStartPosition(unit, ((VariableTree) syntax).getInitializer());
        } else if (syntax instanceof ClassTree) {
            for (Tree member : ((ClassTree) syntax).getMembers()) {
                if (!originalTrees.contains(member)) continue;
                long memberStart = trees.getSourcePositions().getStartPosition(unit, member);
                if (memberStart >= start) headerEnd = Math.min(headerEnd, memberStart);
            }
        }
        Long first = errors.ceiling(Long.valueOf(start));
        return first != null && first.longValue() < headerEnd;
    }

    private static List<? extends TypeMirror> permitted(TypeElement owner) {
        try {
            Method method = TypeElement.class.getMethod("getPermittedSubclasses");
            @SuppressWarnings("unchecked")
            List<? extends TypeMirror> result = (List<? extends TypeMirror>) method.invoke(owner);
            return result;
        } catch (NoSuchMethodException oldJdk) {
            return Collections.emptyList();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot read permitted declarations", failure);
        }
    }

    private void inspect(
            TypeMirror type,
            Set<String> reasons,
            Set<TypeMirror> seen,
            Set<Element> variables,
            int depth) {
        if (type == null || !seen.add(type)) return;
        if (depth > 128) throw new WorkerTypes.BudgetExceeded();
        for (AnnotationMirror annotation : type.getAnnotationMirrors())
            inspectAnnotation(annotation, reasons, seen, variables, depth + 1);
        switch (type.getKind()) {
            case ERROR:
                reasons.add("error-type:" + type);
                break;
            case ARRAY:
                inspect(((ArrayType) type).getComponentType(), reasons, seen, variables, depth + 1);
                break;
            case DECLARED:
                DeclaredType declared = (DeclaredType) type;
                inspect(declared.getEnclosingType(), reasons, seen, variables, depth + 1);
                for (TypeMirror argument : declared.getTypeArguments())
                    inspect(argument, reasons, seen, variables, depth + 1);
                break;
            case TYPEVAR:
                TypeVariable variable = (TypeVariable) type;
                if (variables.add(variable.asElement())) {
                    inspect(variable.getUpperBound(), reasons, seen, variables, depth + 1);
                    inspect(variable.getLowerBound(), reasons, seen, variables, depth + 1);
                }
                break;
            case WILDCARD:
                inspect(
                        ((WildcardType) type).getExtendsBound(),
                        reasons,
                        seen,
                        variables,
                        depth + 1);
                inspect(((WildcardType) type).getSuperBound(), reasons, seen, variables, depth + 1);
                break;
            case INTERSECTION:
                for (TypeMirror bound : ((IntersectionType) type).getBounds())
                    inspect(bound, reasons, seen, variables, depth + 1);
                break;
            case EXECUTABLE:
                ExecutableType executable = (ExecutableType) type;
                inspect(executable.getReturnType(), reasons, seen, variables, depth + 1);
                for (TypeMirror parameter : executable.getParameterTypes())
                    inspect(parameter, reasons, seen, variables, depth + 1);
                for (TypeMirror parameter : executable.getTypeVariables())
                    inspect(parameter, reasons, seen, variables, depth + 1);
                for (TypeMirror thrown : executable.getThrownTypes())
                    inspect(thrown, reasons, seen, variables, depth + 1);
                break;
            case OTHER:
                reasons.add("unsupported-type:" + type);
                break;
            default:
                break;
        }
    }

    private void inspectAnnotation(
            AnnotationMirror annotation,
            Set<String> reasons,
            Set<TypeMirror> seen,
            Set<Element> variables,
            int depth) {
        inspect(annotation.getAnnotationType(), reasons, seen, variables, depth + 1);
        for (AnnotationValue value : annotation.getElementValues().values())
            inspectValue(value.getValue(), reasons, seen, variables, depth + 1);
    }

    private void inspectValue(
            Object value,
            Set<String> reasons,
            Set<TypeMirror> seen,
            Set<Element> variables,
            int depth) {
        if (depth > 128) throw new WorkerTypes.BudgetExceeded();
        if (value instanceof AnnotationValue) value = ((AnnotationValue) value).getValue();
        if (value == null) reasons.add("annotation-value-unavailable");
        else if (value instanceof TypeMirror)
            inspect((TypeMirror) value, reasons, seen, variables, depth + 1);
        else if (value instanceof AnnotationMirror)
            inspectAnnotation((AnnotationMirror) value, reasons, seen, variables, depth + 1);
        else if (value instanceof List<?>)
            for (Object item : (List<?>) value)
                inspectValue(item, reasons, seen, variables, depth + 1);
    }

    private void write(DataOutputStream out, Declaration declaration, int depth)
            throws IOException {
        if (depth > 128) throw new IOException("declaration nesting exceeds budget");
        Element element = declaration.element;
        WorkerAnnotations.string(out, element.getKind().name());
        TypeElement owner = enclosingType(element);
        WorkerAnnotations.string(out, owner == null ? "" : owner.getQualifiedName().toString());
        WorkerAnnotations.string(out, element.getSimpleName().toString());
        WorkerAnnotations.string(out, declaration.descriptor);
        out.writeInt(declaration.declaredType);
        out.writeInt(element.getModifiers().size());
        for (Modifier modifier : element.getModifiers())
            WorkerAnnotations.string(out, modifier.toString());
        out.writeBoolean(declaration.variadic);
        WorkerAnnotations.string(out, declaration.canonicalName);
        WorkerAnnotations.string(out, declaration.binaryName);
        WorkerAnnotations.string(out, declaration.signature);
        out.writeLong(declaration.start);
        out.writeLong(declaration.end);
        WorkerAnnotations.string(out, declaration.origin);
        out.writeBoolean(elements.isDeprecated(element));
        out.writeInt(declaration.superclass);
        refs(out, declaration.interfaces);
        refs(out, declaration.permitted);
        WorkerAnnotations.writeAnnotations(out, element.getAnnotationMirrors(), elements);
        WorkerAnnotations.value(
                out,
                element instanceof VariableElement
                        ? ((VariableElement) element).getConstantValue()
                        : null,
                elements,
                0);
        WorkerAnnotations.value(
                out,
                element instanceof ExecutableElement
                        ? ((ExecutableElement) element).getDefaultValue()
                        : null,
                elements,
                0,
                element instanceof ExecutableElement
                        ? ((ExecutableElement) element).getReturnType()
                        : null);
        out.writeInt(declaration.members.size());
        for (Declaration member : declaration.members) write(out, member, depth + 1);
        out.writeInt(declaration.reasons.size());
        for (String reason : declaration.reasons) WorkerAnnotations.string(out, reason);
        out.writeInt(declaration.overrides.size());
        for (OverrideTarget target : declaration.overrides) {
            ExecutableElement method = target.method;
            TypeElement targetOwner = enclosingType(method);
            WorkerAnnotations.string(out, WorkerSignatures.canonicalName(targetOwner));
            WorkerAnnotations.string(out, elements.getBinaryName(targetOwner).toString());
            WorkerAnnotations.string(out, method.getSimpleName().toString());
            WorkerAnnotations.string(out, target.descriptor);
            out.writeInt(target.type);
            out.writeInt(method.getModifiers().size());
            for (Modifier modifier : method.getModifiers())
                WorkerAnnotations.string(out, modifier.toString());
            out.writeBoolean(method.isVarArgs());
        }
        out.writeInt(declaration.overrideReasons.size());
        for (String reason : declaration.overrideReasons) WorkerAnnotations.string(out, reason);
    }

    private static TypeElement enclosingType(Element element) {
        Element enclosing = element.getEnclosingElement();
        while (enclosing != null && !(enclosing instanceof TypeElement))
            enclosing = enclosing.getEnclosingElement();
        return (TypeElement) enclosing;
    }

    private static void refs(DataOutputStream out, List<Integer> refs) throws IOException {
        out.writeInt(refs.size());
        for (Integer ref : refs) out.writeInt(ref.intValue());
    }

    private static final class Declaration {
        final Element element;
        final Set<String> reasons = new LinkedHashSet<String>();
        final Set<String> overrideReasons = new LinkedHashSet<String>();
        final List<OverrideTarget> overrides = new ArrayList<OverrideTarget>();
        final List<Declaration> members = new ArrayList<Declaration>();
        final List<Integer> interfaces = new ArrayList<Integer>(),
                permitted = new ArrayList<Integer>();
        String descriptor = "",
                signature = "",
                canonicalName = "",
                binaryName = "",
                origin = "COMPILER_SYNTHETIC";
        long start = -1, end = -1;
        int declaredType, superclass = -1;
        boolean variadic;

        Declaration(Element element) {
            this.element = element;
        }
    }

    private static final class OverrideTarget {
        final ExecutableElement method;
        final int type;
        final String descriptor;

        OverrideTarget(ExecutableElement method, int type, String descriptor) {
            this.method = method;
            this.type = type;
            this.descriptor = descriptor;
        }
    }
}
