package com.github.guoyyit.apicompatanalyzerjava.model;

/**
 * User supplied options for one analysis run.
 *
 * @param timeoutMillis wall clock budget for the whole analysis; {@code <= 0} uses default
 * @param maxFiles      hard limit on source files accepted into a snapshot
 * @param maxFileBytes  per-file byte size limit; larger files are rejected
 * @param maxMemoryMb   soft heap budget; an exceeded budget fails the run, never yields partial output
 */
public record AnalysisOptions(
        long timeoutMillis,
        int maxFiles,
        long maxFileBytes,
        long maxMemoryMb
) {

    public static AnalysisOptions defaults() {
        return new AnalysisOptions(0, 0, 0L, 0L);
    }
}
