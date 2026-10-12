package micro;

/** Enum with methods → ENUM node + ENUM_CONSTANT members. */
public enum EnumWithMethods {
    PENDING(false),
    PAID(true),
    CANCELLED(true);

    private final boolean terminal;

    EnumWithMethods(boolean terminal) { this.terminal = terminal; }

    public boolean isTerminal() { return terminal; }
}
