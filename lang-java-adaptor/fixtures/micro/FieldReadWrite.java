package micro;

/** Field read + write — READS / WRITES edges. */
public class FieldReadWrite {
    private int counter = 0;

    public void increment() {
        counter = counter + 1;
    }

    public int value() {
        return counter;
    }
}
