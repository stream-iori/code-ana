package sample;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

@Data
public class DisabledAccessor {
    @Getter(AccessLevel.NONE)
    private String secret;
}
