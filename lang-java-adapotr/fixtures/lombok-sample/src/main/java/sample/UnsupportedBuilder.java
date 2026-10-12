package sample;

import lombok.Builder;

@Builder(builderClassName = "*Builder")
public class UnsupportedBuilder {
    private String value;
}
