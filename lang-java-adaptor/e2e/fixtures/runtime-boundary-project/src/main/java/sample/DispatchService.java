package sample;

public class DispatchService {
    public boolean dispatch(boolean approved) {
        if (!approved) {
            return false;
        }
        notifyCustomer();
        return true;
    }

    private void notifyCustomer() {
        // Runtime side effect intentionally omitted from this static fixture.
    }
}
