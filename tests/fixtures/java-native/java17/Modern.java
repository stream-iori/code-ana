package fixture.modern;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Java17 类型文档 */
public final class Modern {
    sealed interface Shape permits Point {}
    record Point(int x) implements Shape {
        Point { if (x < 0) throw new IllegalArgumentException(); }
        Point() { this(0); }
        Point(String value) { this(Integer.parseInt(value)); }
    }
    record Named(String name) {
        Named(String name) { this.name = name; }
    }
    record Box(String value) {}
    static final class Callbacks {
        final Function<Named, String> name;
        Callbacks() { name = Named::name; }
        String result() { return name.apply(new Named("callback")); }
    }
    public String genericCallbacks() {
        java.util.function.IntFunction<Point> factory = Point::new;
        return List.of(factory.apply(1)).stream().map(Point::x)
                .map(Object::toString).collect(Collectors.joining(","));
    }
    public String constructorCallbacks() { return new Callbacks().result(); }

    /** Java17 导航文档 */
    public String run(Object value) {
        // Java17 方法内注释😀
        if (!(value instanceof String text)) return "";
        if (text.isBlank()) return "";
        var items = List.copyOf(List.of(text));
        Function<Point, Integer> accessor = Point::x;
        Function<String, Box> constructor = Box::new;
        var names = items.stream().map(String::trim).toList();
        var joined = names.stream().collect(Collectors.joining(","));
        var point = new Point();
        var box = constructor.apply(joined);
        var label = switch (accessor.apply(point)) { case 0 -> "zero"; default -> "other"; };
        var banner = """
                modern
                """;
        return "%s:%s:%s".formatted(label, box.value(), banner);
    }

    public int ternary(Object value) { return value instanceof String s ? s.length() : 0; }
    public boolean conjunction(Object value) { return value instanceof String s && s.isBlank(); }
    public boolean disjunction(Object value) { return !(value instanceof String s) || s.isBlank(); }
    public String shadow(Object value) {
        if (value instanceof String s) return s.trim();
        String s = "local";
        return s;
    }
    public Runnable lambda() {
        return () -> {
            // Java17 lambda隔离
            System.out.println("lambda");
        };
    }
}
