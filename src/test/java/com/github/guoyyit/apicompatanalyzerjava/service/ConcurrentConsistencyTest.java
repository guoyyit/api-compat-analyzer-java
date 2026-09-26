package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import com.github.guoyyit.apicompatanalyzerjava.support.TestServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentConsistencyTest {

    private Sources project(String marker) {
        return Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public String api() { return \"" + marker + "\"; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.api(); } }");
    }

    @Test
    void readersAlwaysObserveOneConsistentVersionDuringUpdates(@TempDir Path dir)
            throws Exception {
        var service = TestServices.create(dir);
        AnalysisReport initial = service.analyzeSnapshots(
                project("init").reader(), project("init").reader(),
                AnalysisOptions.defaults());
        assertNotNull(initial);

        int updateCount = 8;
        int readerCount = 4;
        ExecutorService readerPool = Executors.newFixedThreadPool(readerCount);
        ExecutorService writerPool = Executors.newSingleThreadExecutor();

        // Readers start reading only after the running phase opens, and stop only after
        // the writer finishes. Separate latches avoid release/completion races.
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Set<String> observedSnapshotIds = ConcurrentHashMap.newKeySet();
        AtomicInteger malformed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        for (int r = 0; r < readerCount; r++) {
            readerPool.submit(() -> {
                try {
                    running.await();
                    boolean keepReading = true;
                    while (keepReading) {
                        AnalysisReport report = service.latestReport();
                        if (report == null) {
                            failures.incrementAndGet();
                        } else {
                            observedSnapshotIds.add(report.currentSnapshotId());
                            if (report.reportId() == null || report.currentSnapshotId() == null
                                    || report.changes() == null || report.impacts() == null) {
                                malformed.incrementAndGet();
                            }
                        }
                        Thread.sleep(1);
                        keepReading = finished.getCount() > 0;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        running.countDown();
        Future<?> writer = writerPool.submit(() -> {
            for (int u = 0; u < updateCount; u++) {
                service.analyzeSnapshots(project("base").reader(),
                        project("v" + u).reader(), AnalysisOptions.defaults());
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        writer.get(30, TimeUnit.SECONDS);
        finished.countDown();
        readerPool.shutdown();
        assertTrue(readerPool.awaitTermination(10, TimeUnit.SECONDS));
        writerPool.shutdown();

        assertEquals(0, failures.get(), "no reader should observe a missing report");
        assertEquals(0, malformed.get(), "no reader should observe a malformed report");
        assertTrue(observedSnapshotIds.size() >= 2,
                "expected to observe multiple distinct snapshot versions, got "
                        + observedSnapshotIds);
    }

    @Test
    void concurrentAnalysesDoNotCorruptState(@TempDir Path dir) throws Exception {
        var service = TestServices.create(dir);
        int count = 10;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < count; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    AnalysisReport report = service.analyzeSnapshots(
                            project("base").reader(), project("p" + index).reader(),
                            AnalysisOptions.defaults());
                    ids.add(report.reportId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(count, ids.size());
        assertNotNull(service.latestReport());
    }
}
