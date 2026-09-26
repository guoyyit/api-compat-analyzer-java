package com.github.guoyyit.apicompatanalyzerjava.model;

import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fully parsed, immutable model of one source snapshot.
 *
 * @param snapshotId   deterministic content hash (paths + file contents + parser version)
 * @param fileHashes   relative path to per-file content hash
 * @param units        relative path to parsed unit (every {@code .java} file)
 * @param elementsById all public/protected API elements keyed by stable id
 * @param callSites    every reference found in the snapshot
 * @param subtypeGraph type FQN to its direct super types (extends/implements)
 * @param suppressions exemptions declared in this snapshot
 * @param sourceFiles  all input file paths (including files that declare no API)
 */
public record SnapshotModel(
        String snapshotId,
        Map<String, String> fileHashes,
        Map<String, ParsedUnit> units,
        Map<String, ApiElement> elementsById,
        List<CallSite> callSites,
        Map<String, Set<String>> subtypeGraph,
        List<Suppression> suppressions,
        List<String> sourceFiles
) {
}
