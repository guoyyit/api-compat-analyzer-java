package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.increment.SnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.service.BaselineStore;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 4: exemptions enter the baseline and are reused by later comparisons. */
class BaselineStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void baselineRoundTripPreservesElementsAndSuppressions() {
        JavaSourceParser parser = new JavaSourceParser();
        ParsedUnit unit = parser.parse("Legacy.java", Fixtures.IGNORED_ANNOTATION);
        ApiSnapshot snapshot = SnapshotBuilder.build("1.0", List.of(unit), Map.of("Legacy.java", "hash"));

        Path file = tempDir.resolve("baseline.json");
        BaselineStore store = new BaselineStore();
        store.save(snapshot, unit.suppressedKeys(), file);

        BaselineStore.BaselineData loaded = store.load(file);
        assertThat(loaded.snapshot().version()).isEqualTo("1.0");
        assertThat(loaded.snapshot().elements().keySet())
                .isEqualTo(snapshot.elements().keySet());
        assertThat(loaded.suppressedKeys())
                .contains("METHOD:com.example.Legacy#oldWay()");
    }

    @Test
    void loadedBaselineSuppressesLaterComparison() {
        AnalysisService first = newService();
        first.setBaseline(Map.of("Legacy.java", Fixtures.IGNORED_ANNOTATION), "1.0");

        Path file = tempDir.resolve("baseline.json");
        new BaselineStore().save(first.baselineSnapshot(), first.baselineSuppressions(), file);

        // New service instance (e.g. next CI run) loads the persisted baseline.
        AnalysisService second = newService();
        BaselineStore.BaselineData data = new BaselineStore().load(file);
        second.installBaselineSnapshot(data.snapshot(), data.suppressedKeys());

        String legacyV2 = """
                package com.example;
                public class Legacy {
                    public void kept() {}
                }
                """;
        CompatReport report = second.analyzeFull(Map.of("Legacy.java", legacyV2), "2.0");
        assertThat(report.changes())
                .filteredOn(c -> c.elementKey().equals("METHOD:com.example.Legacy#oldWay()"))
                .allSatisfy(c -> assertThat(c.suppressed()).isTrue());
        assertThat(report.breakingCount()).isZero();
    }

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }
}
