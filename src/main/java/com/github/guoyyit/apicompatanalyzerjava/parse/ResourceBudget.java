package com.github.guoyyit.apicompatanalyzerjava.parse;

/**
 * Time and memory boundary for a parse/analysis run.
 *
 * <p>Every violation is raised <em>before</em> any partial result is published:
 * <ul>
 *   <li>{@link #checkFileAccepted(int)} rejects snapshots with too many files,</li>
 *   <li>{@link #checkFileSize(SourceFile)} rejects pathologically large files,</li>
 *   <li>{@link #tick()} enforces the wall clock deadline and the soft heap budget.</li>
 * </ul>
 * Zero or negative values mean "use no explicit limit" for that dimension.
 */
public final class ResourceBudget {

    private final long timeoutMillis;
    private final long deadlineNanos;
    private final int maxFiles;
    private final long maxFileBytes;
    private final long maxMemoryBytes;
    private final long startNanos;

    public ResourceBudget(long timeoutMillis, int maxFiles, long maxFileBytes, long maxMemoryMb) {
        this.startNanos = System.nanoTime();
        this.timeoutMillis = timeoutMillis;
        this.deadlineNanos = timeoutMillis > 0 ? startNanos + timeoutMillis * 1_000_000L : Long.MAX_VALUE;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
        this.maxMemoryBytes = maxMemoryMb > 0 ? maxMemoryMb * 1024 * 1024L : Long.MAX_VALUE;
    }

    public void checkFileAccepted(int fileCount) {
        if (maxFiles > 0 && fileCount > maxFiles) {
            throw new AnalysisBudgetException(
                    "snapshot contains " + fileCount + " files, limit is " + maxFiles);
        }
    }

    public void checkFileSize(SourceFile file) {
        if (maxFileBytes > 0 && file.size() > maxFileBytes) {
            throw new AnalysisBudgetException(
                    "file " + file.path() + " is " + file.size() + " bytes, limit is " + maxFileBytes);
        }
    }

    /**
     * Enforce deadline and heap budget. Cheap enough to call per parsed file and per
     * major processing step.
     */
    public void tick() {
        if (System.nanoTime() > deadlineNanos) {
            throw new AnalysisBudgetException(
                    "analysis exceeded time budget of " + timeoutMillis + " ms");
        }
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        if (used > maxMemoryBytes && maxMemoryBytes != Long.MAX_VALUE) {
            // One GC-based confirmation avoids reacting to a transient spike.
            System.gc();
            long confirmed = runtime.totalMemory() - runtime.freeMemory();
            if (confirmed > maxMemoryBytes) {
                throw new AnalysisBudgetException(
                        "analysis exceeded memory budget (used "
                                + (confirmed / 1024 / 1024) + " MB)");
            }
        }
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
