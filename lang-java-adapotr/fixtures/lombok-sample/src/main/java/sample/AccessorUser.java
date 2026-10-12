package sample;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(fluent = true)
public class AccessorUser {
    private String name;
}
