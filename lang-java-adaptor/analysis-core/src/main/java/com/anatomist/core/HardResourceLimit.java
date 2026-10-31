package com.anatomist.core;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Resource failure must cross semantic recovery boundaries without becoming partial facts. */
public final class HardResourceLimit extends FatalAnalysisFailure {
    public HardResourceLimit(String message) { super(message); }
    public HardResourceLimit(String message, Throwable cause) { super(message, cause); }

    public static void rethrow(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; failure != null && i < 64 && seen.add(failure); i++, failure = failure.getCause()) {
            if (failure instanceof FatalAnalysisFailure fatal) throw fatal;
            if (failure instanceof java.util.MissingResourceException)
                throw new FatalAnalysisFailure("XML_PARSER_RESOURCE_BUNDLE_UNAVAILABLE", failure);
            if (failure instanceof com.fasterxml.jackson.core.exc.StreamConstraintsException)
                throw new HardResourceLimit("JSON resource limit", failure);
            // JDK JAXP processing-limit codes; never classify arbitrary SAX syntax failures as limits.
            String message = failure.getMessage();
            if (message != null
                    && message.matches("(?s).*JAXP0001000[1-9]:.*"))
                throw new HardResourceLimit("XML platform resource limit", failure);
        }
        if (failure != null && seen.size() == 64 && !seen.contains(failure))
            throw new FatalAnalysisFailure("ANALYSIS_FAILURE_CAUSE_DEPTH_LIMIT", failure);
    }
}
