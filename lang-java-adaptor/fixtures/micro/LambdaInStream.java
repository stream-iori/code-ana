package micro;

import java.util.List;
import java.util.stream.Collectors;

/** Lambda inside a stream — emits LAMBDA node + CALLS edges crossing into it. */
public class LambdaInStream {
    public List<String> upper(List<String> in) {
        return in.stream()
                .map(s -> s.toUpperCase())
                .collect(Collectors.toList());
    }
}
