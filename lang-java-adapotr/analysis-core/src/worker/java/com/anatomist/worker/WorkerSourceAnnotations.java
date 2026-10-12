package com.anatomist.worker;

import com.sun.source.tree.*;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;

/** Public-API recovery of type-use annotations lost by Java 8 declaration type variables. */
final class WorkerSourceAnnotations {
    private WorkerSourceAnnotations() {}

    static List<AnnotationMirror> typeUses(TreePath declaration, Trees trees) {
        Tree syntax = declaration.getLeaf();
        ModifiersTree modifiers =
                syntax instanceof VariableTree
                        ? ((VariableTree) syntax).getModifiers()
                        : syntax instanceof MethodTree
                                ? ((MethodTree) syntax).getModifiers()
                                : null;
        if (modifiers == null && !(syntax instanceof AnnotatedTypeTree))
            return Collections.emptyList();
        List<AnnotationMirror> annotations = new ArrayList<AnnotationMirror>();
        TreePath modifierPath =
                modifiers == null ? declaration : new TreePath(declaration, modifiers);
        List<? extends AnnotationTree> source =
                modifiers == null
                        ? ((AnnotatedTypeTree) syntax).getAnnotations()
                        : modifiers.getAnnotations();
        for (AnnotationTree annotation : source) {
            TreePath path = new TreePath(modifierPath, annotation);
            TypeMirror type =
                    trees.getTypeMirror(new TreePath(path, annotation.getAnnotationType()));
            if (type == null || type.getKind() != TypeKind.DECLARED) throw new Unavailable();
            TypeElement owner = (TypeElement) ((DeclaredType) type).asElement();
            if (isTypeUse(owner)) annotations.add(annotation(path, trees, 0));
        }
        Map<String, List<AnnotationMirror>> groups =
                new LinkedHashMap<String, List<AnnotationMirror>>();
        for (AnnotationMirror annotation : annotations) {
            String name = annotation.getAnnotationType().toString();
            List<AnnotationMirror> group = groups.get(name);
            if (group == null) {
                group = new ArrayList<AnnotationMirror>();
                groups.put(name, group);
            }
            group.add(annotation);
        }
        List<AnnotationMirror> normalized = new ArrayList<AnnotationMirror>();
        for (List<AnnotationMirror> group : groups.values()) {
            if (group.size() == 1) normalized.add(group.get(0));
            else normalized.add(container(group));
        }
        return normalized;
    }

    private static AnnotationMirror container(List<AnnotationMirror> repeated) {
        TypeElement element = (TypeElement) repeated.get(0).getAnnotationType().asElement();
        for (AnnotationMirror meta : element.getAnnotationMirrors()) {
            if (!meta.getAnnotationType().toString().equals("java.lang.annotation.Repeatable"))
                continue;
            for (AnnotationValue argument : meta.getElementValues().values()) {
                if (!(argument.getValue() instanceof DeclaredType)) continue;
                final DeclaredType type = (DeclaredType) argument.getValue();
                for (Element member : type.asElement().getEnclosedElements()) {
                    if (!(member instanceof ExecutableElement)
                            || !member.getSimpleName().contentEquals("value")) continue;
                    ExecutableElement value = (ExecutableElement) member;
                    TypeMirror component = ((ArrayType) value.getReturnType()).getComponentType();
                    List<AnnotationValue> contents = new ArrayList<AnnotationValue>();
                    for (AnnotationMirror annotation : repeated)
                        contents.add(converted(annotation, component));
                    final Map<ExecutableElement, AnnotationValue> values =
                            Collections.singletonMap(
                                    value, converted(contents, value.getReturnType()));
                    return new AnnotationMirror() {
                        @Override
                        public DeclaredType getAnnotationType() {
                            return type;
                        }

                        @Override
                        public Map<? extends ExecutableElement, ? extends AnnotationValue>
                                getElementValues() {
                            return values;
                        }
                    };
                }
            }
        }
        throw new Unavailable();
    }

