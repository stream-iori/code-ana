package com.anatomist.worker;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;

/** Unit-owned type graph. Never erases generic information to make it fit the protocol. */
final class WorkerTypes {
    private static final int MAX_TYPES = 100000;
    private final IdentityHashMap<TypeMirror, Integer> identities =
            new IdentityHashMap<TypeMirror, Integer>();
    private final IdentityHashMap<Element, Map<String, Integer>> variables =
            new IdentityHashMap<Element, Map<String, Integer>>();
    private final List<Entry> entries = new ArrayList<Entry>();
    private final Elements elements;
    private final IdentityHashMap<Tree, Integer> syntaxTypes = new IdentityHashMap<Tree, Integer>();

    WorkerTypes(Elements elements) {
        this.elements = elements;
    }

    int add(TypeMirror type) {
        return add(type, 0);
    }

    /** Java 8 drops type-use wrappers from declaration mirrors but keeps them on syntax paths. */
    int declarationType(Element element, Trees trees) {
        TreePath path = WorkerSourceTrees.path(element, trees);
        Tree syntax = path == null ? null : path.getLeaf();
        int reference = add(element.asType());
        if (path == null || syntax == null) return reference;
        if (syntax instanceof VariableTree) {
            Tree type = ((VariableTree) syntax).getType();
            if (type != null && hasAnnotations(type, 0))
                reference = sourceType(new TreePath(path, type), trees, 0);
            return sourceAnnotations(reference, path, trees);
        } else if (syntax instanceof MethodTree) {
            MethodTree method = (MethodTree) syntax;
            Entry original = entries.get(reference);
            int returns = original.returns;
            if (method.getReturnType() != null) {
                if (hasAnnotations(method.getReturnType(), 0))
                    returns = sourceType(new TreePath(path, method.getReturnType()), trees, 0);
                returns = sourceAnnotations(returns, path, trees);
            }
            int receiver = original.enclosing;
            VariableTree receiverSyntax = method.getReceiverParameter();
            if (receiverSyntax != null) {
                TreePath receiverPath = new TreePath(path, receiverSyntax);
                receiver =
                        sourceType(new TreePath(receiverPath, receiverSyntax.getType()), trees, 0);
                receiver = sourceAnnotations(receiver, receiverPath, trees);
            }
            List<Integer> parameters = new ArrayList<Integer>();
            for (VariableTree parameter : method.getParameters()) {
                TreePath parameterPath = new TreePath(path, parameter);
                int type = sourceType(new TreePath(parameterPath, parameter.getType()), trees, 0);
                parameters.add(Integer.valueOf(sourceAnnotations(type, parameterPath, trees)));
            }
            List<Integer> exceptions = new ArrayList<Integer>();
            for (Tree thrown : method.getThrows())
                exceptions.add(Integer.valueOf(sourceType(new TreePath(path, thrown), trees, 0)));
            if (receiver != original.enclosing
                    || returns != original.returns
                    || !parameters.equals(original.parameters)
                    || !exceptions.equals(original.exceptions)) {
                int sourceReference = entries.size();
                Entry result = copy(reference);
                result.returns = returns;
                result.enclosing = receiver;
                result.parameters = parameters;
                result.exceptions = exceptions;
                return sourceReference;
            }
        } else if (syntax instanceof TypeParameterTree) {
            TypeParameterTree parameter = (TypeParameterTree) syntax;
            boolean annotated = false;
            for (Tree bound : parameter.getBounds()) annotated |= hasAnnotations(bound, 0);
            if (annotated) {
                int sourceReference = entries.size();
                Entry result = copy(reference);
                List<Integer> bounds = new ArrayList<Integer>();
                for (Tree bound : parameter.getBounds())
                    bounds.add(Integer.valueOf(sourceType(new TreePath(path, bound), trees, 0)));
                if (bounds.size() > 1) {
                    int intersection = entries.size();
                    Entry upper = copy(result.upper.get(0).intValue());
                    upper.upper = bounds;
                    result.upper = Collections.singletonList(Integer.valueOf(intersection));
                } else result.upper = bounds;
                return sourceReference;
            }
        }
        return reference;
    }

