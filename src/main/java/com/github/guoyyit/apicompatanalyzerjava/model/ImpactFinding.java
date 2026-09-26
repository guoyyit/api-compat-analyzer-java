package com.github.guoyyit.apicompatanalyzerjava.model;

import java.util.List;

/** A breaking change together with the concrete call sites it affects. */
public record ImpactFinding(
        String elementId,
        String reason,
        List<CallSite> callSites
) {
}
