package sample;

import lombok.experimental.SuperBuilder;

@SuperBuilder(toBuilder = true)
public class ChildModel extends BaseModel {
    private String child;
}
