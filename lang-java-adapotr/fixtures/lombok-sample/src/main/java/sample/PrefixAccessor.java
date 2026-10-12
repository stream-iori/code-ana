package sample;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(prefix = "m")
public class PrefixAccessor {
    private String mName;
}
