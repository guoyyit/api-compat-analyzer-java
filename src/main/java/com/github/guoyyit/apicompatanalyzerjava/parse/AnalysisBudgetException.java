package com.github.guoyyit.apicompatanalyzerjava.parse;

/** Raised when an analysis run exceeds its time, file or memory boundary. */
public class AnalysisBudgetException extends RuntimeException {

    public AnalysisBudgetException(String message) {
        super(message);
    }
}
