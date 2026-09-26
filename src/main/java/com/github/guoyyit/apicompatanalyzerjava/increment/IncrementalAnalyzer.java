package com.github.guoyyit.apicompatanalyzerjava.increment;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Computes the minimal set of files that must be re-parsed when the source
 * tree changes: the changed files themselves plus every file that directly
 * or transitively depends on them. Unchanged, non-dependent files keep their
 * cached parse results and are never re-parsed.
 */
@Component
public class IncrementalAnalyzer {

    /**
     * @param previous  the last published snapshot (holds hashes and dependency graph)
     * @param newHashes file -> content hash of the new source tree
     * @return files that must be (re-)parsed to bring the snapshot up to date
     */
    public Set<String> dirtyFiles(ApiSnapshot previous, Map<String, String> newHashes) {
        Map<String, String> oldHashes = previous.fileHashes();
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : newHashes.entrySet()) {
            if (!e.getValue().equals(oldHashes.get(e.getKey()))) {
                changed.add(e.getKey()); // added or modified
            }
        }
        for (String oldFile : oldHashes.keySet()) {
            if (!newHashes.containsKey(oldFile)) {
                changed.add(oldFile); // removed
            }
        }

        // Reverse dependency graph: file -> files that depend on it.
        Map<String, Set<String>> dependents = new HashMap<>();
        previous.fileDependencies().forEach((file, deps) -> {
            for (String dep : deps) {
                dependents.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(file);
            }
        });

        Set<String> dirty = new LinkedHashSet<>(changed);
        Deque<String> queue = new ArrayDeque<>(changed);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String dependent : dependents.getOrDefault(current, Set.of())) {
                if (dirty.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        // Only files present in the new tree can be re-parsed.
        dirty.retainAll(newHashes.keySet());
        return dirty;
    }
}
