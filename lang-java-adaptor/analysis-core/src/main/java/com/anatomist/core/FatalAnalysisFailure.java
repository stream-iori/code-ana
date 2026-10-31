package com.anatomist.core;

/** An analysis failure which semantic recovery must never turn into publishable facts. */
public class FatalAnalysisFailure extends IllegalArgumentException {
    public FatalAnalysisFailure(String message) { super(message); }
    public FatalAnalysisFailure(String message, Throwable cause) { super(message, cause); }
}
