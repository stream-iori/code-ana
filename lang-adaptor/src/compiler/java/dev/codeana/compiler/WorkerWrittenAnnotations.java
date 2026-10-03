package dev.codeana.compiler;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import dev.codeana.common.Json;
import java.util.*;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.Elements;

/** Separate written annotation evidence for extensions; never changes compiler mirrors. */
final class WorkerWrittenAnnotations {
    private WorkerWrittenAnnotations() {}

    static List<Object> derive(TreePath declaration, Trees trees, Elements elements) {
        if (declaration == null) return Collections.emptyList();
        Tree leaf = declaration.getLeaf();
        ModifiersTree modifiers = leaf instanceof ClassTree ? ((ClassTree) leaf).getModifiers()
                : leaf instanceof MethodTree ? ((MethodTree) leaf).getModifiers()
                : leaf instanceof VariableTree ? ((VariableTree) leaf).getModifiers() : null;
        if (modifiers == null) return Collections.emptyList();
        List<Object> result = new ArrayList<Object>();
        TreePath path = new TreePath(declaration, modifiers);
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            TreePath annotationPath = new TreePath(path, annotation);
            TreePath typePath = new TreePath(annotationPath, annotation.getAnnotationType());
            TypeMirror mirror = trees.getTypeMirror(typePath);
            if (mirror != null && mirror.getKind() == TypeKind.DECLARED) continue;
            String name = qualified(typePath, trees, elements);
            if (name == null) continue;
            Map<String, Object> values = new TreeMap<String, Object>();
            for (ExpressionTree argument : annotation.getArguments()) {
                String key = "value";
                TreePath argumentPath = new TreePath(annotationPath, argument);
                if (argument instanceof AssignmentTree) {
                    key = ((AssignmentTree) argument).getVariable().toString();
                    argumentPath = new TreePath(argumentPath, ((AssignmentTree) argument).getExpression());
                }
                Object value = literal(argumentPath, trees, elements, 0);
                if (values.put(key, value) != null)
                    values.put(key, unknown("duplicate-argument"));
            }
            result.add(Json.object("type", name, "source_name", annotation.getAnnotationType().toString(),
                    "explicit", values, "effective", values, "retention", "SOURCE", "complete", false,
                    "binding_origin", "written_annotation", "source_options_complete", complete(values), "original_evidence_retained", true));
        }
        return result;
    }

    private static String qualified(TreePath path, Trees trees, Elements elements) {
        TypeMirror mirror = trees.getTypeMirror(path);
        if (mirror != null && mirror.getKind() == TypeKind.DECLARED)
            return ((TypeElement) ((DeclaredType) mirror).asElement()).getQualifiedName().toString();
        String raw = path.getLeaf().toString();
        CompilationUnitTree unit = path.getCompilationUnit();
        Set<String> imports = new TreeSet<String>();
        for (ImportTree item : unit.getImports())
            if (!item.isStatic() && item.getQualifiedIdentifier().toString().endsWith("." + raw))
                imports.add(item.getQualifiedIdentifier().toString());
        if (imports.size() == 1) return imports.iterator().next();
        if (imports.size() > 1) return null;
        if (raw.contains(".")) return raw;
        String local = unit.getPackageName() == null ? raw : unit.getPackageName() + "." + raw;
        if (found(elements, local)) return local;
        Set<String> candidates = new TreeSet<String>();
        for (ImportTree item : unit.getImports()) {
            String imported = item.getQualifiedIdentifier().toString();
            if (!item.isStatic() && imported.endsWith(".*")) {
                String candidate = imported.substring(0, imported.length() - 1) + raw;
                if (found(elements, candidate)) candidates.add(candidate);
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private static boolean found(Elements elements, String name) {
        TypeElement type = elements.getTypeElement(name);
        return type != null && type.asType().getKind() == TypeKind.DECLARED;
    }

    private static Object scalar(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Long
                || value instanceof Float || value instanceof Double)
            return Json.object("kind", "primitive", "type", value.getClass().getSimpleName().toString().toLowerCase(Locale.ROOT), "value", value);
        return value;
    }

    private static boolean complete(Object value) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (Boolean.FALSE.equals(map.get("complete"))) return false;
            for (Object child : map.values()) if (!complete(child)) return false;
        } else if (value instanceof List)
            for (Object child : (List<?>) value) if (!complete(child)) return false;
        return true;
    }

    private static Object unknown(String reason) {
        return Json.object("kind", "unknown", "complete", false, "reason", reason);
    }

    private static Object literal(TreePath path, Trees trees, Elements elements, int depth) {
        if (depth > 64) return unknown("annotation-expression-budget");
        Tree node = path.getLeaf();
        Element element = trees.getElement(path);
        if (element instanceof VariableElement && element.asType().getKind() != TypeKind.ERROR) {
            VariableElement variable = (VariableElement) element;
            if (variable.getKind() == ElementKind.ENUM_CONSTANT)
                return Json.object("kind", "enum", "type", ((TypeElement) variable.getEnclosingElement()).getQualifiedName().toString(), "name", variable.getSimpleName().toString());
            if (variable.getConstantValue() != null) return scalar(variable.getConstantValue());
        }
        if (node instanceof LiteralTree) {
            Object value = ((LiteralTree) node).getValue();
            if (value instanceof Character) value = (int) ((Character) value).charValue();
            return value == null ? unknown("null-annotation-value") : scalar(value);
        }
        if (node instanceof ParenthesizedTree)
            return literal(new TreePath(path, ((ParenthesizedTree) node).getExpression()), trees, elements, depth + 1);
        if (node instanceof NewArrayTree && ((NewArrayTree) node).getInitializers() != null) {
            List<Object> values = new ArrayList<Object>();
            for (ExpressionTree item : ((NewArrayTree) node).getInitializers())
                values.add(literal(new TreePath(path, item), trees, elements, depth + 1));
            return Json.object("kind", "array", "values", values);
        }
        if (node instanceof MemberSelectTree && ((MemberSelectTree) node).getIdentifier().contentEquals("class")) {
            String name = qualified(new TreePath(path, ((MemberSelectTree) node).getExpression()), trees, elements);
            if (name != null) return Json.object("kind", "class", "name", name, "descriptor", "L" + name.replace('.', '/') + ";", "complete", true);
        }
        return unknown("unresolved-static-value");
    }
}