    private static boolean isTypeUse(TypeElement annotation) {
        for (AnnotationMirror meta : annotation.getAnnotationMirrors()) {
            if (!"java.lang.annotation.Target".equals(meta.getAnnotationType().toString()))
                continue;
            for (AnnotationValue value : meta.getElementValues().values()) {
                Object raw = value.getValue();
                if (!(raw instanceof List<?>)) continue;
                for (Object item : (List<?>) raw) {
                    Object target = ((AnnotationValue) item).getValue();
                    if (target instanceof VariableElement
                            && ((VariableElement) target).getSimpleName().contentEquals("TYPE_USE"))
                        return true;
                }
            }
        }
        return false;
    }

    private static AnnotationMirror annotation(TreePath path, Trees trees, int depth) {
        depth(depth);
        AnnotationTree syntax = (AnnotationTree) path.getLeaf();
        TypeMirror mirror = trees.getTypeMirror(new TreePath(path, syntax.getAnnotationType()));
        if (mirror == null || mirror.getKind() != TypeKind.DECLARED) throw new Unavailable();
        final DeclaredType type = (DeclaredType) mirror;
        Map<String, ExecutableElement> properties = new LinkedHashMap<String, ExecutableElement>();
        for (Element member : type.asElement().getEnclosedElements())
            if (member instanceof ExecutableElement)
                properties.put(member.getSimpleName().toString(), (ExecutableElement) member);
        final Map<ExecutableElement, AnnotationValue> values =
                new LinkedHashMap<ExecutableElement, AnnotationValue>();
        for (ExpressionTree argument : syntax.getArguments()) {
            String name = "value";
            ExpressionTree expression = argument;
            TreePath argumentPath = new TreePath(path, argument);
            if (argument instanceof AssignmentTree) {
                AssignmentTree assignment = (AssignmentTree) argument;
                name = assignment.getVariable().toString();
                expression = assignment.getExpression();
                argumentPath = new TreePath(argumentPath, expression);
            }
            ExecutableElement property = properties.get(name);
            if (property == null || values.containsKey(property)) throw new Unavailable();
            values.put(
                    property,
                    converted(
                            constant(argumentPath, trees, property.getReturnType(), depth + 1),
                            property.getReturnType()));
        }
        return new AnnotationMirror() {
            @Override
            public DeclaredType getAnnotationType() {
                return type;
            }

            @Override
            public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValues() {
                return Collections.unmodifiableMap(values);
            }

            @Override
            public String toString() {
                return "@" + type + values;
            }
        };
    }

