package dev.codeana.common;

import java.util.List;

/** A row view with a stable immutable snapshot; shared by the Java 8 model boundary. */
public interface ModelRows {
    List<Object> snapshot();
}
