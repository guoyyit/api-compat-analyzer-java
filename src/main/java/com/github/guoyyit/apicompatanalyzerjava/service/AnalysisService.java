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
import com.github.guoyyit.apicompatanalyzerjava.util.Fingerprints;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates full and incremental compatibility analysis.
 *
 * <p>Concurrency model: updates (full/incremental/baseline changes) are
 * serialized through an update lock; the resulting immutable report and its
 * matching snapshot are swapped in together through a single
 * {@link AtomicReference} only after all work is complete, so concurrent
 * readers always observe exactly one complete, self-consistent version.
 *
 * <p>Bounds: every run executes on a bounded executor with a wall-clock
 * timeout and input-size limits. On timeout or limit violation nothing is
 * published, so no half-finished report is ever visible.
 *
 * <p>Determinism: reports carry no clock or process state. The report id is a
 * digest of the full baseline and current input contents; identical inputs
 * (even after a restart or a failed attempt) always produce an identical
 * report, including its id and timestamp fields.
 */
@Service
public class AnalysisService {

    private final SourceParser parser;
    private final ApiDiffEngine diffEngine;
    private final IncrementalAnalyzer incrementalAnalyzer;
    private final SuppressionStore suppressionStore;
    private final AnalysisProperties properties;
    private final ExecutorService executor;

    /** Immutable baseline state: snapshot, exemption keys and content fingerprint. */
    private record BaselineState(ApiSnapshot snapshot, Set<String> suppressions, String fingerprint) {
    }

    /** Immutable pair of the published snapshot and the report describing it. */
    private record PublishedState(ApiSnapshot current, CompatReport report,
                                  Map<String, String> sources, Map<String, ParsedUnit> units,
                                  Map<String, String> hashes) {
        private static final PublishedState INITIAL =
                new PublishedState(ApiSnapshot.empty("none"), null, Map.of(), Map.of(), Map.of());
    }

    private final ReentrantLock updateLock = new ReentrantLock();
    private BaselineState baseline;
    private final AtomicReference<PublishedState> published =
            new AtomicReference<>(PublishedState.INITIAL);

