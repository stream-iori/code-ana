package p;
import java.util.function.Function;
interface Greeting { String greet(String name); }
class Service implements Greeting {
    public String greet(String name) { return name.trim(); }
    public <T> T identity(T value) { return value; }
}
public class App {
    // Supplementary character before calls: 🧪
    public static void main(String[] args) {
        Service service = new Service();
        service.greet("one");
        service.greet("two");
        Function<String,String> f = service::greet;
        service.identity(f.apply("three"));
    }
}
