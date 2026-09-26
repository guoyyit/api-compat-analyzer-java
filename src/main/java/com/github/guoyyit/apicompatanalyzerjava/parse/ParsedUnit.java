package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Result of parsing a single source file. */
public final class ParsedUnit {
    private final String file;
    private final List<ApiElement> apiElements;
    private final List<CallSite> callSites;
    private final Set<String> referencedTypes;
    private final Set<String> declaredTypes;
    private final Set<String> suppressedKeys;
    private final Map<String, List<String>> typeSupertypes;

    public ParsedUnit(String file, List<ApiElement> apiElements, List<CallSite> callSites,
                      Set<String> referencedTypes, Set<String> declaredTypes,
                      Set<String> suppressedKeys) {
        this(file, apiElements, callSites, referencedTypes, declaredTypes, suppressedKeys, Map.of());
    }

    public ParsedUnit(String file, List<ApiElement> apiElements, List<CallSite> callSites,
                      Set<String> referencedTypes, Set<String> declaredTypes,
                      Set<String> suppressedKeys, Map<String, List<String>> typeSupertypes) {
        this.file = file;
        this.apiElements = List.copyOf(apiElements);
        this.callSites = List.copyOf(callSites);
        this.referencedTypes = Set.copyOf(referencedTypes);
        this.declaredTypes = Set.copyOf(declaredTypes);
        this.suppressedKeys = Set.copyOf(suppressedKeys);
        this.typeSupertypes = Map.copyOf(typeSupertypes);
    }

    public String file() { return file; }
    public List<ApiElement> apiElements() { return apiElements; }
    public List<CallSite> callSites() { return callSites; }
    public Set<String> referencedTypes() { return referencedTypes; }
    public Set<String> declaredTypes() { return declaredTypes; }
    /** Declared type fqn -> resolved supertype names (extends/implements). */
    public Map<String, List<String>> typeSupertypes() { return typeSupertypes; }
    public Set<String> suppressedKeys() { return suppressedKeys; }
}