    private static Object constant(TreePath path, Trees trees, TypeMirror expected, int depth) {
        depth(depth);
        Tree syntax = path.getLeaf();
        if (syntax instanceof ParenthesizedTree)
            return constant(
                    new TreePath(path, ((ParenthesizedTree) syntax).getExpression()),
                    trees,
                    expected,
                    depth + 1);
        if (syntax instanceof LiteralTree) return ((LiteralTree) syntax).getValue();
        if (syntax instanceof AnnotationTree) return annotation(path, trees, depth + 1);
        if (syntax instanceof NewArrayTree) {
            List<? extends ExpressionTree> initializers = ((NewArrayTree) syntax).getInitializers();
            if (initializers == null || expected.getKind() != TypeKind.ARRAY)
                throw new Unavailable();
            TypeMirror component = ((ArrayType) expected).getComponentType();
            List<AnnotationValue> values = new ArrayList<AnnotationValue>();
            for (ExpressionTree initializer : initializers)
                values.add(
                        converted(
                                constant(
                                        new TreePath(path, initializer),
                                        trees,
                                        component,
                                        depth + 1),
                                component));
            return values;
        }
        if (syntax instanceof MemberSelectTree
                && ((MemberSelectTree) syntax).getIdentifier().contentEquals("class")) {
            TypeMirror value =
                    trees.getTypeMirror(
                            new TreePath(path, ((MemberSelectTree) syntax).getExpression()));
            if (value == null || value.getKind() == TypeKind.ERROR) throw new Unavailable();
            return value;
        }
        if (syntax instanceof IdentifierTree || syntax instanceof MemberSelectTree) {
            Element element = trees.getElement(path);
            if (element instanceof VariableElement) {
                VariableElement variable = (VariableElement) element;
                if (variable.getKind() == ElementKind.ENUM_CONSTANT) return variable;
                Object value = variable.getConstantValue();
                if (value != null) return value;
            }
            throw new Unavailable();
        }
        if (syntax instanceof ConditionalExpressionTree) {
            ConditionalExpressionTree conditional = (ConditionalExpressionTree) syntax;
            Object condition =
                    constant(
                            new TreePath(path, conditional.getCondition()),
                            trees,
                            expected,
                            depth + 1);
            if (!(condition instanceof Boolean)) throw new Unavailable();
            ExpressionTree branch =
                    ((Boolean) condition).booleanValue()
                            ? conditional.getTrueExpression()
                            : conditional.getFalseExpression();
            TypeMirror type = trees.getTypeMirror(path);
            if (type == null) throw new Unavailable();
            Object value = constant(new TreePath(path, branch), trees, type, depth + 1);
            return value instanceof Number || value instanceof Character
                    ? numeric(number(value), type.getKind())
                    : value;
        }
        if (syntax instanceof TypeCastTree) {
            TypeCastTree cast = (TypeCastTree) syntax;
            TypeMirror type = trees.getTypeMirror(new TreePath(path, cast.getType()));
            if (type == null) throw new Unavailable();
            return converted(
                            constant(
                                    new TreePath(path, cast.getExpression()),
                                    trees,
                                    type,
                                    depth + 1),
                            type)
                    .getValue();
        }
        if (syntax instanceof UnaryTree) {
            Object value =
                    constant(
                            new TreePath(path, ((UnaryTree) syntax).getExpression()),
                            trees,
                            expected,
                            depth + 1);
            if (syntax.getKind() == Tree.Kind.LOGICAL_COMPLEMENT && value instanceof Boolean)
                return Boolean.valueOf(!((Boolean) value).booleanValue());
            Number number = number(value);
            TypeMirror type = trees.getTypeMirror(path);
            if (type == null) throw new Unavailable();
            switch (syntax.getKind()) {
                case UNARY_PLUS:
                    return numeric(number, type.getKind());
                case UNARY_MINUS:
                    if (type.getKind() == TypeKind.DOUBLE)
                        return Double.valueOf(-number.doubleValue());
                    if (type.getKind() == TypeKind.FLOAT)
                        return Float.valueOf(-number.floatValue());
                    if (type.getKind() == TypeKind.LONG) return Long.valueOf(-number.longValue());
                    return Integer.valueOf(-number.intValue());
                case BITWISE_COMPLEMENT:
                    if (type.getKind() == TypeKind.LONG) return Long.valueOf(~number.longValue());
                    return Integer.valueOf(~number.intValue());
                default:
                    throw new Unavailable();
            }
        }
        if (syntax instanceof BinaryTree) {
            BinaryTree binary = (BinaryTree) syntax;
            Object left =
                    constant(
                            new TreePath(path, binary.getLeftOperand()),
                            trees,
                            expected,
                            depth + 1);
            if (left instanceof Boolean) {
                if (syntax.getKind() == Tree.Kind.CONDITIONAL_AND
                        && !((Boolean) left).booleanValue()) return Boolean.FALSE;
                if (syntax.getKind() == Tree.Kind.CONDITIONAL_OR && ((Boolean) left).booleanValue())
                    return Boolean.TRUE;
            }
            Object right =
                    constant(
                            new TreePath(path, binary.getRightOperand()),
                            trees,
                            expected,
                            depth + 1);
            if (syntax.getKind() == Tree.Kind.PLUS
                    && (left instanceof String || right instanceof String))
                return String.valueOf(left) + right;
            if (left instanceof Boolean && right instanceof Boolean) {
                boolean a = ((Boolean) left).booleanValue(), b = ((Boolean) right).booleanValue();
                switch (syntax.getKind()) {
                    case AND:
                    case CONDITIONAL_AND:
                        return Boolean.valueOf(a & b);
                    case OR:
                    case CONDITIONAL_OR:
                        return Boolean.valueOf(a | b);
                    case XOR:
                        return Boolean.valueOf(a ^ b);
                    case EQUAL_TO:
                        return Boolean.valueOf(a == b);
                    case NOT_EQUAL_TO:
                        return Boolean.valueOf(a != b);
                    default:
                        throw new Unavailable();
                }
            }
            if (left instanceof String && right instanceof String) {
                if (syntax.getKind() == Tree.Kind.EQUAL_TO)
                    return Boolean.valueOf(left.equals(right));
                if (syntax.getKind() == Tree.Kind.NOT_EQUAL_TO)
                    return Boolean.valueOf(!left.equals(right));
            }
            TypeMirror type = trees.getTypeMirror(path);
            if (type == null) throw new Unavailable();
            return binary(number(left), number(right), syntax.getKind(), type.getKind());
        }
        throw new Unavailable();
    }

