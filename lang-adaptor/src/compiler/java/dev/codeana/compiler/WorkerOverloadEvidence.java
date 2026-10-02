package dev.codeana.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/** Job-owned check against javac recovery selecting an arbitrary overload for an error argument. */
final class WorkerOverloadEvidence {
    private final Trees trees;
    private final Elements elements;
    private final Map<TypeElement, List<ExecutableElement>> members =
            new IdentityHashMap<TypeElement, List<ExecutableElement>>();

    WorkerOverloadEvidence(Trees trees, Elements elements) {
        this.trees = trees;
        this.elements = elements;
    }

    boolean selectionKnown(Tree node, TreePath path, ExecutableElement selected) {
        List<? extends ExpressionTree> arguments;
        if (node instanceof MethodInvocationTree)
            arguments = ((MethodInvocationTree) node).getArguments();
        else if (node instanceof NewClassTree) arguments = ((NewClassTree) node).getArguments();
        else return true;
        boolean error = false;
        boolean incompleteParameters = false;
        for (VariableElement parameter : selected.getParameters())
            incompleteParameters |= errorType(parameter.asType());
        for (ExpressionTree argument : arguments) {
            TypeMirror type = trees.getTypeMirror(new TreePath(path, argument));
            if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.NONE)
                error = true;
        }
        if (!error && !incompleteParameters) return true;
        // javac error types are permissive. A missing parameter can support recovery only
        // when the argument refers to the very same missing declaration, not merely another
        // error type with the same short spelling. Keep arbitrary error arguments to fully
        // known signatures under the existing unique-overload rule.
        if (incompleteParameters) {
            if (selected.isVarArgs() || arguments.size() != selected.getParameters().size())
                return false;
            for (int index = 0; index < arguments.size(); index++) {
                TypeMirror parameter = selected.getParameters().get(index).asType();
                if (!errorType(parameter)) continue;
                TypeMirror argument = trees.getTypeMirror(new TreePath(path, arguments.get(index)));
                if (!sameMissingDeclaration(argument, parameter, 0)) {
                    Element actual = trees.getElement(new TreePath(path, arguments.get(index)));
                    String expectedKey = missingSourceKey(selected.getParameters().get(index));
                    if (!(actual instanceof VariableElement)
                            || expectedKey == null
                            || !expectedKey.equals(missingSourceKey((VariableElement) actual)))
                        return false;
                }
            }
        }
        Element enclosing = selected.getEnclosingElement();
        if (!(enclosing instanceof TypeElement)) return false;
        TypeElement owner = (TypeElement) enclosing;
        if (incompleteParameters && !completeHierarchy(owner)) return false;
        List<ExecutableElement> methods = members.get(owner);
        if (methods == null) {
            methods = new ArrayList<ExecutableElement>();
            for (Element member : elements.getAllMembers(owner)) {
                if (member instanceof ExecutableElement) methods.add((ExecutableElement) member);
                if (methods.size() > 16384) return false;
            }
            members.put(owner, methods);
        }
        Set<String> candidates = new HashSet<String>();
        for (ExecutableElement method : methods) {
            if (method.getKind() != selected.getKind()
                    || !method.getSimpleName().contentEquals(selected.getSimpleName())) continue;
            int count = method.getParameters().size();
            if (method.isVarArgs() ? arguments.size() < count - 1 : arguments.size() != count)
                continue;
            StringBuilder signature = new StringBuilder();
            for (VariableElement parameter : method.getParameters())
                signature.append(parameter.asType()).append(';');
            candidates.add(signature.toString());
            if (candidates.size() > 1) return false;
        }
        return candidates.size() == 1;
    }

    private static boolean completeHierarchy(TypeElement owner) {
        ArrayDeque<TypeElement> pending = new ArrayDeque<TypeElement>();
        Set<TypeElement> visited =
                Collections.newSetFromMap(new IdentityHashMap<TypeElement, Boolean>());
        pending.add(owner);
        while (!pending.isEmpty()) {
            TypeElement current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (visited.size() > 256) return false;
            List<TypeMirror> parents = new ArrayList<TypeMirror>(current.getInterfaces());
            if (current.getSuperclass().getKind() != TypeKind.NONE)
                parents.add(current.getSuperclass());
            for (TypeMirror parent : parents) {
                if (parent.getKind() != TypeKind.DECLARED
                        || !(((DeclaredType) parent).asElement() instanceof TypeElement))
                    return false;
                pending.add((TypeElement) ((DeclaredType) parent).asElement());
            }
        }
        return true;
    }

    private String missingSourceKey(VariableElement variable) {
        TreePath source = trees.getPath(variable);
        if (source == null || !(source.getLeaf() instanceof VariableTree)) return null;
        Tree type = ((VariableTree) source.getLeaf()).getType();
        String suffix = "";
        for (int depth = 0; type instanceof ArrayTypeTree && depth < 128; depth++) {
            suffix += "[]";
            type = ((ArrayTypeTree) type).getType();
        }
        if (!(type instanceof IdentifierTree || type instanceof MemberSelectTree)) return null;
        String name = type.toString();
        String head = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String imported = null;
        boolean wildcard = false;
        for (ImportTree entry : source.getCompilationUnit().getImports()) {
            if (entry.isStatic()) return null;
            String qualified = entry.getQualifiedIdentifier().toString();
            if (qualified.endsWith(".*")) {
                wildcard = true;
                continue;
            }
            if (qualified.endsWith("." + head)) {
                if (imported != null && !imported.equals(qualified)) return null;
                imported = qualified;
            }
        }
        if (imported != null) return imported + name.substring(head.length()) + suffix;
        if (type instanceof MemberSelectTree) return name + suffix;
        if (wildcard) return null;
        ExpressionTree packageName = source.getCompilationUnit().getPackageName();
        return (packageName == null ? "" : packageName.toString() + ".") + name + suffix;
    }

    private static boolean errorType(TypeMirror type) {
        for (int depth = 0; type != null && depth < 128; depth++) {
            if (type.getKind() == TypeKind.ERROR) return true;
            if (type.getKind() != TypeKind.ARRAY) return false;
            type = ((ArrayType) type).getComponentType();
        }
        return false;
    }

    private static boolean sameMissingDeclaration(
            TypeMirror argument, TypeMirror parameter, int depth) {
        if (argument == null || depth >= 128 || argument.getKind() != parameter.getKind())
            return false;
        if (parameter.getKind() == TypeKind.ARRAY)
            return sameMissingDeclaration(
                    ((ArrayType) argument).getComponentType(),
                    ((ArrayType) parameter).getComponentType(),
                    depth + 1);
        return parameter.getKind() == TypeKind.ERROR
                && ((DeclaredType) argument).asElement() == ((DeclaredType) parameter).asElement()
                && !parameter.toString().isEmpty()
                && argument.toString().equals(parameter.toString());
    }
}
