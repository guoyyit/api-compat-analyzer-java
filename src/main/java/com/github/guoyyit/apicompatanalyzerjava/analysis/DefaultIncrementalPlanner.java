package com.github.guoyyit.apicompatanalyzerjava.analysis;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Default {@link IncrementalPlanner}.
 *
 * <p>Changed paths = added + modified + deleted (by content hash). The answer is the
 * transitive reverse-dependency closure of added/modified files over the dependency
 * index. Deleted files appear in the closure as well so downstream consumers can drop
 * their cached parse results.
 */
@Component
public class DefaultIncrementalPlanner implements IncrementalPlanner {

    @Override
    public Set<String> plan(Map<String, String> oldHashes,
                            Map<String, String> newHashes,
                            DependencyIndex dependencyIndex) {
        Set<String> addedOrModified = new TreeSet<>();
        Set<String> deleted = new TreeSet<>();

        for (Map.Entry<String, String> entry : newHashes.entrySet()) {
            String path = entry.getKey();
            String oldHash = oldHashes.get(path);
            if (oldHash == null || !oldHash.equals(entry.getValue())) {
                addedOrModified.add(path);
            }
        }
        for (String path : oldHashes.keySet()) {
            if (!newHashes.containsKey(path)) {
                deleted.add(path);
            }
        }

        Set<String> closure = new HashSet<>(
                dependencyIndex.reverseDependencies(addedOrModified));
        closure.addAll(deleted);
        return new TreeSet<>(closure);
    }
}
