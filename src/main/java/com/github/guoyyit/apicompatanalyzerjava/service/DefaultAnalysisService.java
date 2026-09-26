package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.analysis.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.analysis.FileDependencyIndex;
import com.github.guoyyit.apicompatanalyzerjava.analysis.ImpactResolver;
import com.github.guoyyit.apicompatanalyzerjava.analysis.IncrementalPlanner;
import com.github.guoyyit.apicompatanalyzerjava.config.AnalyzerProperties;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ImpactFinding;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.parse.AnalysisBudgetException;
import com.github.guoyyit.apicompatanalyzerjava.parse.ResourceBudget;
import com.github.guoyyit.apicompatanalyzerjava.parse.SnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFileReader;
import com.github.guoyyit.apicompatanalyzerjava.storage.BaselineRepository;
import com.github.guoyyit.apicompatanalyzerjava.storage.SnapshotJsonCodec;
import com.github.guoyyit.apicompatanalyzerjava.util.Hashes;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Default {@link AnalysisService}.
 *
 * <p>Concurrency: analyses and reads take the read lock and may overlap; updates take
 * the write lock exclusively. Published state (latest report, current snapshot) is
 * swapped atomically, so any observed report belongs to exactly one version.
 *
 * <p>Boundaries: each run executes on a worker thread under a single {@link ResourceBudget};
 * on timeout or resource exhaustion the run is aborted and the previously published
 * report remains in place — no half-finished report is ever observable.
 */
@Service
public class DefaultAnalysisService implements AnalysisService {

    private final SnapshotBuilder snapshotBuilder;
    private final ApiDiffEngine diffEngine;
    private final ImpactResolver impactResolver;
    private final IncrementalPlanner incrementalPlanner;
    private final BaselineRepository baselineRepository;
    private final AnalyzerProperties properties;
    private final SnapshotJsonCodec codec = new SnapshotJsonCodec();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ExecutorService workers = Executors.newCachedThreadPool(new NamedThreadFactory());

    /** One published version: report and snapshot swap together, never separately. */
    private record PublishedVersion(AnalysisReport report, SnapshotModel currentSnapshot,
                                    String baselineSnapshotId) {
    }

    private final AtomicReference<PublishedVersion> published = new AtomicReference<>();

    public DefaultAnalysisService(SnapshotBuilder snapshotBuilder,
                                  ApiDiffEngine diffEngine,
                                  ImpactResolver impactResolver,
                                  IncrementalPlanner incrementalPlanner,
                                  BaselineRepository baselineRepository,
                                  AnalyzerProperties properties) {
        this.snapshotBuilder = snapshotBuilder;
        this.diffEngine = diffEngine;
        this.impactResolver = impactResolver;
        this.incrementalPlanner = incrementalPlanner;
        this.baselineRepository = baselineRepository;
        this.properties = properties;
    }

