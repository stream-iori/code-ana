package sample;

import lombok.Builder;

@Builder(builderMethodName = "newBuilder", buildMethodName = "create", setterPrefix = "with")
public class CustomBuilder {
    private String label;
}
