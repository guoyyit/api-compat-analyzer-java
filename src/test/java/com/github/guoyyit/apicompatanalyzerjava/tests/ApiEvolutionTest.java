package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.github.guoyyit.apicompatanalyzerjava.service.BaselineStore;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Coverage for the version-evolution rules: deterministic reports, abstract
 * method obligations, interface defaults, superclass changes, full/incremental
 * parity, concurrency and failure retry.
 */
class ApiEvolutionTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    private static AnalysisService newService(SourceParser parser, AnalysisProperties properties) {
        return new AnalysisService(parser, new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), properties);
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
    // Requirement 1: deterministic reports
    // ------------------------------------------------------------------

    @Test
    void repeatedRunsProduceByteIdenticalReports() throws Exception {
        AnalysisService service = newService();
        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1);
        Map<String, String> v2 = tree("Service.java", Fixtures.SERVICE_V2,
                "Caller.java", Fixtures.CALLER);
        service.setBaseline(v1, "1.0");

        CompatReport first = service.analyzeFull(v2, "2.0");
        CompatReport second = service.analyzeFull(v2, "2.0");

        assertThat(first.reportVersion()).isEqualTo(second.reportVersion());
        assertThat(first.generatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(second.generatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(JSON.writeValueAsString(first)).isEqualTo(JSON.writeValueAsString(second));
        assertThat(first.reportVersion()).startsWith("report-");
    }

    @Test
    void anyInputChangeChangesReportIdentityEvenWhenApiIsIdentical() {
        AnalysisService service = newService();
        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1);
        service.setBaseline(v1, "1.0");

        CompatReport first = service.analyzeFull(v1, "x");
        String withComment = Fixtures.SERVICE_V1.replaceFirst("public class Service",
                "// a harmless comment that changes raw input\npublic class Service");
        CompatReport commented = service.analyzeFull(tree("Service.java", withComment), "x");
        CompatReport reVersioned = service.analyzeFull(v1, "different-label");

        assertThat(first.changes()).isEmpty();
        assertThat(commented.changes()).isEmpty();
        assertThat(commented.reportVersion()).isNotEqualTo(first.reportVersion());
        assertThat(reVersioned.reportVersion()).isNotEqualTo(first.reportVersion());
    }

    @Test
    void baselineRestoredAfterRestartYieldsIdenticalReport() throws Exception {
        AnalysisService first = newService();
        Map<String, String> v1 = tree("Service.java", Fixtures.SERVICE_V1);
        Map<String, String> v2 = tree("Service.java", Fixtures.SERVICE_V2,
                "Caller.java", Fixtures.CALLER);
        first.setBaseline(v1, "1.0");
        CompatReport before = first.analyzeFull(v2, "2.0");

        Path file = tempDir.resolve("baseline.json");
        new BaselineStore().save(first.baselineSnapshot(), first.baselineSuppressions(),
                first.baselineFingerprint(), file);

        AnalysisService restarted = newService();
        BaselineStore.BaselineData data = new BaselineStore().load(file);
        restarted.installBaselineSnapshot(data.snapshot(), data.suppressedKeys(),
                data.fingerprint());
        CompatReport after = restarted.analyzeFull(v2, "2.0");

        assertThat(JSON.writeValueAsString(after)).isEqualTo(JSON.writeValueAsString(before));
    }

    // ------------------------------------------------------------------
    // Requirement 2: abstract method added to an inheritable public type
    // ------------------------------------------------------------------

    @Test
    void abstractMethodAddedBreaksConcreteSubtypesWithLocations() {
        String shapeV1 = """
                package com.example;
                public abstract class Shape {
                    public String name() { return "shape"; }
                }
                """;
        String shapeV2 = """
                package com.example;
                public abstract class Shape {
                    public String name() { return "shape"; }
                    public abstract int area();
                }
                """;
        String circle = """
                package com.example;
                public class Circle extends Shape {
                }
                """;
        String square = """
                package com.example;
                public class Square extends Shape {
                    public int area() { return 1; }
                }
                """;
        String holder = """
                package com.example;
                abstract class Holder extends Shape {
                }
                """;
        String mini = """
                package com.example;
                public class Mini extends Holder {
                }
                """;
        String shapeUser = """
                package com.example;
                public class ShapeUser {
                    public Shape make() { return new Circle(); }
                }
                """;

        AnalysisService service = newService();
        service.setBaseline(tree("Shape.java", shapeV1, "Circle.java", circle,
                "Square.java", square, "Holder.java", holder, "Mini.java", mini), "1.0");
        CompatReport report = service.analyzeFull(tree(
                "Shape.java", shapeV2, "Circle.java", circle, "Square.java", square,
                "Holder.java", holder, "Mini.java", mini, "ShapeUser.java", shapeUser), "2.0");

        ApiChange change = findChange(report, "METHOD:com.example.Shape#area()",
                ChangeKind.ABSTRACT_METHOD_ADDED);
        assertThat(change).isNotNull();
        assertThat(change.breaking()).isTrue();

        var sites = change.affectedCallSites();
        assertThat(sites).anySatisfy(cs -> {
            assertThat(cs.file()).isEqualTo("Circle.java");
            assertThat(cs.line()).isEqualTo(2);
            assertThat(cs.enclosingSymbol()).isEqualTo("com.example.Circle");
            assertThat(cs.referencedKey()).isEqualTo("TYPE:com.example.Circle");
        });
        assertThat(sites).anySatisfy(cs -> {
            assertThat(cs.file()).isEqualTo("Mini.java");
            assertThat(cs.line()).isEqualTo(2);
            assertThat(cs.enclosingSymbol()).isEqualTo("com.example.Mini");
        });
        assertThat(sites).anySatisfy(cs -> {
            assertThat(cs.file()).isEqualTo("ShapeUser.java");
            assertThat(cs.line()).isEqualTo(3);
        });
        // Square already implements area(); the abstract intermediate Holder is exempt.
        assertThat(sites).extracting(CallSite::file)
                .doesNotContain("Square.java", "Holder.java");
    }

    // ------------------------------------------------------------------
    // Requirement 3: interface method additions
    // ------------------------------------------------------------------

    @Test
    void interfaceAbstractMethodBreaksImplementors() {
        String repoV1 = """
                package com.example;
                public interface Repo {
                    String find(int id);
                }
                """;
        String repoV2 = """
                package com.example;
                public interface Repo {
                    String find(int id);
                    int count();
                }
                """;
        String impl = """
                package com.example;
                public class RepoImpl implements Repo {
                    public String find(int id) { return ""; }
                }
                """;
        String user = """
                package com.example;
                public class RepoUser {
                    public Repo get() { return new RepoImpl(); }
                }
                """;

        AnalysisService service = newService();
        service.setBaseline(tree("Repo.java", repoV1, "RepoImpl.java", impl), "1.0");
        CompatReport report = service.analyzeFull(
                tree("Repo.java", repoV2, "RepoImpl.java", impl, "RepoUser.java", user), "2.0");

        ApiChange change = findChange(report, "METHOD:com.example.Repo#count()",
                ChangeKind.ABSTRACT_METHOD_ADDED);
        assertThat(change).isNotNull();
        assertThat(change.breaking()).isTrue();
        var sites = change.affectedCallSites();
        assertThat(sites).anySatisfy(cs -> {
            assertThat(cs.file()).isEqualTo("RepoImpl.java");
            assertThat(cs.line()).isEqualTo(2);
            assertThat(cs.enclosingSymbol()).isEqualTo("com.example.RepoImpl");
            assertThat(cs.referencedKey()).isEqualTo("TYPE:com.example.RepoImpl");
        });
        assertThat(sites).extracting(CallSite::file).contains("RepoUser.java");
    }

    @Test
    void interfaceDefaultMethodIsCompatible() {
        String repoV1 = """
                package com.example;
                public interface Repo {
                    String find(int id);
                }
                """;
        String repoDefault = """
                package com.example;
                public interface Repo {
                    String find(int id);
                    default int count() { return 0; }
                }
                """;
        String impl = """
                package com.example;
                public class RepoImpl implements Repo {
                    public String find(int id) { return ""; }
                }
                """;

        AnalysisService service = newService();
        service.setBaseline(tree("Repo.java", repoV1, "RepoImpl.java", impl), "1.0");
        CompatReport report = service.analyzeFull(
                tree("Repo.java", repoDefault, "RepoImpl.java", impl), "2.0");

        assertThat(findChange(report, "METHOD:com.example.Repo#count()",
                ChangeKind.ABSTRACT_METHOD_ADDED)).isNull();
        ApiChange added = findChange(report, "METHOD:com.example.Repo#count()", ChangeKind.ADDED);
        assertThat(added).isNotNull();
        assertThat(added.breaking()).isFalse();
        assertThat(report.breakingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 4: superclass changes
    // ------------------------------------------------------------------

    @Test
    void replacingSuperclassIsBreakingAndPropagatesMembers() {
        String oldBase = """
                package com.example;
                public class OldBase {
                    public int size() { return 0; }
                }
                """;
        String newBase = """
                package com.example;
                public class NewBase {
                    public String label() { return ""; }
                }
                """;
        String childV1 = """
                package com.example;
                public class Child extends OldBase {
                }
                """;
        String childV2 = """
                package com.example;
                public class Child extends NewBase {
                }
                """;
        String childUser = """
                package com.example;
                public class ChildUser {
                    public void run() {
                        Child c = new Child();
                        int s = c.size();
                    }
                }
                """;

        AnalysisService service = newService();
        service.setBaseline(tree("OldBase.java", oldBase, "NewBase.java", newBase,
                "Child.java", childV1), "1.0");
        CompatReport report = service.analyzeFull(tree(
                "OldBase.java", oldBase, "NewBase.java", newBase,
                "Child.java", childV2, "ChildUser.java", childUser), "2.0");

        ApiChange superChange = findChange(report, "TYPE:com.example.Child",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(superChange).isNotNull();
        assertThat(superChange.breaking()).isTrue();
        assertThat(superChange.oldDetail()).isEqualTo("com.example.OldBase");
        assertThat(superChange.newDetail()).isEqualTo("com.example.NewBase");
        assertThat(superChange.affectedCallSites())
                .anySatisfy(cs -> {
                    assertThat(cs.file()).isEqualTo("Child.java");
                    assertThat(cs.line()).isEqualTo(2);
                    assertThat(cs.enclosingSymbol()).isEqualTo("com.example.Child");
                })
                .anySatisfy(cs -> {
                    assertThat(cs.file()).isEqualTo("ChildUser.java");
                    assertThat(cs.enclosingSymbol()).contains("ChildUser");
                });

        // The inherited member that disappears through the parent swap is also
        // reported, pointing at the concrete call site.
        ApiChange inheritedRemoved = findChange(report, "METHOD:com.example.Child#size()",
                ChangeKind.REMOVED);
        assertThat(inheritedRemoved).isNotNull();
        assertThat(inheritedRemoved.breaking()).isTrue();
        assertThat(inheritedRemoved.affectedCallSites()).anySatisfy(cs -> {
            assertThat(cs.file()).isEqualTo("ChildUser.java");
            assertThat(cs.line()).isEqualTo(5);
            assertThat(cs.enclosingSymbol()).isEqualTo("com.example.ChildUser#run()");
        });
    }

    @Test
    void removingAndAddingSuperclassIsBreaking() {
        String base = """
                package com.example;
                public class Base {
                    public void greet() {}
                }
                """;
        String childExtends = """
                package com.example;
                public class Child extends Base {
                }
                """;
        String childPlain = """
                package com.example;
                public class Child {
                }
                """;

        AnalysisService removal = newService();
        removal.setBaseline(tree("Base.java", base, "Child.java", childExtends), "1.0");
        CompatReport removed = removal.analyzeFull(
                tree("Base.java", base, "Child.java", childPlain), "2.0");
        ApiChange removeChange = findChange(removed, "TYPE:com.example.Child",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(removeChange).isNotNull();
        assertThat(removeChange.breaking()).isTrue();
        assertThat(removeChange.oldDetail()).isEqualTo("com.example.Base");
        assertThat(removeChange.newDetail()).isEmpty();

        AnalysisService addition = newService();
        addition.setBaseline(tree("Base.java", base, "Child.java", childPlain), "1.0");
        CompatReport added = addition.analyzeFull(
                tree("Base.java", base, "Child.java", childExtends), "2.0");
        ApiChange addChange = findChange(added, "TYPE:com.example.Child",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(addChange).isNotNull();
        assertThat(addChange.breaking()).isTrue();
        assertThat(addChange.oldDetail()).isEmpty();
        assertThat(addChange.newDetail()).isEqualTo("com.example.Base");
    }

    // ------------------------------------------------------------------
    // Requirement 5: compatible additions and access widening
    // ------------------------------------------------------------------

    @Test
    void newMethodsOverloadsAndWidenedAccessStayCompatible() {
        String v1 = """
                package com.example;
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                    protected void hook() {}
                }
                """;
        String v2 = """
                package com.example;
                public class Calc {
                    public int add(int a, int b) { return a + b; }
                    public int add(int a, int b, int c) { return a + b + c; }
                    public void hook() {}
                    public String describe() { return "calc"; }
                }
                """;
        AnalysisService service = newService();
        service.setBaseline(tree("Calc.java", v1), "1.0");
        CompatReport report = service.analyzeFull(tree("Calc.java", v2), "2.0");

        assertThat(report.changes()).allSatisfy(c -> assertThat(c.breaking()).isFalse());
        assertThat(findChange(report, "METHOD:com.example.Calc#add(int,int,int)",
                ChangeKind.ADDED)).isNotNull();
        assertThat(findChange(report, "METHOD:com.example.Calc#describe()",
                ChangeKind.ADDED)).isNotNull();
        assertThat(findChange(report, "METHOD:com.example.Calc#hook()",
                ChangeKind.ACCESS_WIDENED)).isNotNull();
        assertThat(report.breakingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 6: incremental path equals full path
    // ------------------------------------------------------------------

    @Test
    void incrementalAndFullReportsAreIdenticalIncludingId() throws Exception {
        String shapeV1 = """
                package com.example;
                public abstract class Shape {
                    public String name() { return "shape"; }
                }
                """;
        String shapeV2 = """
                package com.example;
                public abstract class Shape {
                    public String name() { return "shape"; }
                    public abstract int area();
                }
                """;
        String circle = """
                package com.example;
                public class Circle extends Shape {
                    public int area() { return 1; }
                }
                """;
        String unrelated = """
                package com.example.util;
                public class Printer {
                    public void print() {}
                }
                """;

        Map<String, String> v1 = tree("Shape.java", shapeV1, "Circle.java", circle,
                "Printer.java", unrelated);

        AnalysisService incremental = newService();
        incremental.setBaseline(v1, "1.0");
        incremental.analyzeFull(v1, "1.0");
        CompatReport incrementalReport = incremental.analyzeIncremental(
                tree("Shape.java", shapeV2), Set.of(), "2.0");

        AnalysisService full = newService();
        full.setBaseline(v1, "1.0");
        CompatReport fullReport = full.analyzeFull(
                tree("Shape.java", shapeV2, "Circle.java", circle, "Printer.java", unrelated), "2.0");

        assertThat(incrementalReport.reportVersion()).isEqualTo(fullReport.reportVersion());
        assertThat(incrementalReport.generatedAt()).isEqualTo(fullReport.generatedAt());
        assertThat(JSON.writeValueAsString(incrementalReport))
                .isEqualTo(JSON.writeValueAsString(fullReport));
    }

    @Test
    void incrementalDoesNotReparseUnaffectedFiles() {
        CountingParser counting = new CountingParser(new JavaSourceParser());
        AnalysisService service = newService(counting, new AnalysisProperties());

        String baseV1 = "package com.example; public class Base { public void m() {} }";
        String baseV2 = "package com.example; public class Base { public void m() {} public void n() {} }";
        String child = "package com.example; public class Child extends Base {}";
        String unrelated = "package com.example.util; public class Lone { public void x() {} }";

        Map<String, String> v1 = tree("Base.java", baseV1, "Child.java", child,
                "Lone.java", unrelated);
        service.setBaseline(v1, "1.0");
        service.analyzeFull(v1, "1.0");
        counting.reset();

        service.analyzeIncremental(tree("Base.java", baseV2), Set.of(), "2.0");

        assertThat(counting.counts()).containsKey("Base.java");
        assertThat(counting.counts()).containsKey("Child.java"); // depends on Base
        assertThat(counting.counts()).doesNotContainKey("Lone.java");
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

    // ------------------------------------------------------------------
    // Requirement 7: concurrent consistency
    // ------------------------------------------------------------------

    @Test
    void concurrentAnalysesOfSameInputReturnIdenticalReports() throws Exception {
        AnalysisService service = newService();
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        Map<String, String> v2 = tree("Service.java", Fixtures.SERVICE_V2,
                "Caller.java", Fixtures.CALLER);

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<CompatReport>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.analyzeFull(v2, "2.0");
            }));
        }
        start.countDown();
        CompatReport expected = futures.get(0).get(60, TimeUnit.SECONDS);
        for (var future : futures) {
            CompatReport report = future.get(60, TimeUnit.SECONDS);
            assertThat(report.reportVersion()).isEqualTo(expected.reportVersion());
            assertThat(report.changes()).isEqualTo(expected.changes());
        }
        CompatReport published = service.currentReport().orElseThrow();
        assertThat(published.reportVersion()).isEqualTo(expected.reportVersion());
        assertThat(published.currentVersion()).isEqualTo(service.currentSnapshot().version());
        pool.shutdownNow();
    }

    @Test
    void readersNeverObserveMixedSnapshotAndReport() throws Exception {
        AnalysisService service = newService();
        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        Map<String, String> clean = tree("Service.java", Fixtures.SERVICE_V1);
        Map<String, String> changed = tree("Service.java", Fixtures.SERVICE_V2);
        service.analyzeFull(clean, "clean");

        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        AtomicInteger inconsistent = new AtomicInteger();

        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 100; i++) {
                service.analyzeFull(i % 2 == 0 ? changed : clean, i % 2 == 0 ? "changed" : "clean");
            }
            return null;
        }));
        for (int r = 0; r < 3; r++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 500; i++) {
                    CompatReport report = service.currentReport().orElse(null);
                    String snapshotVersion = service.currentSnapshot().version();
                    if (report != null && !report.currentVersion().equals(snapshotVersion)) {
                        inconsistent.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (var future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(inconsistent).hasValue(0);
    }

    // ------------------------------------------------------------------
    // Requirement 8: failures publish nothing and retries are identical
    // ------------------------------------------------------------------

    @Test
    void parseFailureKeepsPreviousReportAndRetryMatchesNoFailureRun() {
        SourceParser failOnce = new OneShotParser(OneShotParser.Mode.FAIL);
        AnalysisService service = newService(failOnce, new AnalysisProperties());

        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport previous = service.analyzeFull(tree("Service.java", Fixtures.SERVICE_V1), "prev");

        assertThatThrownBy(() -> service.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER), "2.0"))
                .isInstanceOf(AnalysisException.class);

        assertThat(service.currentReport()).containsSame(previous);
        assertThat(service.currentSnapshot().version()).isEqualTo("prev");

        CompatReport retried = service.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER), "2.0");

        AnalysisService pristine = newService();
        pristine.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport noFailure = pristine.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2, "Caller.java", Fixtures.CALLER), "2.0");
        assertThat(retried.reportVersion()).isEqualTo(noFailure.reportVersion());
        assertThat(retried.changes()).isEqualTo(noFailure.changes());
    }

    @Test
    void timeoutKeepsPreviousReportAndRetrySucceeds() {
        AnalysisProperties props = new AnalysisProperties();
        props.setTimeoutMillis(150);
        SourceParser slowOnce = new OneShotParser(OneShotParser.Mode.SLOW);
        AnalysisService service = newService(slowOnce, props);

        service.setBaseline(tree("Service.java", Fixtures.SERVICE_V1), "1.0");
        CompatReport previous = service.analyzeFull(tree("Service.java", Fixtures.SERVICE_V1), "prev");

        assertThatThrownBy(() -> service.analyzeFull(
                tree("Service.java", Fixtures.SERVICE_V2), "2.0"))
                .isInstanceOf(AnalysisException.class)
                .hasMessageContaining("timed out");

        assertThat(service.currentReport()).containsSame(previous);

        CompatReport retried = service.analyzeFull(tree("Service.java", Fixtures.SERVICE_V2), "2.0");
        assertThat(retried.currentVersion()).isEqualTo("2.0");
        assertThat(retried.reportVersion()).isNotBlank();
    }

    /** Parser that misbehaves only on its first parse invocation. */
    static final class OneShotParser implements SourceParser {
        enum Mode {FAIL, SLOW}

        private final Mode mode;
        private final AtomicInteger calls = new AtomicInteger();

        OneShotParser(Mode mode) {
            this.mode = mode;
        }

        @Override
        public ParsedUnit parse(String file, String source) {
            // Only the current-version inputs trigger the fault; baseline stays healthy.
            boolean isCurrentInput = source.contains("compute(int x, int y)")
                    || source.contains("class Caller");
            if (isCurrentInput && calls.incrementAndGet() == 1) {
                if (mode == Mode.FAIL) {
                    throw new IllegalStateException("synthetic parse failure");
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AnalysisException("interrupted", e);
                }
            }
            return new JavaSourceParser().parse(file, source);
        }
    }
}