    // Working state for incremental updates. Guarded by updateLock; published
    // values live in {@link #published} and are swapped atomically.
    private final Map<String, String> currentSources = new HashMap<>();
    private final Map<String, ParsedUnit> currentUnits = new HashMap<>();
    private final Map<String, String> currentHashes = new HashMap<>();

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
        this.baseline = new BaselineState(ApiSnapshot.empty("none"), Set.of(),
                Fingerprints.ofInputs("none", Map.of()));
    }

    /** Full analysis: parses every file of the given source tree. */
    public CompatReport analyzeFull(Map<String, String> sources, String currentVersion) {
        checkBounds(sources);
        return runBounded(() -> {
            updateLock.lock();
            try {
                Map<String, ParsedUnit> units = parseAll(sources);
                Map<String, String> hashes = hashes(sources);
                ApiSnapshot snapshot = SnapshotBuilder.build(currentVersion, units.values(), hashes);
                String currentFingerprint = Fingerprints.ofInputs(currentVersion, sources);
                return commit(snapshot, units, sources, hashes, currentFingerprint);
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

                Set<String> dirty = incrementalAnalyzer.dirtyFiles(
                        published.get().current(), newHashes);
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
                String currentFingerprint = Fingerprints.ofInputs(currentVersion, newSources);
                return commit(snapshot, units, newSources, newHashes, currentFingerprint);
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
                Map<String, ParsedUnit> units = parseAll(sources);
                ApiSnapshot snapshot = SnapshotBuilder.build(
                        baselineVersion, units.values(), hashes(sources));
                Set<String> suppressions = collectSuppressions(units.values());
                baseline = new BaselineState(snapshot, suppressions,
                        Fingerprints.ofInputs(baselineVersion, sources));
                refreshSuppressions(published.get().units().values());
                return null;
            } finally {
                updateLock.unlock();
            }
        });
    }

    /** Installs an already-built baseline snapshot (e.g. loaded from disk). */
    public void installBaselineSnapshot(ApiSnapshot snapshot, Set<String> suppressions) {
        installBaselineSnapshot(snapshot, suppressions, "");
    }

    /** Installs a baseline loaded from disk together with its stored fingerprint. */
    public void installBaselineSnapshot(ApiSnapshot snapshot, Set<String> suppressions,
                                        String storedFingerprint) {
        updateLock.lock();
        try {
            Set<String> copy = Set.copyOf(suppressions);
            String fingerprint = storedFingerprint != null && !storedFingerprint.isEmpty()
                    ? storedFingerprint : Fingerprints.ofSnapshot(snapshot);
            baseline = new BaselineState(snapshot, copy, fingerprint);
            refreshSuppressions(currentUnits.values());
        } finally {
            updateLock.unlock();
        }
    }

    /** Promotes the current snapshot to be the new baseline. */
    public void promoteCurrentToBaseline() {
        updateLock.lock();
        try {
            PublishedState state = published.get();
            Set<String> suppressions = collectSuppressions(state.units().values());
            String fingerprint = Fingerprints.ofInputs(state.current().version(), state.sources());
            baseline = new BaselineState(state.current(), suppressions, fingerprint);
            refreshSuppressions(currentUnits.values());
        } finally {
            updateLock.unlock();
        }
    }

    /** Latest published report; empty when no analysis has completed yet. */
    public Optional<CompatReport> currentReport() {
        return Optional.ofNullable(published.get().report());
    }

    public ApiSnapshot currentSnapshot() {
        return published.get().current();
    }

    public ApiSnapshot baselineSnapshot() {
        return baseline.snapshot();
    }

    /** Deterministic content fingerprint of the installed baseline. */
    public String baselineFingerprint() {
        updateLock.lock();
        try {
            return baseline.fingerprint();
        } finally {
            updateLock.unlock();
        }
    }

    /** Exemption keys currently attached to the baseline. */
    public Set<String> baselineSuppressions() {
        updateLock.lock();
        try {
            return baseline.suppressions();
        } finally {
            updateLock.unlock();
        }
    }

    // ------------------------------------------------------------------

    private Map<String, ParsedUnit> parseAll(Map<String, String> sources) {
        Map<String, ParsedUnit> units = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(sources).entrySet()) {
            units.put(e.getKey(), parser.parse(e.getKey(), e.getValue()));
        }
        return units;
    }

    private CompatReport commit(ApiSnapshot snapshot, Map<String, ParsedUnit> units,
                                Map<String, String> sources, Map<String, String> hashes,
                                String currentFingerprint) {
        BaselineState base = baseline;
        refreshSuppressions(units.values());
        List<ApiChange> changes = diffEngine.diff(base.snapshot(), snapshot, suppressionStore.snapshot());
        String reportId = "report-" + Fingerprints.combine(base.fingerprint(), currentFingerprint);
        CompatReport newReport = new CompatReport(
                reportId, base.snapshot().version(), snapshot.version(),
                CompatReport.DETERMINISTIC_EPOCH, changes);

        // Build the complete next state first, then publish it in one atomic
        // swap. A reader can never observe a snapshot without its matching
        // report (or vice versa), and a failure before this point changes
        // nothing that a reader can see.
        Map<String, String> nextSources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        Map<String, ParsedUnit> nextUnits = Collections.unmodifiableMap(new LinkedHashMap<>(units));
        Map<String, String> nextHashes = Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
        PublishedState next = new PublishedState(snapshot, newReport,
                nextSources, nextUnits, nextHashes);

        currentSources.clear();
        currentSources.putAll(sources);
        currentUnits.clear();
        currentUnits.putAll(units);
        currentHashes.clear();
        currentHashes.putAll(hashes);

        if (Thread.currentThread().isInterrupted()) {
            throw new AnalysisException("Analysis interrupted before publication");
        }
        published.set(next);
        return newReport;
    }

    private void refreshSuppressions(Iterable<ParsedUnit> units) {
        Set<String> all = new HashSet<>(baseline.suppressions());
        units.forEach(u -> all.addAll(u.suppressedKeys()));
        suppressionStore.replaceAll(all);
    }

    private static Set<String> collectSuppressions(Iterable<ParsedUnit> units) {
        Set<String> all = new HashSet<>();
        units.forEach(u -> all.addAll(u.suppressedKeys()));
        return Set.copyOf(all);
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
        Future<T> future = executor.submit(() -> {
            // A pooled worker may carry a stale interrupt from a previously
            // cancelled run; it must not poison this fresh attempt.
            Thread.interrupted();
            return task.call();
        });
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
        new TreeMap<>(sources).forEach((file, content) -> hashes.put(file, sha256(content)));
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
