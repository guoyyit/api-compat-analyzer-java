package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 6: concurrent analysis/update requests never expose mixed versions. */
class ConcurrencyTest {

    private AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    @Test
    void readersAlwaysObserveOneConsistentVersion() throws Exception {
        AnalysisService service = newService();
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        // Version "clean" has no changes vs baseline; version "changed" removes a method.
        Map<String, String> clean = Map.of("Service.java", Fixtures.SERVICE_V1);
        Map<String, String> changed = Map.of("Service.java", Fixtures.SERVICE_V2);

        service.analyzeFull(clean, "clean");
        int rounds = 200;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean inconsistency = new AtomicBoolean(false);
        List<Future<?>> futures = new ArrayList<>();

        // Writer: alternates between two versions.
        futures.add(pool.submit(() -> {
            await(start);
            for (int i = 0; i < rounds; i++) {
                if (i % 2 == 0) {
                    service.analyzeFull(changed, "changed");
                } else {
                    service.analyzeFull(clean, "clean");
                }
            }
        }));

        // Readers: every observed report must be internally consistent.
        for (int r = 0; r < 3; r++) {
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < rounds * 2; i++) {
                    var report = service.currentReport();
                    if (report.isPresent()) {
                        CompatReport rep = report.get();
                        boolean consistent =
                                ("clean".equals(rep.currentVersion()) && rep.changes().isEmpty())
                                || ("changed".equals(rep.currentVersion()) && !rep.changes().isEmpty());
                        if (!consistent) {
                            inconsistency.set(true);
                        }
                    }
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(inconsistency).isFalse();
    }

    @Test
    void concurrentUpdatesAllCompleteWithWholeReports() throws Exception {
        AnalysisService service = newService();
        service.setBaseline(Map.of("Service.java", Fixtures.SERVICE_V1), "1.0");

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CompatReport>> futures = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int id = i;
            futures.add(pool.submit(() -> {
                await(start);
                if (id % 2 == 0) {
                    return service.analyzeFull(Map.of("Service.java", Fixtures.SERVICE_V1), "v" + id);
                }
                return service.analyzeFull(Map.of("Service.java", Fixtures.SERVICE_V2), "v" + id);
            }));
        }
        start.countDown();
        for (Future<CompatReport> f : futures) {
            CompatReport report = f.get(60, TimeUnit.SECONDS);
            // Each returned report is a complete, self-consistent object.
            assertThat(report.reportVersion()).isNotBlank();
            assertThat(report.generatedAt()).isNotNull();
        }
        pool.shutdownNow();

        // Final published report matches the current snapshot version.
        CompatReport last = service.currentReport().orElseThrow();
        assertThat(last.currentVersion()).isEqualTo(service.currentSnapshot().version());
    }

    @Test
    void concurrentIncrementalAndFullUpdatesStayConsistent() throws Exception {
        AnalysisService service = newService();
        Map<String, String> base = Map.of(
                "Service.java", Fixtures.SERVICE_V1,
                "Caller.java", Fixtures.CALLER);
        service.setBaseline(base, "1.0");
        service.analyzeFull(base, "1.0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> full = pool.submit(() -> {
            await(start);
            for (int i = 0; i < 50; i++) {
                service.analyzeFull(base, "full-" + i);
            }
        });
        Future<?> inc = pool.submit(() -> {
            await(start);
            for (int i = 0; i < 50; i++) {
                service.analyzeIncremental(Map.of(), Set.of(), "inc-" + i);
            }
        });
        start.countDown();
        full.get(60, TimeUnit.SECONDS);
        inc.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        CompatReport report = service.currentReport().orElseThrow();
        assertThat(report.currentVersion()).isEqualTo(service.currentSnapshot().version());
        assertThat(report.changes()).isEmpty(); // identical trees
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
