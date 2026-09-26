package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeKind;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisException;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisServiceTest {

    private AnalysisService service;

    @BeforeEach
    void setUp() {
        service = newService(new JavaSourceParser(), new AnalysisProperties());
    }

    private static AnalysisService newService(SourceParser parser, AnalysisProperties props) {
        return new AnalysisService(parser, new ApiDiffEngine(), new IncrementalAnalyzer(),
                new SuppressionStore(), props);
    }

    private static Map<String, String> tree(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static ApiChange findChange(CompatReport report, String elementKey, ChangeKind kind) {
        return report.changes().stream()
                .filter(c -> c.elementKey().equals(elementKey) && c.kind() == kind)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Requirement 1+2: breaking vs compatible, call-site locations
    // ------------------------------------------------------------------

    @Test
    void removedMethodIsBreakingAndLocatesCallSites() {
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER), "2.0");

        ApiChange removed = findChange(report, "METHOD:com.example.Service#compute(int)", ChangeKind.REMOVED);
        assertThat(removed).isNotNull();
        assertThat(removed.breaking()).isTrue();
        assertThat(removed.affectedCallSites())
                .extracting(CallSite::file).contains("Caller.java");
        assertThat(removed.affectedCallSites())
                .extracting(CallSite::line).contains(5);
        assertThat(removed.affectedCallSites())
                .extracting(CallSite::enclosingSymbol)
                .allSatisfy(s -> assertThat(s).contains("Caller"));
    }

    @Test
    void addedOverloadIsCompatibleNotBreaking() {
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER), "2.0");

        ApiChange added = findChange(report, "METHOD:com.example.Service#compute(int,int)", ChangeKind.ADDED);
        assertThat(added).isNotNull();
        assertThat(added.breaking()).isFalse();
        assertThat(added.affectedCallSites()).isEmpty();
    }

    @Test
    void accessNarrowingIsBreakingWideningIsCompatible() {
        String v2 = Fixtures.SERVICE_V1.replace("protected void hook()", "public void hook()")
                .replace("public String name()", "protected String name()");
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(tree("Service.java", v2), "2.0");

        ApiChange narrowed = findChange(report, "METHOD:com.example.Service#name()",
                ChangeKind.ACCESS_NARROWED);
        assertThat(narrowed).isNotNull();
        assertThat(narrowed.breaking()).isTrue();

        ApiChange widened = findChange(report, "METHOD:com.example.Service#hook()",
                ChangeKind.ACCESS_WIDENED);
        assertThat(widened).isNotNull();
        assertThat(widened.breaking()).isFalse();
        assertThat(report.breakingCount()).isEqualTo(1);
    }

    @Test
    void returnTypeChangeIsBreaking() {
        String v2 = Fixtures.SERVICE_V1.replace("public String name()", "public Object name()");
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(tree("Service.java", v2), "2.0");

        ApiChange change = findChange(report, "METHOD:com.example.Service#name()",
                ChangeKind.SIGNATURE_CHANGED);
        assertThat(change).isNotNull();
        assertThat(change.breaking()).isTrue();
    }

    @Test
    void identicalTreesProduceNoChanges() {
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport report = service.analyzeFull(tree("Service.java", Fixtures.SERVICE_V1), "1.0.1");
        assertThat(report.changes()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Requirement 3: inheritance, overriding, overloading
    // ------------------------------------------------------------------

    @Test
    void removingInheritedMethodFlagsSubtypeCallers() {
        service.setBaseline(tree(
                "Base.java", Fixtures.BASE,
                "Child.java", Fixtures.CHILD), "1.0");
        CompatReport report = service.analyzeFull(tree(
                "Base.java", Fixtures.BASE_V2,
                "Child.java", Fixtures.CHILD,
                "ChildCaller.java", Fixtures.CHILD_CALLER), "2.0");

        ApiChange baseRemoved = findChange(report, "METHOD:com.example.Base#greet()", ChangeKind.REMOVED);
        assertThat(baseRemoved).isNotNull();
        assertThat(baseRemoved.breaking()).isTrue();

        ApiChange inheritedRemoved = findChange(report, "METHOD:com.example.Child#greet()",
                ChangeKind.REMOVED);
        assertThat(inheritedRemoved).isNotNull();
        assertThat(inheritedRemoved.affectedCallSites())
                .extracting(CallSite::file).contains("ChildCaller.java");
    }

    @Test
    void overrideKeepsSubtypeApiWhenBaseMethodRemoved() {
        String childOverride = """
                package com.example;
                public class Child extends Base {
                    public void greet() {}
                }
                """;
        service.setBaseline(tree("Base.java", Fixtures.BASE, "Child.java", Fixtures.CHILD), "1.0");
        CompatReport report = service.analyzeFull(tree(
                "Base.java", Fixtures.BASE_V2, "Child.java", childOverride), "2.0");

        assertThat(findChange(report, "METHOD:com.example.Child#greet()", ChangeKind.REMOVED)).isNull();
        assertThat(findChange(report, "METHOD:com.example.Base#greet()", ChangeKind.REMOVED)).isNotNull();
    }

    @Test
    void removingOneOverloadKeepsTheOther() {
        String overV1 = """
                package com.example;
                public class Over {
                    public void m(int x) {}
                    public void m(String s) {}
                }
                """;
        String overV2 = """
                package com.example;
                public class Over {
                    public void m(int x) {}
                }
                """;
        String caller = """
                package com.example;
                public class OverCaller {
                    public void call(Over over) {
                        over.m("text");
                    }
                }
                """;
        service.setBaseline(tree("Over.java", overV1), "1.0");
        CompatReport report = service.analyzeFull(tree("Over.java", overV2, "OverCaller.java", caller), "2.0");

        ApiChange removed = findChange(report, "METHOD:com.example.Over#m(String)", ChangeKind.REMOVED);
        assertThat(removed).isNotNull();
        assertThat(removed.breaking()).isTrue();
        assertThat(removed.affectedCallSites()).extracting(CallSite::file).contains("OverCaller.java");
        assertThat(findChange(report, "METHOD:com.example.Over#m(int)", ChangeKind.REMOVED)).isNull();
    }

    // ------------------------------------------------------------------
    // Requirement 4: suppressions
    // ------------------------------------------------------------------

    @Test
    void suppressedChangesAreMarkedAndExcludedFromBreakingCount() {
        String legacyV2 = """
                package com.example;
                public class Legacy {
                }
                """;
        service.setBaseline(tree("Legacy.java", Fixtures.IGNORED_ANNOTATION), "1.0");
        CompatReport report = service.analyzeFull(tree("Legacy.java", legacyV2), "2.0");

        ApiChange suppressed = findChange(report, "METHOD:com.example.Legacy#oldWay()",
                ChangeKind.REMOVED);
        assertThat(suppressed).isNotNull();
        assertThat(suppressed.suppressed()).isTrue();

        ApiChange kept = findChange(report, "METHOD:com.example.Legacy#kept()", ChangeKind.REMOVED);
        assertThat(kept).isNotNull();
        assertThat(kept.suppressed()).isFalse();
        assertThat(report.breakingCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Requirement 5: incremental updates
    // ------------------------------------------------------------------

    @Test
    void incrementalResultEqualsFullResult() {
        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1,
                "Caller.java", Fixtures.CALLER, "Unrelated.java", Fixtures.UNRELATED);
        service.setBaseline(v1, "1.0");
        service.analyzeFull(v1, "1.0");

        CompatReport incremental = service.analyzeIncremental(
                tree("Service.java", Fixtures.SERVICE_V2), Set.of(), "2.0");

        AnalysisService fresh = newService(new JavaSourceParser(), new AnalysisProperties());
        fresh.setBaseline(v1, "1.0");
        CompatReport full = fresh.analyzeFull(tree(
                "Service.java", Fixtures.SERVICE_V2,
                "Caller.java", Fixtures.CALLER,
                "Unrelated.java", Fixtures.UNRELATED), "2.0");

        assertThat(summarize(incremental)).isEqualTo(summarize(full));
    }

    @Test
    void incrementalDoesNotReparseUnaffectedFiles() {
        CountingParser counting = new CountingParser(new JavaSourceParser());
        AnalysisService countingService = newService(counting, new AnalysisProperties());

        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1,
                "Caller.java", Fixtures.CALLER, "Unrelated.java", Fixtures.UNRELATED);
        countingService.setBaseline(v1, "1.0");
        countingService.analyzeFull(v1, "1.0");
        counting.reset();

        countingService.analyzeIncremental(tree("Service.java", Fixtures.SERVICE_V2), Set.of(), "2.0");

        // Service.java changed; Caller.java depends on it; Unrelated.java must not be re-parsed.
        assertThat(counting.counts()).containsKey("Service.java");
        assertThat(counting.counts()).doesNotContainKey("Unrelated.java");
    }

    @Test
    void incrementalHandlesDeletedFiles() {
        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1,
                "Caller.java", Fixtures.CALLER);
        service.setBaseline(v1, "1.0");
        service.analyzeFull(v1, "1.0");

        CompatReport report = service.analyzeIncremental(Map.of(), Set.of("Service.java"), "2.0");
        assertThat(findChange(report, "TYPE:com.example.Service", ChangeKind.REMOVED)).isNotNull();
        assertThat(service.currentSnapshot().elements()).doesNotContainKey("TYPE:com.example.Service");
    }

    // ------------------------------------------------------------------
    // Requirement 7: bounds
    // ------------------------------------------------------------------

    @Test
    void timeoutPublishesNoReport() {
        SourceParser slowParser = (file, source) -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new JavaSourceParser().parse(file, source);
        };
        AnalysisProperties props = new AnalysisProperties();
        props.setTimeoutMillis(100);
        AnalysisService slow = newService(slowParser, props);

        assertThatThrownBy(() -> slow.analyzeFull(tree("Service.java", Fixtures.SERVICE_V1), "1.0"))
                .isInstanceOf(AnalysisException.class)
                .hasMessageContaining("timed out");
        assertThat(slow.currentReport()).isEmpty();
    }

    @Test
    void resourceLimitRejectsOversizeInput() {
        AnalysisProperties props = new AnalysisProperties();
        props.setMaxFiles(1);
        AnalysisService limited = newService(new JavaSourceParser(), props);

        assertThatThrownBy(() -> limited.analyzeFull(
                tree("A.java", Fixtures.SERVICE_V1, "B.java", Fixtures.CALLER), "1.0"))
                .isInstanceOf(AnalysisException.class)
                .hasMessageContaining("Too many files");
        assertThat(limited.currentReport()).isEmpty();
    }

    private static List<String> summarize(CompatReport report) {
        return report.changes().stream()
                .map(c -> c.kind() + "|" + c.breaking() + "|" + c.elementKey() + "|"
                        + c.affectedCallSites().stream()
                                .map(cs -> cs.file() + ":" + cs.line()).sorted().toList())
                .sorted()
                .toList();
    }

    /** Parser decorator that counts parse invocations per file. */
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
