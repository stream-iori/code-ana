package micro;

import java.util.ArrayList;
import java.util.List;

/** Generic type parameter → REFERENCES edge with context=generic_arg. */
public class GenericRepository<T> {
    private final List<T> store = new ArrayList<T>();

    public void add(T item) { store.add(item); }
    public T get(int i)     { return store.get(i); }
}
