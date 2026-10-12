package sample;

import lombok.Data;
import lombok.NonNull;

@Data
public class User {
    private final String id;
    @NonNull private String name;
    private boolean active;
}
