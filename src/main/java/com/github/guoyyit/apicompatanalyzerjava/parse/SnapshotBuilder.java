package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;

import java.util.Set;

/**
 * Turns source files into a fully parsed, content addressed snapshot.
 *
 * <p>Full and incremental builds go through the same aggregation, so their results are
 * byte-identical for identical inputs.
 */
public interface SnapshotBuilder {

    /** Parse every file provided by the reader. */
    SnapshotModel build(SourceFileReader reader, ResourceBudget budget);

    /**
     * Incremental build: reuse unchanged units from {@code previous}, only re-parse the
     * files listed in {@code changedPaths} (which includes added files). Files present in
     * the reader but not in {@code previous} are treated as added; files missing from the
     * reader are deleted.
     */
    SnapshotModel buildIncremental(SourceFileReader reader, ResourceBudget budget,
                                   SnapshotModel previous, Set<String> changedPaths);
}
