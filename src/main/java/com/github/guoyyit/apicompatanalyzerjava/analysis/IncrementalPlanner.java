package com.github.guoyyit.apicompatanalyzerjava.analysis;

import java.util.Map;
import java.util.Set;

/**
 * Decides which files must be re-parsed when a snapshot is updated: changed files plus
 * every file that depends on them (transitively). Files outside the answer are never
 * parsed again.
 */
public interface IncrementalPlanner {

    /**
     * @param oldHashes path -> content hash of the previous snapshot
     * @param newHashes path -> content hash of the new snapshot
     * @return paths that must be re-parsed (added, modified, deleted handling by caller)
     */
    Set<String> plan(Map<String, String> oldHashes, Map<String, String> newHashes,
                     DependencyIndex dependencyIndex);

    /** Maps a declared type FQN to the file that declares it and files to their used types. */
    interface DependencyIndex {
        /** All file paths whose compilation can observe the given changed files. */
        Set<String> reverseDependencies(Set<String> changedFiles);
    }
}
