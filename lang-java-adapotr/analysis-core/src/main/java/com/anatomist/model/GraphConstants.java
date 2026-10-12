package com.anatomist.model;

import java.util.Set;

public final class GraphConstants {
    private GraphConstants() {}

    public static final Set<String> TYPE_KINDS =
            Set.of(
                    Kind.CLASS,
                    Kind.INTERFACE,
                    Kind.ENUM,
                    Kind.ANNOTATION,
                    Kind.RECORD,
                    Kind.ANONYMOUS_CLASS);
    public static final Set<String> DECLARED_TYPE_KINDS =
            Set.of(Kind.CLASS, Kind.INTERFACE, Kind.ENUM, Kind.ANNOTATION, Kind.RECORD);
    public static final Set<String> INDEX_SUMMARY_TYPE_KINDS =
            Set.of(
                    Kind.CLASS,
                    Kind.INTERFACE,
                    Kind.ENUM,
                    Kind.ANNOTATION,
                    Kind.RECORD,
                    Kind.ANONYMOUS_CLASS);
    public static final Set<String> METHOD_KINDS = Set.of(Kind.METHOD, Kind.CONSTRUCTOR);
    public static final Set<String> MEMBER_KINDS =
            Set.of(Kind.METHOD, Kind.CONSTRUCTOR, Kind.FIELD);
    public static final Set<String> FIELD_ACCESS_RELATIONS =
            Set.of(Relation.READS, Relation.WRITES);
    public static final Set<String> FRAMEWORK_RELATIONS =
            Set.of(
                    Relation.DEFINED_BY,
                    Relation.BINDS_TO,
                    Relation.INJECTS,
                    Relation.HANDLES,
                    Relation.WIRES);
    public static final Set<String> DEPENDENCY_RELATIONS =
            Set.of(
                    Relation.CALLS,
                    Relation.REFERENCES,
                    Relation.WIRES,
                    Relation.INJECTS,
                    Relation.HANDLES,
                    Relation.DEFINED_BY);
    public static final Set<String> HIERARCHY_RELATIONS =
            Set.of(Relation.IMPLEMENTS, Relation.INHERITS, Relation.PERMITS);
    public static final Set<String> PACKAGE_DEPENDENCY_RELATIONS =
            Set.of(
                    Relation.CALLS,
                    Relation.REFERENCES,
                    Relation.INJECTS,
                    Relation.IMPLEMENTS,
                    Relation.INHERITS,
                    Relation.READS,
                    Relation.WRITES,
                    Relation.DEFINED_BY,
                    Relation.XML_REFERS_TO,
                    Relation.PARENT_BEAN,
                    Relation.BINDS_TO,
                    Relation.WIRES,
                    Relation.HANDLES);
    public static final Set<String> TYPE_EDGE_RELATIONS =
            Set.of(
                    Relation.CALLS,
                    Relation.REFERENCES,
                    Relation.WIRES,
                    Relation.IMPLEMENTS,
                    Relation.INHERITS);
    public static final Set<String> OVERVIEW_TYPE_EDGE_RELATIONS =
            Set.of(
                    Relation.CALLS,
                    Relation.REFERENCES,
                    Relation.WIRES,
                    Relation.INJECTS,
                    Relation.IMPLEMENTS,
                    Relation.INHERITS);

    public static final class Kind {
        public static final String ANNOTATION = "ANNOTATION";
        public static final String ANONYMOUS_CLASS = "ANONYMOUS_CLASS";
        public static final String ARTIFACT = "ARTIFACT";
        public static final String BEAN = "BEAN";
        public static final String CLASS = "CLASS";
        public static final String CONSTRUCTOR = "CONSTRUCTOR";
        public static final String ENUM = "ENUM";

        /** Query-only aggregation of external edges; never persisted in nodes. */
        public static final String EXTERNAL_CLASS = "EXTERNAL_CLASS";

        public static final String FIELD = "FIELD";
        public static final String INTERFACE = "INTERFACE";
        public static final String LAMBDA = "LAMBDA";
        public static final String METHOD = "METHOD";
        public static final String METHOD_REF = "METHOD_REF";
        public static final String RECORD = "RECORD";
        public static final String ROUTE = "ROUTE";
        public static final String XML_CONSTRUCTOR_ARG = "XML_CONSTRUCTOR_ARG";
        public static final String XML_CALLABLE_REF = "XML_CALLABLE_REF";
        public static final String XML_ENTRY = "XML_ENTRY";
        public static final String XML_IDREF = "XML_IDREF";
        public static final String XML_LIST = "XML_LIST";
        public static final String XML_MAP = "XML_MAP";
        public static final String XML_NULL = "XML_NULL";
        public static final String XML_PROPERTY = "XML_PROPERTY";
        public static final String XML_REF = "XML_REF";
        public static final String XML_VALUE = "XML_VALUE";

        private Kind() {}
    }

    public static final class Relation {
        public static final String CALLS = "CALLS";
        public static final String BINDS_TO = "BINDS_TO";
        public static final String CONFIGURES = "CONFIGURES";
        public static final String CONTAINS = "CONTAINS";
        public static final String DEFINED_BY = "DEFINED_BY";
        public static final String HANDLES = "HANDLES";
        public static final String IMPLEMENTS = "IMPLEMENTS";
        public static final String INHERITS = "INHERITS";
        public static final String INJECTS = "INJECTS";
        public static final String OVERRIDES = "OVERRIDES";
        public static final String PARENT_BEAN = "PARENT_BEAN";
        public static final String FACTORY_BEAN = "FACTORY_BEAN";

        /** Canonical child-to-sealed-parent relation declared by a permits clause. */
        public static final String PERMITS = "PERMITS";

        public static final String READS = "READS";
        public static final String REFERENCES = "REFERENCES";
        public static final String WIRES = "WIRES";
        public static final String WRITES = "WRITES";
        public static final String XML_CONTAINS = "XML_CONTAINS";
        public static final String XML_REFERS_TO = "XML_REFERS_TO";

        private Relation() {}
    }

    public static final class Confidence {
        public static final String AMBIGUOUS = "AMBIGUOUS";
        public static final String CONFIGURED = "CONFIGURED";
        public static final String EXTRACTED = "EXTRACTED";
        public static final String INFERRED = "INFERRED";

        private Confidence() {}
    }

    /** How an external target was resolved. Persisted only for external edges. */
    public static final class Resolution {
        public static final String CLASSPATH = "classpath";
        public static final String AST_FALLBACK = "ast_fallback";
        public static final String TYPE_FALLBACK = "type_fallback";
        public static final String STATIC_NAME_FALLBACK = "static_name_fallback";
        public static final String SOURCE_FALLBACK = "source_fallback";
        public static final String REFLECTION = "reflection";
        public static final String XML = "xml";

        private Resolution() {}
    }

    public static final class CallKind {
        public static final String CONSTRUCTOR = "CONSTRUCTOR";
        public static final String INSTANCE = "INSTANCE";
        public static final String INTERFACE = "INTERFACE";
        public static final String REFLECTION = "REFLECTION";
        public static final String STATIC = "STATIC";
        public static final String SUPER = "SUPER";

        private CallKind() {}
    }

    public static final class MetadataVia {
        public static final String INJECTION = "injection";
        public static final String INJECTED_CALL = "injected-call";
        public static final String REFLECTION = "reflection";

        private MetadataVia() {}
    }

    public static final class Scope {
        public static final String MAIN = "MAIN";

        private Scope() {}
    }
}
