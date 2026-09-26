package com.github.guoyyit.apicompatanalyzerjava.suppress;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds exemption declarations collected from source annotations
 * and from a persisted baseline file. Thread-safe.
 */
@Component
public class SuppressionStore {

    private final Set<String> suppressedKeys = ConcurrentHashMap.newKeySet();

    public boolean isSuppressed(String elementKey) {
        return suppressedKeys.contains(elementKey);
    }

    public void addAll(Set<String> keys) {
        suppressedKeys.addAll(keys);
    }

    public void replaceAll(Set<String> keys) {
        suppressedKeys.clear();
        suppressedKeys.addAll(keys);
    }

    public Set<String> snapshot() {
        return Set.copyOf(suppressedKeys);
    }
}
