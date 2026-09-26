package com.github.guoyyit.apicompatanalyzerjava.service;

/** Raised when a request cannot be served (bad input, missing baseline, resource limit). */
public class AnalysisRejectedException extends RuntimeException {

    public AnalysisRejectedException(String message) {
        super(message);
    }

    public AnalysisRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
