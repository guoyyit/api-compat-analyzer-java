package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;

import java.util.List;

/** Computes added / removed / signature-changed API elements and their compatibility verdict. */
public interface ApiDiffEngine {

    List<ApiChange> diff(SnapshotModel baseline, SnapshotModel current);
}
