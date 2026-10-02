package com.github.guoyyit.apicompatanalyzerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
/**
 * Immutable analysis report. Readers always observe one complete
 * version; reports are published atomically, never mutated in place.
 *
 * <p>A report is fully deterministic: the {@code reportVersion} is derived
 * only from the complete baseline and current inputs, and the report carries
 * no wall-clock or process-state fields. The same inputs always produce a
 * byte-for-byte identical report, including after a service restart.
 */
public final class CompatReport {
    private final String reportVersion;
    private final String baselineVersion;
    private final String currentVersion;
    private final List<ApiChange> changes;

    public CompatReport(String reportVersion, String baselineVersion, String currentVersion,
                        List<ApiChange> changes) {
        this.reportVersion = reportVersion;
        this.baselineVersion = baselineVersion;
        this.currentVersion = currentVersion;
        this.changes = List.copyOf(changes);
    }

    public String reportVersion() { return reportVersion; }
    public String baselineVersion() { return baselineVersion; }
    public String currentVersion() { return currentVersion; }
    public List<ApiChange> changes() { return changes; }

    /** Breaking changes that are not covered by an exemption. */
    public long breakingCount() {
        return changes.stream().filter(c -> c.breaking() && !c.suppressed()).count();
    }

    public long compatibleCount() {
        return changes.stream().filter(c -> !c.breaking()).count();
    }
}
