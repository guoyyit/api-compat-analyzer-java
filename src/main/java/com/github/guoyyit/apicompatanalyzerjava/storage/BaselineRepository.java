package com.github.guoyyit.apicompatanalyzerjava.storage;

import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;

/** Persists a baseline snapshot (including exemptions) for later version comparison. */
public interface BaselineRepository {

    void save(String baselineId, SnapshotModel snapshot);

    SnapshotModel load(String baselineId);

    boolean exists(String baselineId);

    void delete(String baselineId);
}
