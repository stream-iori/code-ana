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

    public String prefixed(PrefixAccessor value) {
        return value.getName();
    }

    public String disabled(DisabledAccessor value) {
        return value.getSecret();
    }

    public ChildModel inheritedBuild() {
        return ChildModel.builder().base("base").child("child").build();
    }

    public ChildModel copyBuild(ChildModel value) {
        return value.toBuilder().child("next").build();
    }
}
