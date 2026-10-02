package com.github.guoyyit.apicompatanalyzerjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable view of the public API of a source tree plus the
 * reference index and per-file hashes needed for incremental updates.
 */
public final class ApiSnapshot {
    private final String version;
    private final Map<String, ApiElement> elements;
    private final Map<String, java.util.List<CallSite>> referencesByKey;
    private final Map<String, String> fileHashes;
    private final Map<String, java.util.Set<String>> fileDependencies;
    private final Map<String, TypeRelation> typeHierarchy;

    public ApiSnapshot(String version,
                       Map<String, ApiElement> elements,
                       Map<String, java.util.List<CallSite>> referencesByKey,
                       Map<String, String> fileHashes,
                       Map<String, java.util.Set<String>> fileDependencies) {
        this(version, elements, referencesByKey, fileHashes, fileDependencies, Map.of());
    }

    public ApiSnapshot(String version,
                       Map<String, ApiElement> elements,
                       Map<String, java.util.List<CallSite>> referencesByKey,
                       Map<String, String> fileHashes,
                       Map<String, java.util.Set<String>> fileDependencies,
                       Map<String, TypeRelation> typeHierarchy) {
        this.version = version;
        this.elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
        this.referencesByKey = deepCopyRefs(referencesByKey);
        this.fileHashes = Collections.unmodifiableMap(new LinkedHashMap<>(fileHashes));
        this.fileDependencies = deepCopyDeps(fileDependencies);
        this.typeHierarchy = Collections.unmodifiableMap(new LinkedHashMap<>(typeHierarchy));
    }

    private static Map<String, java.util.List<CallSite>> deepCopyRefs(Map<String, java.util.List<CallSite>> in) {
        Map<String, java.util.List<CallSite>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, java.util.Set<String>> deepCopyDeps(Map<String, java.util.Set<String>> in) {
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }

    public static ApiSnapshot empty(String version) {
        return new ApiSnapshot(version, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public String version() { return version; }
    public Map<String, ApiElement> elements() { return elements; }
    public Map<String, java.util.List<CallSite>> referencesByKey() { return referencesByKey; }
    public Map<String, String> fileHashes() { return fileHashes; }
    public Map<String, java.util.Set<String>> fileDependencies() { return fileDependencies; }
    /** Declared type fqn -> direct superclass/interface relation; empty map for old persisted baselines. */
    public Map<String, TypeRelation> typeHierarchy() { return typeHierarchy; }
}