    private int sourceAnnotations(int reference, TreePath path, Trees trees) {
        if (reference < 0) return reference;
        return modifierAnnotations(reference, WorkerSourceAnnotations.typeUses(path, trees), 0);
    }

    private int modifierAnnotations(int reference, List<AnnotationMirror> annotations, int depth) {
        if (depth > 128) throw new BudgetExceeded();
        if (annotations.isEmpty()) return reference;
        Entry type = entries.get(reference);
        // A modifier annotation applies to the base element/outer type, never an array dimension.
        if (type.kind.equals("ARRAY")) {
            int component = modifierAnnotations(type.component, annotations, depth + 1);
            if (component == type.component) return reference;
            int result = entries.size();
            copy(reference).component = component;
            return result;
        }
        if (type.enclosing >= 0 && type.kind.equals("DECLARED")) {
            int enclosing = modifierAnnotations(type.enclosing, annotations, depth + 1);
            if (enclosing == type.enclosing) return reference;
            int result = entries.size();
            copy(reference).enclosing = enclosing;
            return result;
        }
        List<AnnotationMirror> merged = new ArrayList<AnnotationMirror>(type.annotations);
        for (AnnotationMirror annotation : annotations) {
            boolean known = false;
            for (AnnotationMirror existing : merged)
                if (existing.getAnnotationType()
                        .toString()
                        .equals(annotation.getAnnotationType().toString())) known = true;
            if (!known) merged.add(annotation);
        }
        if (merged.size() == type.annotations.size()) return reference;
        int result = entries.size();
        copy(reference).annotations = merged;
        return result;
    }

    int sourceType(TreePath path, Trees trees, int depth) {
        Tree syntax = path.getLeaf();
        Integer known = syntaxTypes.get(syntax);
        if (known != null) return known.intValue();
        int original = add(trees.getTypeMirror(path));
        if (original < 0 || !hasAnnotations(syntax, depth)) return original;
        int reference = entries.size();
        Entry result = copy(original);
        syntaxTypes.put(syntax, Integer.valueOf(reference));
        structure(result, path, trees, depth + 1);
        return reference;
    }

    private void structure(Entry result, TreePath path, Trees trees, int depth) {
        if (depth > 128) throw new BudgetExceeded();
        Tree syntax = path.getLeaf();
        if (syntax instanceof AnnotatedTypeTree) {
            if (result.annotations.isEmpty())
                result.annotations = WorkerSourceAnnotations.typeUses(path, trees);
            structure(
                    result,
                    new TreePath(path, ((AnnotatedTypeTree) syntax).getUnderlyingType()),
                    trees,
                    depth + 1);
        } else if (syntax instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree parameterized = (ParameterizedTypeTree) syntax;
            result.arguments = new ArrayList<Integer>();
            for (Tree argument : parameterized.getTypeArguments())
                result.arguments.add(
                        Integer.valueOf(
                                sourceType(new TreePath(path, argument), trees, depth + 1)));
            structure(result, new TreePath(path, parameterized.getType()), trees, depth + 1);
        } else if (syntax instanceof ArrayTypeTree) {
            result.component =
                    sourceType(
                            new TreePath(path, ((ArrayTypeTree) syntax).getType()),
                            trees,
                            depth + 1);
        } else if (syntax instanceof WildcardTree) {
            Tree bound = ((WildcardTree) syntax).getBound();
            if (bound != null) {
                int reference = sourceType(new TreePath(path, bound), trees, depth + 1);
                if (syntax.getKind() == Tree.Kind.EXTENDS_WILDCARD)
                    result.upper = Collections.singletonList(Integer.valueOf(reference));
                else result.lower = reference;
            }
        } else if (syntax instanceof IntersectionTypeTree) {
            result.upper = new ArrayList<Integer>();
            for (Tree bound : ((IntersectionTypeTree) syntax).getBounds())
                result.upper.add(
                        Integer.valueOf(sourceType(new TreePath(path, bound), trees, depth + 1)));
        } else if (syntax instanceof UnionTypeTree) {
            result.arguments = new ArrayList<Integer>();
            for (Tree alternative : ((UnionTypeTree) syntax).getTypeAlternatives())
                result.arguments.add(
                        Integer.valueOf(
                                sourceType(new TreePath(path, alternative), trees, depth + 1)));
        } else if (syntax instanceof MemberSelectTree && result.enclosing >= 0) {
            result.enclosing =
                    sourceType(
                            new TreePath(path, ((MemberSelectTree) syntax).getExpression()),
                            trees,
                            depth + 1);
        }
    }

