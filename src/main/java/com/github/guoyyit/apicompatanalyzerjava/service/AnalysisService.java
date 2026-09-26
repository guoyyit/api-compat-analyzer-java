package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFileReader;

/**
 * Entry point of the analyzer.
 *
 * <p>Concurrency contract: analyses (reads) may run in parallel, while an update
 * (baseline publish or current snapshot replacement) is exclusive. Every returned
 * report is a single consistent version; new and old data are never mixed.
 */
public interface AnalysisService {

    /** Full analysis of two explicit source snapshots. */
    AnalysisReport analyzeSnapshots(SourceFileReader baselineReader,
                                    SourceFileReader currentReader,
                                    AnalysisOptions options);

    /** Full analysis of a stored baseline against a fresh current snapshot. */
    AnalysisReport analyzeAgainstBaseline(String baselineId,
                                          SourceFileReader currentReader,
                                          AnalysisOptions options);

    /** Parse and publish a snapshot as a reusable baseline (exemptions included). */
    String publishBaseline(String baselineId, SourceFileReader reader, AnalysisOptions options);

    /** Incremental update of the current snapshot and re-analysis against the baseline. */
    AnalysisReport updateAndAnalyze(String baselineId,
                                    SourceFileReader newCurrentReader,
                                    AnalysisOptions options);

    /** Read the latest published report; {@code null} before the first analysis. */
    AnalysisReport latestReport();

    /** Expose the currently published current snapshot, mainly for tests/inspection. */
    SnapshotModel currentSnapshot();
}
