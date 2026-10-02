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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.TreeMap;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Orchestrates full and incremental compatibility analysis.
 *
 * <p>Concurrency model: updates (full/incremental/baseline changes) are
 * serialized through an update lock; the resulting immutable report is
 * published together with its snapshot and cached working state as one
 * immutable state object through an {@link AtomicReference}, only after the
 * analysis is complete. Concurrent readers therefore always observe exactly
 * one fully published (report + version info) pair and never a generation in
 * progress.
 *
 * <p>Determinism: reports contain no wall-clock or process-state data. The
 * report id is a hash of the complete baseline and current input contents
 * (per-file content hashes plus version labels); identical inputs produce
 * identical reports on every run and after a service restart.
 *
 * <p>Bounds: every run executes on a bounded executor with a wall-clock
 * timeout and input-size limits. On timeout or limit violation the state
 * object is not replaced, so no partial result is published and the previous
 * report stays intact and readable.
 */
@Service
public class AnalysisService {

    private final SourceParser parser;
    private final ApiDiffEngine diffEngine;
    private final IncrementalAnalyzer incrementalAnalyzer;
    private final SuppressionStore suppressionStore;
    private final AnalysisProperties properties;
    private final ExecutorService executor;

    /**
     * Immutable published state: baseline snapshot, current snapshot, the
     * report comparing them and the cached working data of the current tree.
     * Replaced atomically as a whole on every successful update.
     */
    private record AnalysisState(ApiSnapshot baselineSnapshot, Set<String> baselineSuppressions,
                                 ApiSnapshot currentSnapshot, CompatReport report,
                                 Map<String, String> currentSources,
                                 Map<String, ParsedUnit> currentUnits,
                                 Map<String, String> currentHashes) {
        AnalysisState {
            baselineSuppressions = Set.copyOf(baselineSuppressions);
            currentSources = Map.copyOf(currentSources);
            currentUnits = Map.copyOf(currentUnits);
            currentHashes = Map.copyOf(currentHashes);
        }

        static AnalysisState initial() {
            return new AnalysisState(ApiSnapshot.empty("none"), Set.of(),
                    ApiSnapshot.empty("none"), null, Map.of(), Map.of(), Map.of());
        }
    }

    private final AtomicReference<AnalysisState> state =
            new AtomicReference<>(AnalysisState.initial());

