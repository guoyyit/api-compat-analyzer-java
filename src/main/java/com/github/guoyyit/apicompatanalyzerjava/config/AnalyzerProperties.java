package com.github.guoyyit.apicompatanalyzerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration knobs ({@code analyzer.*}) for time and memory boundaries and storage. */
@ConfigurationProperties(prefix = "analyzer")
public class AnalyzerProperties {

    private long timeoutMillis = 60_000L;
    private int maxFiles = 10_000;
    private long maxFileBytes = 2L * 1024 * 1024;
    private long maxMemoryMb = 512L;
    private String baselineDir = "./.api-compat/baselines";

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public long getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(long maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public String getBaselineDir() {
        return baselineDir;
    }

    public void setBaselineDir(String baselineDir) {
        this.baselineDir = baselineDir;
    }
}
