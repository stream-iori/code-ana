import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Reflection is needed only by the test serializer, never by the product C ABI. */
public final class ReflectionInventory {
    public static void main(String[] args) throws Exception {
        List<Object> records=new ArrayList<>();
        try(var jar=new JarFile(args[0])) {
            for(var entry:Collections.list(jar.entries())) {
                String name=entry.getName();
                if(!name.endsWith(".class") || !(name.startsWith("com/anatomist/core/") || name.startsWith("com/anatomist/model/"))) continue;
                String type=name.substring(0,name.length()-6).replace('/','.');
                records.add(Map.of("name",type,"allDeclaredMethods",true,"allPublicFields",true));
            }
        }
        Files.writeString(Path.of(args[1]),dev.codeana.common.Json.write(records));
    }
}
