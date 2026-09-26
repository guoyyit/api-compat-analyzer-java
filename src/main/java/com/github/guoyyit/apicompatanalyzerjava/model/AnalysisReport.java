package com.github.guoyyit.apicompatanalyzerjava.model;

import java.util.List;

/**
 * Immutable result of one analysis. A published report is always complete; a run that
 * times out or runs out of resources fails without publishing a report.
 *
 * @param reportId          deterministic content hash of this report (reproducibility)
 * @param baselineSnapshotId content hash of the baseline snapshot
 * @param currentSnapshotId  content hash of the current snapshot
 * @param incremental       whether this report came from an incremental update
 * @param analyzedFiles     files actually parsed (re-parsed when incremental)
 * @param reusedFiles       unchanged files reused from the current cache
 * @param changes           every detected change, verdict and suppression included
 * @param impacts           breaking changes with concrete affected call sites
 * @param suppressions      exemptions discovered while parsing the current snapshot
 * @param durationMillis    elapsed wall clock time
 * @param generatedAt       epoch millis when the report was published
 */
public record AnalysisReport(
        String reportId,
        String baselineSnapshotId,
        String currentSnapshotId,
        boolean incremental,
        List<String> analyzedFiles,
        List<String> reusedFiles,
        List<ApiChange> changes,
        List<ImpactFinding> impacts,
        List<Suppression> suppressions,
        long durationMillis,
        long generatedAt
) {
}