    private static Number number(Object value) {
        if (value instanceof Character) return Integer.valueOf(((Character) value).charValue());
        if (value instanceof Number) return (Number) value;
        throw new Unavailable();
    }

    private static Object binary(Number left, Number right, Tree.Kind operation, TypeKind type) {
        long x = left.longValue(), y = right.longValue();
        if (type == TypeKind.BOOLEAN) {
            boolean floating =
                    left instanceof Float
                            || left instanceof Double
                            || right instanceof Float
                            || right instanceof Double;
            boolean doubles = left instanceof Double || right instanceof Double;
            double a = doubles ? left.doubleValue() : left.floatValue();
            double b = doubles ? right.doubleValue() : right.floatValue();
            switch (operation) {
                case LESS_THAN:
                    return Boolean.valueOf(floating ? a < b : x < y);
                case LESS_THAN_EQUAL:
                    return Boolean.valueOf(floating ? a <= b : x <= y);
                case GREATER_THAN:
                    return Boolean.valueOf(floating ? a > b : x > y);
                case GREATER_THAN_EQUAL:
                    return Boolean.valueOf(floating ? a >= b : x >= y);
                case EQUAL_TO:
                    return Boolean.valueOf(floating ? a == b : x == y);
                case NOT_EQUAL_TO:
                    return Boolean.valueOf(floating ? a != b : x != y);
                default:
                    throw new Unavailable();
            }
        }
        if (type == TypeKind.FLOAT) {
            float a = left.floatValue(), b = right.floatValue();
            switch (operation) {
                case PLUS:
                    return Float.valueOf(a + b);
                case MINUS:
                    return Float.valueOf(a - b);
                case MULTIPLY:
                    return Float.valueOf(a * b);
                case DIVIDE:
                    return Float.valueOf(a / b);
                case REMAINDER:
                    return Float.valueOf(a % b);
                default:
                    throw new Unavailable();
            }
        }
        if (type == TypeKind.DOUBLE) {
            double a = left.doubleValue(), b = right.doubleValue();
            switch (operation) {
                case PLUS:
                    return Double.valueOf(a + b);
                case MINUS:
                    return Double.valueOf(a - b);
                case MULTIPLY:
                    return Double.valueOf(a * b);
                case DIVIDE:
                    return Double.valueOf(a / b);
                case REMAINDER:
                    return Double.valueOf(a % b);
                default:
                    throw new Unavailable();
            }
        }
        long result;
        switch (operation) {
            case PLUS:
                result = x + y;
                break;
            case MINUS:
                result = x - y;
                break;
            case MULTIPLY:
                result = x * y;
                break;
            case DIVIDE:
                if (y == 0) throw new Unavailable();
                result = x / y;
                break;
            case REMAINDER:
                if (y == 0) throw new Unavailable();
                result = x % y;
                break;
            case AND:
                result = x & y;
                break;
            case OR:
                result = x | y;
                break;
            case XOR:
                result = x ^ y;
                break;
            case LEFT_SHIFT:
                result = type == TypeKind.LONG ? x << y : (int) x << y;
                break;
            case RIGHT_SHIFT:
                result = type == TypeKind.LONG ? x >> y : (int) x >> y;
                break;
            case UNSIGNED_RIGHT_SHIFT:
                result = type == TypeKind.LONG ? x >>> y : (int) x >>> y;
                break;
            default:
                throw new Unavailable();
        }
        return type == TypeKind.LONG
                ? (Number) Long.valueOf(result)
                : Integer.valueOf((int) result);
    }

