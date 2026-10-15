package micro;

/** Static vs instance call_kind on the same outer surface. */
public class StaticVsInstance {
    public static int square(int x) { return x * x; }
    public int times(int x)         { return x * x; }

    public int demo() {
        return square(2) + new StaticVsInstance().times(3);
    }
}
