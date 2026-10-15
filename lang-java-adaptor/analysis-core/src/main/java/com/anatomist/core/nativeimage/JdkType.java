package com.anatomist.core.nativeimage;

import java.util.List;

public final class JdkType {

    public static final int FLAG_PUBLIC = 1 << 0;
    public static final int FLAG_STATIC = 1 << 1;
    public static final int FLAG_FINAL = 1 << 2;
    public static final int FLAG_INTERFACE = 1 << 3;
    public static final int FLAG_ABSTRACT = 1 << 4;
    public static final int FLAG_ENUM = 1 << 5;
    public static final int FLAG_ANNOTATION = 1 << 6;
    public static final int FLAG_CLASS = 1 << 7;
    public static final int FLAG_RECORD = 1 << 8;
    public static final int FLAG_VARARGS = 1 << 9;

    public final String fqn;
    public final String superFqn;
    public final List<String> interfaceFqns;
    public final int flags;
    public final String signature;
    public final List<FieldEntry> fields;
    public final List<MethodEntry> methods;

    public JdkType(
            String fqn,
            String superFqn,
            List<String> interfaceFqns,
            int flags,
            String signature,
            List<FieldEntry> fields,
            List<MethodEntry> methods) {
        this.fqn = fqn;
        this.superFqn = superFqn;
        this.interfaceFqns = interfaceFqns;
        this.flags = flags;
        this.signature = signature;
        this.fields = fields;
        this.methods = methods;
    }

    public static final class FieldEntry {
        public final String name;
        public final String descriptor;
        public final int flags;
        public final String signature;

        public FieldEntry(String name, String descriptor, int flags) {
            this(name, descriptor, flags, null);
        }

        public FieldEntry(String name, String descriptor, int flags, String signature) {
            this.name = name;
            this.descriptor = descriptor;
            this.flags = flags;
            this.signature = signature;
        }
    }

    public static final class MethodEntry {
        public final String name;
        public final String descriptor;
        public final int flags;
        public final String signature;

        public MethodEntry(String name, String descriptor, int flags) {
            this(name, descriptor, flags, null);
        }

        public MethodEntry(String name, String descriptor, int flags, String signature) {
            this.name = name;
            this.descriptor = descriptor;
            this.flags = flags;
            this.signature = signature;
        }
    }
}