    private static Object numeric(Number value, TypeKind type) {
        switch (type) {
            case BYTE:
                return Byte.valueOf(value.byteValue());
            case SHORT:
                return Short.valueOf(value.shortValue());
            case CHAR:
                return Character.valueOf((char) value.intValue());
            case INT:
                return Integer.valueOf(value.intValue());
            case LONG:
                return Long.valueOf(value.longValue());
            case FLOAT:
                return Float.valueOf(value.floatValue());
            case DOUBLE:
                return Double.valueOf(value.doubleValue());
            default:
                throw new Unavailable();
        }
    }

    private static AnnotationValue converted(Object value, TypeMirror expected) {
        if (expected.getKind() == TypeKind.ARRAY && !(value instanceof List<?>))
            value =
                    Collections.singletonList(
                            converted(value, ((ArrayType) expected).getComponentType()));
        else if (value instanceof Number || value instanceof Character)
            value = numeric(number(value), expected.getKind());
        final Object result = value;
        return new AnnotationValue() {
            @Override
            public Object getValue() {
                return result;
            }

            @Override
            public String toString() {
                return String.valueOf(result);
            }

            @Override
            public <R, P> R accept(AnnotationValueVisitor<R, P> visitor, P parameter) {
                if (result instanceof Boolean)
                    return visitor.visitBoolean(((Boolean) result).booleanValue(), parameter);
                if (result instanceof Byte)
                    return visitor.visitByte(((Byte) result).byteValue(), parameter);
                if (result instanceof Short)
                    return visitor.visitShort(((Short) result).shortValue(), parameter);
                if (result instanceof Character)
                    return visitor.visitChar(((Character) result).charValue(), parameter);
                if (result instanceof Integer)
                    return visitor.visitInt(((Integer) result).intValue(), parameter);
                if (result instanceof Long)
                    return visitor.visitLong(((Long) result).longValue(), parameter);
                if (result instanceof Float)
                    return visitor.visitFloat(((Float) result).floatValue(), parameter);
                if (result instanceof Double)
                    return visitor.visitDouble(((Double) result).doubleValue(), parameter);
                if (result instanceof String)
                    return visitor.visitString((String) result, parameter);
                if (result instanceof TypeMirror)
                    return visitor.visitType((TypeMirror) result, parameter);
                if (result instanceof VariableElement)
                    return visitor.visitEnumConstant((VariableElement) result, parameter);
                if (result instanceof AnnotationMirror)
                    return visitor.visitAnnotation((AnnotationMirror) result, parameter);
                if (result instanceof List<?>) {
                    @SuppressWarnings("unchecked")
                    List<? extends AnnotationValue> values =
                            (List<? extends AnnotationValue>) result;
                    return visitor.visitArray(values, parameter);
                }
                return visitor.visitUnknown(this, parameter);
            }
        };
    }

    private static void depth(int depth) {
        if (depth > 128) throw new WorkerTypes.BudgetExceeded();
    }

    static final class Unavailable extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        Unavailable() {
            super("source annotation constant is unavailable");
        }
    }
}