    private static boolean hasAnnotations(Tree syntax, int depth) {
        if (depth > 128) throw new BudgetExceeded();
        if (syntax instanceof AnnotatedTypeTree) return true;
        if (syntax instanceof ParameterizedTypeTree) {
            ParameterizedTypeTree type = (ParameterizedTypeTree) syntax;
            if (hasAnnotations(type.getType(), depth + 1)) return true;
            for (Tree argument : type.getTypeArguments())
                if (hasAnnotations(argument, depth + 1)) return true;
        } else if (syntax instanceof ArrayTypeTree)
            return hasAnnotations(((ArrayTypeTree) syntax).getType(), depth + 1);
        else if (syntax instanceof WildcardTree)
            return hasAnnotations(((WildcardTree) syntax).getBound(), depth + 1);
        else if (syntax instanceof MemberSelectTree)
            return hasAnnotations(((MemberSelectTree) syntax).getExpression(), depth + 1);
        else if (syntax instanceof IntersectionTypeTree) {
            for (Tree bound : ((IntersectionTypeTree) syntax).getBounds())
                if (hasAnnotations(bound, depth + 1)) return true;
        } else if (syntax instanceof UnionTypeTree) {
            for (Tree alternative : ((UnionTypeTree) syntax).getTypeAlternatives())
                if (hasAnnotations(alternative, depth + 1)) return true;
        }
        return false;
    }

    private Entry copy(int reference) {
        if (entries.size() >= MAX_TYPES) throw new BudgetExceeded();
        Entry value = entries.get(reference), result = new Entry(value.kind, value.display);
        result.name = value.name;
        result.binaryName = value.binaryName;
        result.enclosing = value.enclosing;
        result.lower = value.lower;
        result.component = value.component;
        result.returns = value.returns;
        result.arguments = value.arguments;
        result.upper = value.upper;
        result.parameters = value.parameters;
        result.exceptions = value.exceptions;
        result.annotations = value.annotations;
        entries.add(result);
        return result;
    }

