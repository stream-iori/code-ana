package micro;

/** Interface with default method (JDK 8) — default method belongs to INTERFACE node. */
public interface InterfaceDefaultMethod {
    String name();

    default String greeting() {
        return "hello, " + name();
    }
}
