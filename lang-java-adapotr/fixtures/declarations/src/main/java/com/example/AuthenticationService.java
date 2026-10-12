package com.example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.List;

@Deprecated
public class AuthenticationService {
    public AuthenticationService() {}

    @Deprecated
    public AuthResult authenticate() {
        return null;
    }

    protected synchronized <T> T convert(
            @TypeUse List<T> values,
            String... labels) {
        return values.getFirst();
    }

    public void overload(String value) {}
    public void overload(int value) {}

    public record AuthResult(boolean valid) {
        public AuthResult {
        }

        public String message() {
            return "";
        }
    }

    private void internalOnly() {}

    @Target(ElementType.TYPE_USE)
    @interface TypeUse {}
}
