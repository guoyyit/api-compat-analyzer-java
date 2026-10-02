package com.github.guoyyit.apicompatanalyzerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.time.Instant;
import java.util.List;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
/**
 * Immutable analysis report. Readers always observe one complete
 * version; reports are published atomically, never mutated in place.
 *
 * <p>Reports are fully deterministic: {@code reportVersion} is derived from
 * the complete baseline and current input contents, and {@code generatedAt}
 * carries no wall-clock information (it is fixed to {@link #DETERMINISTIC_EPOCH})
 * so the same inputs always serialize to byte-identical reports.
 */
public final class CompatReport {
    /** Fixed, content-independent timestamp used for every deterministic report. */
    public static final Instant DETERMINISTIC_EPOCH = Instant.EPOCH;

    private final String reportVersion;
    private final String baselineVersion;
    private final String currentVersion;
    private final Instant generatedAt;
    private final List<ApiChange> changes;

    public CompatReport(String reportVersion, String baselineVersion, String currentVersion,
                        Instant generatedAt, List<ApiChange> changes) {
        this.reportVersion = reportVersion;
        this.baselineVersion = baselineVersion;
        this.currentVersion = currentVersion;
        this.generatedAt = generatedAt == null ? DETERMINISTIC_EPOCH : generatedAt;
        this.changes = List.copyOf(changes);
    }

    public String reportVersion() { return reportVersion; }
    public String baselineVersion() { return baselineVersion; }
    public String currentVersion() { return currentVersion; }
    public Instant generatedAt() { return generatedAt; }
    public List<ApiChange> changes() { return changes; }

    /** Breaking changes that are not covered by an exemption. */
    public long breakingCount() {
        return changes.stream().filter(c -> c.breaking() && !c.suppressed()).count();
    }

    public long compatibleCount() {
        return changes.stream().filter(c -> !c.breaking()).count();
    }
}
