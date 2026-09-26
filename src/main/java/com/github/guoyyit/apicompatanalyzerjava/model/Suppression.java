package com.github.guoyyit.apicompatanalyzerjava.model;

/**
 * An exemption declaration found in source, persisted with baselines so that later
 * version comparisons keep honoring it.
 */
public record Suppression(
        String elementId,
        String path,
        int line,
        String reason,
        String changeSelector
) {
}
