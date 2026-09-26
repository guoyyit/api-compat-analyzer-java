package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.increment.SnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceParser;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates full and incremental compatibility analysis.
 *
 * <p>Concurrency model: updates (full/incremental/baseline changes) are
 * serialized through an update lock; the resulting immutable report is
 * published through an {@link AtomicReference} only after it is complete,
 * so concurrent readers always observe exactly one consistent version.
 *
 * <p>Bounds: every run executes on a bounded executor with a wall-clock
 * timeout and input-size limits. On timeout or limit violation nothing is
 * published, so no half-finished report is ever visible.
 */
@Service
public class AnalysisService {

    private final SourceParser parser;
    private final ApiDiffEngine diffEngine;
    private final IncrementalAnalyzer incrementalAnalyzer;
    private final SuppressionStore suppressionStore;
    private final AnalysisProperties properties;
    private final ExecutorService executor;

    private final AtomicReference<ApiSnapshot> baseline =
            new AtomicReference<>(ApiSnapshot.empty("none"));
    private final AtomicReference<ApiSnapshot> current =
            new AtomicReference<>(ApiSnapshot.empty("none"));
    private final AtomicReference<CompatReport> report = new AtomicReference<>();
    private final AtomicLong reportSequence = new AtomicLong();

    private final ReentrantLock updateLock = new ReentrantLock();

    // Working state for incremental updates. Guarded by updateLock.
    private final Map<String, String> currentSources = new HashMap<>();
    private final Map<String, ParsedUnit> currentUnits = new HashMap<>();
    private final Map<String, String> currentHashes = new HashMap<>();
    private final Set<String> baselineSuppressions = new HashSet<>();