    @Override
    public AnalysisReport analyzeSnapshots(SourceFileReader baselineReader,
                                           SourceFileReader currentReader,
                                           AnalysisOptions options) {
        lock.readLock().lock();
        try {
            return runWithBudget(options, budget -> {
                SnapshotModel baseline = snapshotBuilder.build(baselineReader, budget);
                SnapshotModel current = snapshotBuilder.build(currentReader, budget);
                return publish(produceReport(baseline, current, false, List.of(), List.of(), budget),
                        current, baseline.snapshotId());
            });
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public AnalysisReport analyzeAgainstBaseline(String baselineId,
                                                 SourceFileReader currentReader,
                                                 AnalysisOptions options) {
        lock.readLock().lock();
        try {
            return runWithBudget(options, budget -> {
                SnapshotModel baseline = baselineRepository.load(baselineId);
                SnapshotModel current = snapshotBuilder.build(currentReader, budget);
                return publish(produceReport(baseline, current, false, List.of(), List.of(), budget),
                        current, baseline.snapshotId());
            });
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String publishBaseline(String baselineId, SourceFileReader reader,
                                  AnalysisOptions options) {
        lock.writeLock().lock();
        try {
            return runWithBudget(options, budget -> {
                SnapshotModel snapshot = snapshotBuilder.build(reader, budget);
                baselineRepository.save(baselineId, snapshot);
                // A freshly published baseline invalidates any current-snapshot state that
                // might have been compared against a different baseline, so the next
                // incremental update starts from a clean full build.
                published.set(null);
                return snapshot.snapshotId();
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public AnalysisReport updateAndAnalyze(String baselineId,
                                           SourceFileReader newCurrentReader,
                                           AnalysisOptions options) {
        lock.writeLock().lock();
        try {
            return runWithBudget(options, budget -> {
                SnapshotModel baseline = baselineRepository.load(baselineId);
                PublishedVersion version = published.get();
                SnapshotModel previous = version == null ? null : version.currentSnapshot();
                boolean sameBaseline = version != null
                        && baseline.snapshotId().equals(version.baselineSnapshotId());

                // First version against this baseline (or a switched baseline): full build,
                // still one consistent version; never reuse another baseline's current state.
                SnapshotModel current;
                boolean incremental;
                List<String> reusedFiles;
                List<String> analyzedFiles;
                if (previous == null || !sameBaseline) {
                    current = snapshotBuilder.build(newCurrentReader, budget);
                    incremental = false;
                    analyzedFiles = List.copyOf(current.sourceFiles());
                    reusedFiles = List.of();
                } else {
                    // Probe new file set cheaply via reader listing + content hashing by parse:
                    // to know hash differences we must parse changed files; unchanged files are
                    // identified by hashing raw content before deciding the parse set.
                    Map<String, String> newHashes = com.github.guoyyit.apicompatanalyzerjava
                            .service.support.IncrementalSupport.contentHashes(newCurrentReader);
                    Set<String> changedPaths = incrementalPlanner.plan(
                            previous.fileHashes(), newHashes,
                            FileDependencyIndex.from(previous));
                    current = snapshotBuilder.buildIncremental(
                            newCurrentReader, budget, previous, changedPaths);
                    incremental = true;
                    analyzedFiles = List.copyOf(changedPaths);
                    reusedFiles = new ArrayList<>();
                    for (String path : current.sourceFiles()) {
                        if (!changedPaths.contains(path)) {
                            reusedFiles.add(path);
                        }
                    }
                }
                AnalysisReport report = produceReport(baseline, current, incremental,
                        analyzedFiles, reusedFiles, budget);
                return publish(report, current, baseline.snapshotId());
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public AnalysisReport latestReport() {
        PublishedVersion version = published.get();
        return version == null ? null : version.report();
    }

    @Override
    public SnapshotModel currentSnapshot() {
        PublishedVersion version = published.get();
        return version == null ? null : version.currentSnapshot();
    }

    private AnalysisReport publish(AnalysisReport report, SnapshotModel current,
                                   String baselineSnapshotId) {
        // Single atomic publication point: report and snapshot always describe one version.
        published.set(new PublishedVersion(report, current, baselineSnapshotId));
        return report;
    }

    private AnalysisReport produceReport(SnapshotModel baseline, SnapshotModel current,
                                         boolean incremental,
                                         List<String> analyzedFiles,
                                         List<String> reusedFiles,
                                         ResourceBudget budget) {
        budget.tick();
        List<ApiChange> changes = diffEngine.diff(baseline, current);
        budget.tick();
        List<ImpactFinding> impacts = impactResolver.resolve(changes, current);
        budget.tick();

        String fingerprint = Hashes.sha256(
                baseline.snapshotId() + "|" + current.snapshotId() + "|"
                        + codec.canonicalJson(changes) + "|" + codec.canonicalJson(impacts)
                        + "|" + codec.canonicalJson(current.suppressions()));
        return new AnalysisReport(
                fingerprint,
                baseline.snapshotId(),
                current.snapshotId(),
                incremental,
                List.copyOf(analyzedFiles),
                List.copyOf(reusedFiles),
                changes,
                impacts,
                current.suppressions(),
                budget.elapsedMillis(),
                System.currentTimeMillis());
    }

    private <T> T runWithBudget(AnalysisOptions options, BudgetedAction<T> action) {
        ResourceBudget budget = budgetFor(options, properties);
        long timeout = effectiveTimeout(options);
        Future<T> future = workers.submit(() -> action.run(budget));
        try {
            return timeout > 0 ? future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new AnalysisTimeoutException(
                    "analysis did not finish within " + timeout + " ms; no partial report published");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AnalysisBudgetException budgetException) {
                throw budgetException;
            }
            if (cause instanceof AnalysisRejectedException rejected) {
                throw rejected;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AnalysisRejectedException("analysis failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnalysisRejectedException("analysis interrupted", e);
        }
    }

    private long effectiveTimeout(AnalysisOptions options) {
        long configured = options == null ? 0 : options.timeoutMillis();
        return configured > 0 ? configured : properties.getTimeoutMillis();
    }

    static ResourceBudget budgetFor(AnalysisOptions options, AnalyzerProperties properties) {
        long timeout = options != null && options.timeoutMillis() > 0
                ? options.timeoutMillis() : properties.getTimeoutMillis();
        int maxFiles = options != null && options.maxFiles() > 0
                ? options.maxFiles() : properties.getMaxFiles();
        long maxFileBytes = options != null && options.maxFileBytes() > 0
                ? options.maxFileBytes() : properties.getMaxFileBytes();
        long maxMemoryMb = options != null && options.maxMemoryMb() > 0
                ? options.maxMemoryMb() : properties.getMaxMemoryMb();
        return new ResourceBudget(timeout, maxFiles, maxFileBytes, maxMemoryMb);
    }

    @FunctionalInterface
    private interface BudgetedAction<T> {
        T run(ResourceBudget budget);
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "api-compat-analysis-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
