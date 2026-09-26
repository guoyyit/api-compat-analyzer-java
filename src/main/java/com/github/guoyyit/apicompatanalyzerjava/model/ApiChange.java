package com.github.guoyyit.apicompatanalyzerjava.model;

/**
 * A single API change between two snapshots.
 *
 * @param nature         added / removed / signature changed
 * @param verdict        breaking or compatible
 * @param elementId      stable identity of the affected element
 * @param oldElement     element in the baseline, {@code null} for ADDED
 * @param newElement     element in the current snapshot, {@code null} for REMOVED
 * @param reason         human readable explanation of the verdict
 * @param suppressed     whether the change is covered by an exemption
 * @param suppressReason exemption reason when suppressed
 */
public record ApiChange(
        ChangeNature nature,
        CompatVerdict verdict,
        String elementId,
        ApiElement oldElement,
        ApiElement newElement,
        String reason,
        boolean suppressed,
        String suppressReason
) {
}
