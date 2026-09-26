package com.github.guoyyit.apicompatanalyzerjava.service;

/** Raised when analysis fails, times out or exceeds resource bounds. */
public class AnalysisException extends RuntimeException {
    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
