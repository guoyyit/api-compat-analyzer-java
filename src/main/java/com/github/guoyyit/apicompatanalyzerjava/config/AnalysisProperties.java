package com.github.guoyyit.apicompatanalyzerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Time and resource bounds for analysis runs. */
@Component
@ConfigurationProperties(prefix = "analysis")
public class AnalysisProperties {
    /** Wall-clock timeout for a single analysis run, milliseconds. */
    private long timeoutMillis = 30_000;
    /** Maximum number of source files accepted per run. */
    private int maxFiles = 10_000;
    /** Maximum total source size in bytes accepted per run. */
    private long maxTotalBytes = 50L * 1024 * 1024;
    /** Worker threads used to execute analysis runs. */
    private int workerThreads = 2;

    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long timeoutMillis) { this.timeoutMillis = timeoutMillis; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public long getMaxTotalBytes() { return maxTotalBytes; }
    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    public void setMaxTotalBytes(long maxTotalBytes) { this.maxTotalBytes = maxTotalBytes; }
}