    private final ReentrantLock updateLock = new ReentrantLock();

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
                AnalysisState st = state.get();
                Map<String, ParsedUnit> units = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : new TreeMap<>(sources).entrySet()) {
                    units.put(e.getKey(), parser.parse(e.getKey(), e.getValue()));
                }
                Map<String, String> hashes = hashes(sources);
                ApiSnapshot snapshot = SnapshotBuilder.build(currentVersion, units.values(), hashes);
                return publish(st, snapshot, sources, units, hashes);
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
                AnalysisState st = state.get();
                Map<String, String> newSources = new LinkedHashMap<>(st.currentSources());
                deletedFiles.forEach(newSources::remove);
                newSources.putAll(changedSources);
                checkBounds(newSources);
                Map<String, String> newHashes = hashes(newSources);

                Set<String> dirty = incrementalAnalyzer.dirtyFiles(st.currentSnapshot(), newHashes);
                Map<String, ParsedUnit> units = new LinkedHashMap<>(st.currentUnits());
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
                return publish(st, snapshot, newSources, units, newHashes);
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
                for (Map.Entry<String, String> e : new TreeMap<>(sources).entrySet()) {
                    units.put(e.getKey(), parser.parse(e.getKey(), e.getValue()));
                }
                ApiSnapshot snapshot = SnapshotBuilder.build(baselineVersion, units.values(), hashes(sources));
                Set<String> suppressions = new HashSet<>();
                units.values().forEach(u -> suppressions.addAll(u.suppressedKeys()));
                AnalysisState st = state.get();
                refreshSuppressions(suppressions, st.currentUnits().values());
                state.set(new AnalysisState(snapshot, suppressions, st.currentSnapshot(), st.report(),
                        st.currentSources(), st.currentUnits(), st.currentHashes()));
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
            AnalysisState st = state.get();
            refreshSuppressions(suppressions, st.currentUnits().values());
            state.set(new AnalysisState(snapshot, suppressions, st.currentSnapshot(), st.report(),
                    st.currentSources(), st.currentUnits(), st.currentHashes()));
        } finally {
            updateLock.unlock();
        }
    }

    /** Promotes the current snapshot to be the new baseline. */
    public void promoteCurrentToBaseline() {
        updateLock.lock();
        try {
            AnalysisState st = state.get();
            Set<String> suppressions = new HashSet<>(st.baselineSuppressions());
            st.currentUnits().values().forEach(u -> suppressions.addAll(u.suppressedKeys()));
            refreshSuppressions(suppressions, st.currentUnits().values());
            state.set(new AnalysisState(st.currentSnapshot(), suppressions, st.currentSnapshot(),
                    st.report(), st.currentSources(), st.currentUnits(), st.currentHashes()));
        } finally {
            updateLock.unlock();
        }
    }

    /** Latest published report; empty when no analysis has completed yet. */
    public Optional<CompatReport> currentReport() {
        return Optional.ofNullable(state.get().report());
    }

    public ApiSnapshot currentSnapshot() {
        return state.get().currentSnapshot();
    }

    public ApiSnapshot baselineSnapshot() {
        return state.get().baselineSnapshot();
    }

    /** Exemption keys currently attached to the baseline. */
    public Set<String> baselineSuppressions() {
        updateLock.lock();
        try {
            return state.get().baselineSuppressions();
        } finally {
            updateLock.unlock();
        }
    }

    // ------------------------------------------------------------------

    private CompatReport publish(AnalysisState st, ApiSnapshot snapshot, Map<String, String> sources,
                                 Map<String, ParsedUnit> units, Map<String, String> hashes) {
        refreshSuppressions(st.baselineSuppressions(), units.values());
        List<ApiChange> changes = diffEngine.diff(st.baselineSnapshot(), snapshot, suppressionStore.snapshot());
        String reportId = deterministicReportId(st.baselineSnapshot(), snapshot);
        CompatReport newReport = new CompatReport(
                reportId, st.baselineSnapshot().version(), snapshot.version(), changes);
        // One atomic publication of the whole (snapshot + report + working
        // state) pair: a reader can only observe the previous or the next
        // complete generation, never an intermediate one. On any failure
        // before this line the previous state stays untouched.
        AnalysisState next = new AnalysisState(st.baselineSnapshot(), st.baselineSuppressions(),
                snapshot, newReport, sources, units, hashes);
        state.set(next);
        return newReport;
    }

    private void refreshSuppressions(Set<String> baselineSuppressionKeys, Iterable<ParsedUnit> units) {
        Set<String> all = new HashSet<>(baselineSuppressionKeys);
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
        new TreeMap<>(sources).forEach((file, content) -> hashes.put(file, sha256(content)));
        return hashes;
    }

    /**
     * Deterministic report id derived solely from the complete contents of the
     * baseline and current inputs: per-file SHA-256 content hashes (sorted by
     * file path) together with the two version labels. Any change to any input
     * file or version changes the id; the same inputs after a restart yield
     * the same id.
     */
    static String deterministicReportId(ApiSnapshot baselineSnapshot, ApiSnapshot currentSnapshot) {
        String canonical = "baseline:" + baselineSnapshot.version() + "\n"
                + canonicalHashes(baselineSnapshot.fileHashes())
                + "current:" + currentSnapshot.version() + "\n"
                + canonicalHashes(currentSnapshot.fileHashes());
        return "report-" + sha256(canonical);
    }

    private static String canonicalHashes(Map<String, String> fileHashes) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(fileHashes).forEach((file, hash) ->
                sb.append(file).append('\t').append(hash).append('\n'));
        return sb.toString();
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
