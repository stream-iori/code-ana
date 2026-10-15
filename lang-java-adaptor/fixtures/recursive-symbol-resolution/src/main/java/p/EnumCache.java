package p;

import java.util.Optional;

class EnumCache {
    static <E extends Enum<E>> String name(E value) {
        return Optional.ofNullable(value).map(Enum::name).orElse("");
    }
}
