package sample;

public class LombokUsageService {
    public String accessor(AccessorUser user) {
        return user.name();
    }

    public UnsupportedBuilder build() {
        return UnsupportedBuilder.builder().value("observed").build();
    }

    public CustomBuilder customBuild() {
        return CustomBuilder.newBuilder().withLabel("observed").create();
    }
}
