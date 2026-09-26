package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ImpactFinding;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;

import java.util.List;

/**
 * Follows reference relationships from breaking changes to concrete call sites in the
 * current snapshot.
 */
public interface ImpactResolver {

    List<ImpactFinding> resolve(List<ApiChange> breakingChanges, SnapshotModel current);

    /** Locate every call site that may dispatch to the given API element. */
    List<CallSite> findCallSites(SnapshotModel current, String elementId);
}
