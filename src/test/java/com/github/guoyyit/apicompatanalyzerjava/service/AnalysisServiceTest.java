package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeNature;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatVerdict;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import com.github.guoyyit.apicompatanalyzerjava.support.TestServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisServiceTest {

    private Sources baseline() {
        return Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public String greet(String n) { return n; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.greet(\"a\"); } }");
    }

    private Sources currentBreaking() {
        return Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public String greet() { return \"\"; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.greet(\"a\"); } }");
    }

    @Test
    void fullAnalysisReportsChangeImpactAndSuppressionList(@TempDir Path dir) {
        var service = TestServices.create(dir);
        AnalysisReport report = service.analyzeSnapshots(
                baseline().reader(), currentBreaking().reader(), AnalysisOptions.defaults());

        ApiChange change = report.changes().stream()
                .filter(c -> c.nature() == ChangeNature.REMOVED
                        && c.elementId().contains("greet"))
                .findFirst().orElseThrow();
        assertEquals(CompatVerdict.BREAKING, change.verdict());
        assertTrue(report.impacts().stream()
                .flatMap(f -> f.callSites().stream())
                .anyMatch(site -> site.path().equals("com/acme/Caller.java")
                        && site.symbol().equals("greet")));
        assertNotNull(report.reportId());
        assertFalse(report.incremental());
    }

    @Test
    void baselineCanBePublishedAndReusedForComparison(@TempDir Path dir) {
        var service = TestServices.create(dir);
        String id = service.publishBaseline("b1", baseline().reader(), AnalysisOptions.defaults());
        assertNotNull(id);

        AnalysisReport report = service.analyzeAgainstBaseline(
                "b1", currentBreaking().reader(), AnalysisOptions.defaults());
        assertTrue(report.impacts().stream().anyMatch(f ->
                f.elementId().contains("com.acme.Service#greet")));
    }

    @Test
    void exemptionInCurrentSnapshotIsRecordedAsSuppression(@TempDir Path dir) {
        var service = TestServices.create(dir);
        Sources exemptCurrent = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { @ApiCompatExempt(reason=\"accepted removal\") "
                                + "private String greet(String n) { return n; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.greet(\"a\"); } }");

        service.publishBaseline("b1", baseline().reader(), AnalysisOptions.defaults());
        AnalysisReport report = service.analyzeAgainstBaseline(
                "b1", exemptCurrent.reader(), AnalysisOptions.defaults());

        assertTrue(report.suppressions().stream()
                .anyMatch(s -> s.elementId().contains("greet")
                        && s.reason().equals("accepted removal")));
        assertTrue(report.changes().stream()
                .filter(c -> c.elementId().contains("greet"))
                .allMatch(ApiChange::suppressed));
    }

    @Test
    void incrementalReportEqualsFullReportAndReusesUnchangedFiles(@TempDir Path dir) {
        var service = TestServices.create(dir);
        service.publishBaseline("b1", baseline().reader(), AnalysisOptions.defaults());

        // First incremental call establishes the current snapshot (full first build).
        AnalysisReport first = service.updateAndAnalyze(
                "b1", currentBreaking().reader(), AnalysisOptions.defaults());
        assertFalse(first.incremental());

        // Second update with only Caller body changed.
        Sources changedCaller = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public String greet() { return \"\"; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { "
                                + "return s.greet(\"different\"); } }");
        AnalysisReport second = service.updateAndAnalyze(
                "b1", changedCaller.reader(), AnalysisOptions.defaults());
        assertTrue(second.incremental());
        assertTrue(second.reusedFiles().contains("com/acme/Service.java"));
        assertTrue(second.analyzedFiles().contains("com/acme/Caller.java"));

        // Incremental result must equal a fresh full analysis.
        AnalysisReport full = service.analyzeAgainstBaseline(
                "b1", changedCaller.reader(), AnalysisOptions.defaults());
        assertEquals(full.changes(), second.changes());
        assertEquals(full.impacts(), second.impacts());
        assertEquals(full.reportId(), second.reportId());
    }

    @Test
    void publishingBaselineAfterFullAnalysisForcesFullIncrementalBuild(@TempDir Path dir) {
        var service = TestServices.create(dir);
        // A full snapshot analysis publishes a current snapshot...
        service.analyzeSnapshots(baseline().reader(), baseline().reader(),
                AnalysisOptions.defaults());
        // ...then publishing a baseline with the same content must invalidate that state.
        service.publishBaseline("b1", baseline().reader(), AnalysisOptions.defaults());

        AnalysisReport first = service.updateAndAnalyze(
                "b1", currentBreaking().reader(), AnalysisOptions.defaults());
        assertFalse(first.incremental(),
                "first update against a freshly published baseline must be a full build");
    }

    @Test
    void reproducibleReportIdIsStableAcrossRuns(@TempDir Path dir) {
        var service1 = TestServices.create(dir.resolve("a"));
        var service2 = TestServices.create(dir.resolve("b"));
        AnalysisReport r1 = service1.analyzeSnapshots(
                baseline().reader(), currentBreaking().reader(), AnalysisOptions.defaults());
        AnalysisReport r2 = service2.analyzeSnapshots(
                baseline().reader(), currentBreaking().reader(), AnalysisOptions.defaults());
        assertEquals(r1.reportId(), r2.reportId());
        assertEquals(r1.currentSnapshotId(), r2.currentSnapshotId());
    }

    @Test
    void rejectsUnknownBaseline(@TempDir Path dir) {
        var service = TestServices.create(dir);
        assertThrows(AnalysisRejectedException.class,
                () -> service.analyzeAgainstBaseline("missing",
                        currentBreaking().reader(), AnalysisOptions.defaults()));
    }
}
