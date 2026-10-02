package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.service.BaselineStore;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 1: reports are deterministic across repeated runs and restarts. */
class DeterministicReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    private static Map<String, String> currentTree() {
        return Map.of("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER);
    }

    @Test
    void repeatedRunsProduceIdenticalReportIncludingId() {
        AnalysisService service = newService();
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        CompatReport first = service.analyzeFull(currentTree(), "2.0");
        CompatReport second = service.analyzeFull(currentTree(), "2.0");

        assertThat(second.reportVersion()).isEqualTo(first.reportVersion());
        assertThat(serialize(second)).isEqualTo(serialize(first));
    }

    @Test
    void reportHasNoRuntimeFields() throws Exception {
        AnalysisService service = newService();
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(currentTree(), "2.0");

        var fieldNames = JSON.readTree(serialize(report)).fieldNames();
        assertThat(fieldNames).toIterable()
                .containsExactly("reportVersion", "baselineVersion", "currentVersion", "changes");
    }

    @Test
    void anyInputChangeChangesTheReportId() {
        AnalysisService service = newService();
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        CompatReport original = service.analyzeFull(currentTree(), "2.0");

        String tweaked = Fixtures.SERVICE_V2.replace("public int compute(int x, int y)",
                "public int compute(int x, int z)");
        CompatReport contentChanged = service.analyzeFull(
                Map.of("Service.java", tweaked, "Caller.java", Fixtures.CALLER), "2.0");
        assertThat(contentChanged.reportVersion()).isNotEqualTo(original.reportVersion());

        CompatReport versionChanged = service.analyzeFull(currentTree(), "2.0.1");
        assertThat(versionChanged.reportVersion()).isNotEqualTo(original.reportVersion());
    }

    @Test
    void changeOrderIsStable() {
        AnalysisService a = newService();
        AnalysisService b = newService();
        a.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        b.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        var reportA = a.analyzeFull(currentTree(), "2.0");
        var reportB = b.analyzeFull(
                Map.of("Caller.java", Fixtures.CALLER, "Service.java", Fixtures.SERVICE_V2), "2.0");

        assertThat(reportB.reportVersion()).isEqualTo(reportA.reportVersion());
        assertThat(reportB.changes().stream().map(ApiChange::elementKey).toList())
                .isEqualTo(reportA.changes().stream().map(ApiChange::elementKey).toList());
    }

    @Test
    void reportSurvivesRestartWithPersistedBaseline(@TempDir Path tempDir) throws Exception {
        AnalysisService before = newService();
        before.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        Path baselineFile = tempDir.resolve("baseline.json");
        new BaselineStore().save(before.baselineSnapshot(), before.baselineSuppressions(), baselineFile);
        CompatReport first = before.analyzeFull(currentTree(), "2.0");

        // Simulate a service restart: fresh process loads the baseline and reruns.
        AnalysisService after = newService();
        BaselineStore.BaselineData data = new BaselineStore().load(baselineFile);
        after.installBaselineSnapshot(data.snapshot(), data.suppressedKeys());
        CompatReport second = after.analyzeFull(currentTree(), "2.0");

        assertThat(serialize(second)).isEqualTo(serialize(first));
        assertThat(second.reportVersion()).isEqualTo(first.reportVersion());
    }

    @Test
    void incrementalAndFullReportsIncludingIdAreIdentical() {
        Map<String, String> v1 = Map.of(
                "Service.java", Fixtures.SERVICE_V1,
                "Caller.java", Fixtures.CALLER,
                "Unrelated.java", Fixtures.UNRELATED);

        AnalysisService incremental = newService();
        incremental.setBaseline(v1, "1.0");
        incremental.analyzeFull(v1, "1.0");
        CompatReport inc = incremental.analyzeIncremental(
                Map.of("Service.java", Fixtures.SERVICE_V2), Set.of(), "2.0");

        AnalysisService full = newService();
        full.setBaseline(v1, "1.0");
        CompatReport fullReport = full.analyzeFull(
                Map.of("Service.java", Fixtures.SERVICE_V2,
                        "Caller.java", Fixtures.CALLER,
                        "Unrelated.java", Fixtures.UNRELATED), "2.0");

        assertThat(serialize(inc)).isEqualTo(serialize(fullReport));
        assertThat(inc.reportVersion()).isEqualTo(fullReport.reportVersion());
    }

    private static String serialize(CompatReport report) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
