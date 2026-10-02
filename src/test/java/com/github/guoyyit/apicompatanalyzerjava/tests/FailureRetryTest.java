package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisException;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 8: failures publish nothing; retry reproduces the never-failed report. */
class FailureRetryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnalysisService newService(SourceParser parser, AnalysisProperties props) {
        return new AnalysisService(parser, new ApiDiffEngine(), new IncrementalAnalyzer(),
                new SuppressionStore(), props);
    }

    private static AnalysisService newService(SourceParser parser) {
        return newService(parser, new AnalysisProperties());
    }

    private static String json(CompatReport report) {
        try {
            return JSON.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void timeoutKeepsPreviousReportAndRetryProducesFreshResult() {
        JavaSourceParser healthy = new JavaSourceParser();
        SourceParser flaky = new SourceParser() {
            private volatile boolean fail = true;

            @Override
            public ParsedUnit parse(String file, String source) {
                if (fail && file.equals("Service.java") && source.equals(Fixtures.SERVICE_V2)) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AnalysisException("interrupted", e);
                    }
                    fail = false;
                }
                return healthy.parse(file, source);
            }
        };

        AnalysisProperties props = new AnalysisProperties();
        props.setTimeoutMillis(30_000);
        AnalysisService service = newService(flaky, props);
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport previous = service.analyzeFull(
                Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        props.setTimeoutMillis(100);
        assertThatThrownBy(() -> service.analyzeFull(
                Map.of("Service.java", Fixtures.SERVICE_V2), "2.0"))
                .isInstanceOf(AnalysisException.class)
                .hasMessageContaining("timed out");

        // No partial result: previous report stays fully readable and untouched.
        CompatReport afterFailure = service.currentReport().orElseThrow();
        assertThat(json(afterFailure)).isEqualTo(json(previous));

        // Retry with a healthy service budget reproduces the never-failed report.
        props.setTimeoutMillis(30_000);
        CompatReport retried = service.analyzeFull(
                Map.of("Service.java", Fixtures.SERVICE_V2), "2.0");

        AnalysisService fresh = newService(new JavaSourceParser());
        fresh.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport expected = fresh.analyzeFull(
                Map.of("Service.java", Fixtures.SERVICE_V2), "2.0");

        assertThat(json(retried)).isEqualTo(json(expected));
        assertThat(retried.reportVersion()).isEqualTo(expected.reportVersion());
    }

    @Test
    void resourceExhaustionBeforePublishLeavesPreviousReportIntact() {
        AnalysisProperties tight = new AnalysisProperties();
        tight.setMaxFiles(1);

        AnalysisService bounded = newService(new JavaSourceParser(), tight);
        bounded.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport first = bounded.analyzeFull(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");
        assertThatThrownBy(() -> bounded.analyzeFull(Map.of(
                "Service.java", Fixtures.SERVICE_V2,
                "Caller.java", Fixtures.CALLER), "2.0"))
                .isInstanceOf(AnalysisException.class);
        assertThat(json(bounded.currentReport().orElseThrow())).isEqualTo(json(first));
        assertThat(bounded.currentReport().orElseThrow().currentVersion()).isEqualTo("1.0");
    }
}
