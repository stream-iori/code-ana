package micro;

import java.util.List;

/** Method overloading — distinct Node IDs via erased signature. */
public class OverloadedMethods {
    public String describe(int n)               { return "i" + n; }
    public String describe(String s)            { return "s" + s; }
    public String describe(List<String> items)  { return "l" + items.size(); }
}
