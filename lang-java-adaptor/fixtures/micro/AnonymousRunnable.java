package micro;

/** Anonymous Runnable — emits ANONYMOUS_CLASS node whose ID encodes line. */
public class AnonymousRunnable {
    public Runnable make() {
        return new Runnable() {
            @Override public void run() {
                System.out.println("hi");
            }
        };
    }
}
