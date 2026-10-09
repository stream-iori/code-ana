import com.anatomist.core.SpringBeanParser;
import com.anatomist.json.Json;
import java.nio.file.*;
import java.util.*;

/** Uses only the frozen parser; no product projection or database is on the classpath. */
public final class XmlConfigurationWitness {
    static Map<String,Object> position(int line,int column,int last,int end) {
        var value=new LinkedHashMap<String,Object>();
        value.put("line",line);value.put("column",column);value.put("end_line",last);value.put("end_column",end);
        return value;
    }
    static int visit(SpringBeanParser.XmlConfigNode node,List<Map<String,Object>> rows,int depth) throws Exception {
        if(depth>256||rows.size()>=30000)throw new IllegalArgumentException("REFERENCE_XML_MODEL_BUDGET");
        int index=rows.size();var children=new ArrayList<Integer>();var metadata=new LinkedHashMap<String,Object>();
        metadata.put("xmlKind",node.kind);metadata.put("ordinal",node.ordinal);
        for(String key:List.of("name","key","index","bean","value","type")) {
            Object value=node.getClass().getField(key).get(node);
            if(value!=null)metadata.put(key,value);
        }
        boolean synthetic=node.endLine<=0||node.endColumn<=0;
        rows.add(Map.of("metadata",metadata,"children",children,"position",position(node.line,node.column,
            synthetic?node.line:node.endLine,synthetic?node.column:node.endColumn)));
        for(var child:node.children)children.add(visit(child,rows,depth+1));
        return index;
    }
    public static void main(String[] args) throws Exception {
        Object inputs=Json.parseTree(Files.readString(Path.of(args[0])));
        var result=new ArrayList<Object>();
        for(Object item:(List<?>)inputs) {
            var input=(Map<?,?>)item;var models=new ArrayList<Object>();
            for(var bean:new SpringBeanParser().parse(Path.of((String)input.get("path")))) {
                var construction=new LinkedHashMap<String,Object>();
                for(var field:bean.getClass().getRecordComponents()) {
                    if(Set.of("line","column","endLine","endColumn","children").contains(field.getName()))continue;
                    construction.put(field.getName().equals("abstractBean")?"abstract":field.getName(),field.getAccessor().invoke(bean));
                }
                var rows=new ArrayList<Map<String,Object>>();var roots=new ArrayList<Integer>();
                for(var child:bean.children())roots.add(visit(child,rows,0));
                models.add(Map.of("version",1,"construction",construction,"roots",roots,"nodes",rows));
            }
            result.add(Map.of("file",input.get("file"),"models",models));
        }
        System.out.println(Json.writeCompact(result));
    }
}
