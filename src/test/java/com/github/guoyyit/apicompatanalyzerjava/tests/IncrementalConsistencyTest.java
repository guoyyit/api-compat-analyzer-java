package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 6: incremental and full runs produce exactly the same report. */
class IncrementalConsistencyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    private static AnalysisService newService(SourceParser parser) {
        return new AnalysisService(parser, new ApiDiffEngine(), new IncrementalAnalyzer(),
                new SuppressionStore(), new AnalysisProperties());
    }

    private static String json(CompatReport report) {
        try {
            return JSON.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void incrementalMatchesFullForAbstractMethodScenario() {
        Map<String, String> v1 = new LinkedHashMap<>(Map.of(
                "Shape.java", Fixtures.SHAPE_V1,
                "Square.java", Fixtures.SHAPE_IMPL,
                "ShapeUser.java", Fixtures.SHAPE_USER,
                "Unrelated.java", Fixtures.UNRELATED));
        Map<String, String> v2 = new LinkedHashMap<>(v1);
        v2.put("Shape.java", Fixtures.SHAPE_V2_ABSTRACT);

        AnalysisService inc = newService();
        inc.setBaseline(v1, "1.0");
        inc.analyzeFull(v1, "1.0");
        CompatReport incremental = inc.analyzeIncremental(
                Map.of("Shape.java", Fixtures.SHAPE_V2_ABSTRACT), Set.of(), "2.0");

        AnalysisService full = newService();
        full.setBaseline(v1, "1.0");
        CompatReport fullReport = full.analyzeFull(v2, "2.0");

        assertThat(json(incremental)).isEqualTo(json(fullReport));
        assertThat(incremental.reportVersion()).isEqualTo(fullReport.reportVersion());
    }

    @Test
    void incrementalMatchesFullForInterfaceAndSuperclassScenario() {
        Map<String, String> v1 = Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_OLD_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER,
                "Repository.java", Fixtures.REPO_V1,
                "JdbcRepository.java", Fixtures.REPO_IMPL);
        Map<String, String> v2 = Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_NEW_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER,
                "Repository.java", Fixtures.REPO_V2_ABSTRACT,
                "JdbcRepository.java", Fixtures.REPO_IMPL);

        AnalysisService inc = newService();
        inc.setBaseline(v1, "1.0");
        inc.analyzeFull(v1, "1.0");
        CompatReport incremental = inc.analyzeIncremental(Map.of(
                "ChildType.java", Fixtures.CHILD_NEW_PARENT,
                "Repository.java", Fixtures.REPO_V2_ABSTRACT), Set.of(), "2.0");

        AnalysisService full = newService();
        full.setBaseline(v1, "1.0");
        CompatReport fullReport = full.analyzeFull(v2, "2.0");

        assertThat(json(incremental)).isEqualTo(json(fullReport));
        assertThat(incremental.reportVersion()).isEqualTo(fullReport.reportVersion());
    }

    @Test
    void incrementalReparsesOnlyChangedAndDependentFiles() {
        CountingParser counter = new CountingParser(new JavaSourceParser());
        AnalysisService service = newService(counter);

        Map<String, String> v1 = Map.of(
                "Shape.java", Fixtures.SHAPE_V1,
                "Square.java", Fixtures.SHAPE_IMPL,
                "ShapeUser.java", Fixtures.SHAPE_USER,
                "Unrelated.java", Fixtures.UNRELATED);
        service.setBaseline(v1, "1.0");
        service.analyzeFull(v1, "1.0");
        counter.reset();

        service.analyzeIncremental(Map.of("Shape.java", Fixtures.SHAPE_V2_ABSTRACT), Set.of(), "2.0");

        assertThat(counter.counts()).containsKey("Shape.java");
        assertThat(counter.counts()).containsKey("Square.java");   // depends on Shape
        assertThat(counter.counts()).containsKey("ShapeUser.java"); // depends on Shape/Square
        assertThat(counter.counts()).doesNotContainKey("Unrelated.java");
    }

    /** Parser decorator counting parse invocations per file. */
    static final class CountingParser implements SourceParser {
        private final SourceParser delegate;
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        CountingParser(SourceParser delegate) {
            this.delegate = delegate;
        }

        @Override
        public ParsedUnit parse(String file, String source) {
            counts.computeIfAbsent(file, k -> new AtomicInteger()).incrementAndGet();
            return delegate.parse(file, source);
        }

        Map<String, AtomicInteger> counts() {
            return counts;
        }

        void reset() {
            counts.clear();
        }
    }
}
