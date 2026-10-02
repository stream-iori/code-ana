package dev.codeana.analysis.extension;

import java.util.*;

/** Selects from frozen captured inputs. Discovery never reads an uncaptured workspace file. */
public interface ProjectResourceProvider extends ExtensionPoint {
    Set<String> kinds();
    List<ProjectResource> discover(ExtensionContext context) throws Exception;
}