    private int add(TypeMirror type, int depth) {
        if (type == null) return -1;
        Integer known = identities.get(type);
        if (known != null) return known.intValue();
        Element variable = null;
        String bounds = null;
        if (type.getKind() == TypeKind.TYPEVAR && !type.toString().startsWith("capture#")) {
            variable = ((TypeVariable) type).asElement();
            TypeVariable typeVariable = (TypeVariable) type;
            bounds =
                    typeVariable.getUpperBound()
                            + "|"
                            + typeVariable.getLowerBound()
                            + "|"
                            + type.getAnnotationMirrors();
            Map<String, Integer> substitutions = variables.get(variable);
            known = substitutions == null ? null : substitutions.get(bounds);
            if (known != null) return known.intValue();
        }
        if (depth > 128 || entries.size() >= MAX_TYPES) throw new BudgetExceeded();
        int id = entries.size();
        Entry entry = new Entry(type.getKind().name(), type.toString());
        entry.annotations = type.getAnnotationMirrors();
        entries.add(entry);
        identities.put(type, Integer.valueOf(id));
        if (variable != null) {
            Map<String, Integer> substitutions = variables.get(variable);
            if (substitutions == null) {
                substitutions = new HashMap<String, Integer>();
                variables.put(variable, substitutions);
            }
            substitutions.put(bounds, Integer.valueOf(id));
        }
        switch (type.getKind()) {
            case ARRAY:
                entry.component = add(((ArrayType) type).getComponentType(), depth + 1);
                break;
            case DECLARED:
            case ERROR:
                DeclaredType declared = (DeclaredType) type;
                Element element = declared.asElement();
                entry.name =
                        element instanceof TypeElement
                                ? WorkerSignatures.canonicalName((TypeElement) element)
                                : element.toString();
                if (element instanceof TypeElement)
                    entry.binaryName = elements.getBinaryName((TypeElement) element).toString();
                entry.enclosing = add(declared.getEnclosingType(), depth + 1);
                entry.arguments = addAll(declared.getTypeArguments(), depth + 1);
                break;
            case TYPEVAR:
                TypeVariable parameter = (TypeVariable) type;
                entry.name = parameter.asElement().getSimpleName().toString();
                entry.upper =
                        Collections.singletonList(
                                Integer.valueOf(add(parameter.getUpperBound(), depth + 1)));
                entry.lower = add(parameter.getLowerBound(), depth + 1);
                break;
            case WILDCARD:
                WildcardType wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null)
                    entry.upper =
                            Collections.singletonList(
                                    Integer.valueOf(add(wildcard.getExtendsBound(), depth + 1)));
                entry.lower = add(wildcard.getSuperBound(), depth + 1);
                break;
            case INTERSECTION:
                entry.upper = addAll(((IntersectionType) type).getBounds(), depth + 1);
                break;
            case UNION:
                entry.arguments = addAll(((UnionType) type).getAlternatives(), depth + 1);
                break;
            case EXECUTABLE:
                ExecutableType executable = (ExecutableType) type;
                entry.arguments = addAll(executable.getTypeVariables(), depth + 1);
                entry.enclosing = add(executable.getReceiverType(), depth + 1);
                entry.parameters = addAll(executable.getParameterTypes(), depth + 1);
                entry.returns = add(executable.getReturnType(), depth + 1);
                entry.exceptions = addAll(executable.getThrownTypes(), depth + 1);
                break;
            default:
                break;
        }
        return id;
    }

    private List<Integer> addAll(List<? extends TypeMirror> types, int depth) {
        List<Integer> result = new ArrayList<Integer>(types.size());
        for (TypeMirror type : types) result.add(Integer.valueOf(add(type, depth)));
        return result;
    }

    void intern(Map<String, Integer> dictionary) {
        for (Entry entry : entries) {
            intern(dictionary, entry.kind);
            intern(dictionary, entry.name);
            intern(dictionary, entry.display);
            intern(dictionary, entry.binaryName);
        }
    }

    private static void intern(Map<String, Integer> dictionary, String value) {
        if (!dictionary.containsKey(value))
            dictionary.put(value, Integer.valueOf(dictionary.size()));
    }

    void write(DataOutputStream out, Map<String, Integer> dictionary) throws IOException {
        out.writeInt(entries.size());
        for (Entry entry : entries) {
            out.writeInt(dictionary.get(entry.kind).intValue());
            out.writeInt(dictionary.get(entry.name).intValue());
            out.writeInt(dictionary.get(entry.display).intValue());
            out.writeInt(entry.enclosing);
            writeRefs(out, entry.arguments);
            writeRefs(out, entry.upper);
            out.writeInt(entry.lower);
            out.writeInt(entry.component);
            writeRefs(out, entry.parameters);
            out.writeInt(entry.returns);
            writeRefs(out, entry.exceptions);
            WorkerAnnotations.writeAnnotations(out, entry.annotations, elements);
            out.writeInt(dictionary.get(entry.binaryName).intValue());
        }
    }

    private static void writeRefs(DataOutputStream out, List<Integer> values) throws IOException {
        out.writeInt(values.size());
        for (Integer value : values) out.writeInt(value.intValue());
    }

    static final class BudgetExceeded extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        BudgetExceeded() {
            super("compiler type graph exceeds budget");
        }
    }

    private static final class Entry {
        final String kind, display;
        String name = "", binaryName = "";
        int enclosing = -1, lower = -1, component = -1, returns = -1;
        List<Integer> arguments = Collections.emptyList(),
                upper = Collections.emptyList(),
                parameters = Collections.emptyList(),
                exceptions = Collections.emptyList();
        List<? extends AnnotationMirror> annotations = Collections.emptyList();

        Entry(String kind, String display) {
            this.kind = kind;
            this.display = display;
        }
    }
}
