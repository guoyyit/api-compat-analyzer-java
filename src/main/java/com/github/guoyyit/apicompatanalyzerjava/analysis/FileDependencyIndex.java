package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * File level dependency index derived from a parsed snapshot.
 *
 * <p>A file A depends on file B when A uses a type that B declares. The index is built
 * per snapshot and used by the incremental planner to compute the transitive closure of
 * files affected by a content change.
 */
public final class FileDependencyIndex implements IncrementalPlanner.DependencyIndex {

    /** file -> files it depends on (uses a type declared by them) */
    private final Map<String, Set<String>> forward = new HashMap<>();
    /** file -> files that depend on it */
    private final Map<String, Set<String>> reverse = new HashMap<>();

    private FileDependencyIndex() {
    }

    public static FileDependencyIndex from(SnapshotModel snapshot) {
        FileDependencyIndex index = new FileDependencyIndex();
        Map<String, String> typeToFile = new HashMap<>();
        for (ParsedUnit unit : snapshot.units().values()) {
            for (String declared : unit.declaredTypes()) {
                typeToFile.put(declared, unit.path());
            }
        }
        for (ParsedUnit unit : snapshot.units().values()) {
            for (String used : unit.usedTypes()) {
                String target = typeToFile.get(used);
                if (target != null && !target.equals(unit.path())) {
                    index.forward.computeIfAbsent(unit.path(), k -> new HashSet<>()).add(target);
                    index.reverse.computeIfAbsent(target, k -> new HashSet<>()).add(unit.path());
                }
            }
            // Wildcard imports (com.acme.*) cannot be resolved exactly: conservatively
            // depend on every file in that package so no affected caller is missed.
            for (ParsedUnit.ImportEntry imp : unit.imports()) {
                if (imp.onDemand() && !imp.isStatic()) {
                    String pkg = imp.qualifiedName();
                    for (Map.Entry<String, String> mapping : typeToFile.entrySet()) {
                        String fqn = mapping.getKey();
                        int dot = fqn.lastIndexOf('.');
                        if (dot > 0 && fqn.substring(0, dot).equals(pkg)
                                && !mapping.getValue().equals(unit.path())) {
                            index.forward.computeIfAbsent(unit.path(), k -> new HashSet<>())
                                    .add(mapping.getValue());
                            index.reverse.computeIfAbsent(mapping.getValue(), k -> new HashSet<>())
                                    .add(unit.path());
                        }
                    }
                }
            }
        }
        return index;
    }

    @Override
    public Set<String> reverseDependencies(Set<String> changedFiles) {
        // Transitive fixed point: callers of changed files, callers of those callers, ...
        Set<String> result = new TreeSet<>(changedFiles);
        Set<String> frontier = new HashSet<>(changedFiles);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String file : frontier) {
                for (String dependent : reverse.getOrDefault(file, Set.of())) {
                    if (result.add(dependent)) {
                        next.add(dependent);
                    }
                }
            }
            frontier = next;
        }
        return result;
    }
}