    public AnalysisService(SourceParser parser, ApiDiffEngine diffEngine,
                           IncrementalAnalyzer incrementalAnalyzer,
                           SuppressionStore suppressionStore,
                           AnalysisProperties properties) {
        this.parser = parser;
        this.diffEngine = diffEngine;
        this.incrementalAnalyzer = incrementalAnalyzer;
        this.suppressionStore = suppressionStore;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.getWorkerThreads()));
    }

    /** Full analysis: parses every file of the given source tree. */
    public CompatReport analyzeFull(Map<String, String> sources, String currentVersion) {
        checkBounds(sources);
        return runBounded(() -> {
            updateLock.lock();
            try {
                Map<String, ParsedUnit> units = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : sources.entrySet()) {
                    units.put(e.getKey(), parser.parse(e.getKey(), e.getValue()));
                }
                Map<String, String> hashes = hashes(sources);
                ApiSnapshot snapshot = SnapshotBuilder.build(currentVersion, units.values(), hashes);
                return publish(snapshot, units, sources, hashes);
            } finally {
                updateLock.unlock();
            }
        });
    }

    /**
     * Incremental update: re-parses only changed files and files affected by
     * them through the dependency graph; all other cached parse results are
     * reused. The result is identical to a full analysis of the new tree.
     */
    public CompatReport analyzeIncremental(Map<String, String> changedSources,
                                           Set<String> deletedFiles, String currentVersion) {
        checkBounds(changedSources);
        return runBounded(() -> {
            updateLock.lock();
            try {
                Map<String, String> newSources = new HashMap<>(currentSources);
                deletedFiles.forEach(newSources::remove);
                newSources.putAll(changedSources);
                checkBounds(newSources);
                Map<String, String> newHashes = hashes(newSources);

                Set<String> dirty = incrementalAnalyzer.dirtyFiles(current.get(), newHashes);
                Map<String, ParsedUnit> units = new LinkedHashMap<>(currentUnits);
                units.keySet().removeIf(file -> !newSources.containsKey(file));
                for (String file : dirty) {
                    String source = newSources.get(file);
                    if (source == null) {
                        units.remove(file);
                    } else {
                        units.put(file, parser.parse(file, source));
                    }
                }
                ApiSnapshot snapshot = SnapshotBuilder.build(currentVersion, units.values(), newHashes);
                return publish(snapshot, units, newSources, newHashes);
            } finally {
                updateLock.unlock();
            }
        });
    }

    /** Replaces the baseline snapshot from the given sources. */
    public void setBaseline(Map<String, String> sources, String baselineVersion) {
        checkBounds(sources);
        runBounded(() -> {
            updateLock.lock();
            try {
                Map<String, ParsedUnit> units = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : sources.entrySet()) {
                    units.put(e.getKey(), parser.parse(e.getKey(), e.getValue()));
                }
                ApiSnapshot snapshot = SnapshotBuilder.build(baselineVersion, units.values(), hashes(sources));
                baseline.set(snapshot);
                baselineSuppressions.clear();
                units.values().forEach(u -> baselineSuppressions.addAll(u.suppressedKeys()));
                refreshSuppressions(currentUnits.values());
                return null;
            } finally {
                updateLock.unlock();
            }
        });
    }

    /** Installs an already-built baseline snapshot (e.g. loaded from disk). */
    public void installBaselineSnapshot(ApiSnapshot snapshot, Set<String> suppressions) {
        updateLock.lock();
        try {
            baseline.set(snapshot);
            baselineSuppressions.clear();
            baselineSuppressions.addAll(suppressions);
            refreshSuppressions(currentUnits.values());
        } finally {
            updateLock.unlock();
        }
    }

    /** Promotes the current snapshot to be the new baseline. */
    public void promoteCurrentToBaseline() {
        updateLock.lock();
        try {
            baseline.set(current.get());
            baselineSuppressions.clear();
            currentUnits.values().forEach(u -> baselineSuppressions.addAll(u.suppressedKeys()));
            refreshSuppressions(currentUnits.values());
        } finally {
            updateLock.unlock();
        }
    }

    /** Latest published report; empty when no analysis has completed yet. */
    public Optional<CompatReport> currentReport() {
        return Optional.ofNullable(report.get());
    }

    public ApiSnapshot currentSnapshot() {
        return current.get();
    }

    public ApiSnapshot baselineSnapshot() {
        return baseline.get();
    }

    /** Exemption keys currently attached to the baseline. */
    public Set<String> baselineSuppressions() {
        updateLock.lock();
        try {
            return Set.copyOf(baselineSuppressions);
        } finally {
            updateLock.unlock();
        }
    }

    // ------------------------------------------------------------------

    private CompatReport publish(ApiSnapshot snapshot, Map<String, ParsedUnit> units,
                                 Map<String, String> sources, Map<String, String> hashes) {
        refreshSuppressions(units.values());
        List<ApiChange> changes = diffEngine.diff(baseline.get(), snapshot, suppressionStore.snapshot());
        CompatReport newReport = new CompatReport(
                "report-" + reportSequence.incrementAndGet(),
                baseline.get().version(), snapshot.version(), Instant.now(), changes);
        // Publish the immutable snapshot first, then the report, so a reader
        // of the report never observes a half-updated pair.
        currentSources.clear();
        currentSources.putAll(sources);
        currentUnits.clear();
        currentUnits.putAll(units);
        currentHashes.clear();
currentHashes.putAll(hashes);
        current.set(snapshot);
        report.set(newReport);
        return newReport;
    }

    private void refreshSuppressions(Iterable<ParsedUnit> units) {
        Set<String> all = new HashSet<>(baselineSuppressions);
        units.forEach(u -> all.addAll(u.suppressedKeys()));
        suppressionStore.replaceAll(all);
    }

    private void checkBounds(Map<String, String> sources) {
        if (sources.size() > properties.getMaxFiles()) {
            throw new AnalysisException("Too many files: " + sources.size()
                    + " > limit " + properties.getMaxFiles());
        }
        long total = sources.values().stream()
                .mapToLong(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
        if (total > properties.getMaxTotalBytes()) {
            throw new AnalysisException("Total source size " + total
                    + " bytes exceeds limit " + properties.getMaxTotalBytes());
        }
    }

    private <T> T runBounded(Callable<T> task) {
        Future<T> future = executor.submit(task);
        try {
            return future.get(properties.getTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AnalysisException("Analysis timed out after " + properties.getTimeoutMillis()
                    + " ms; previous report (if any) is left untouched", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisException("Analysis interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AnalysisException ae) {
                throw ae;
            }
            if (cause instanceof OutOfMemoryError) {
                throw new AnalysisException("Analysis ran out of memory; no report published", cause);
            }
            throw new AnalysisException("Analysis failed: " + cause.getMessage(), cause);
        }
    }

    private static Map<String, String> hashes(Map<String, String> sources) {
        Map<String, String> hashes = new LinkedHashMap<>();
        sources.forEach((file, content) -> hashes.put(file, sha256(content)));
        return hashes;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
