package sample;

import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
public class BaseModel {
    private String base;
}
