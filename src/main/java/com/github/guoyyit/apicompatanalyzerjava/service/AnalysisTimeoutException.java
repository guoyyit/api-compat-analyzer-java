package com.github.guoyyit.apicompatanalyzerjava.service;

/** Raised when an analysis run does not finish within its time boundary. */
public class AnalysisTimeoutException extends RuntimeException {

    public AnalysisTimeoutException(String message) {
        super(message);
    }
}
