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
    private final Map<String, String> typeSuperclasses;
    private final Map<String, List<String>> typeInterfaces;
    private final Set<String> abstractTypes;

    public ParsedUnit(String file, List<ApiElement> apiElements, List<CallSite> callSites,
                      Set<String> referencedTypes, Set<String> declaredTypes,
                      Set<String> suppressedKeys) {
        this(file, apiElements, callSites, referencedTypes, declaredTypes, suppressedKeys,
                Map.of(), Map.of(), Map.of(), Set.of());
    }

    public ParsedUnit(String file, List<ApiElement> apiElements, List<CallSite> callSites,
                      Set<String> referencedTypes, Set<String> declaredTypes,
                      Set<String> suppressedKeys, Map<String, List<String>> typeSupertypes) {
        this(file, apiElements, callSites, referencedTypes, declaredTypes, suppressedKeys,
                typeSupertypes, Map.of(), Map.of(), Set.of());
    }

    public ParsedUnit(String file, List<ApiElement> apiElements, List<CallSite> callSites,
                      Set<String> referencedTypes, Set<String> declaredTypes,
                      Set<String> suppressedKeys, Map<String, List<String>> typeSupertypes,
                      Map<String, String> typeSuperclasses, Map<String, List<String>> typeInterfaces,
                      Set<String> abstractTypes) {
        this.file = file;
        this.apiElements = List.copyOf(apiElements);
        this.callSites = List.copyOf(callSites);
        this.referencedTypes = Set.copyOf(referencedTypes);
        this.declaredTypes = Set.copyOf(declaredTypes);
        this.suppressedKeys = Set.copyOf(suppressedKeys);
        this.typeSupertypes = Map.copyOf(typeSupertypes);
        this.typeSuperclasses = Map.copyOf(typeSuperclasses);
        this.typeInterfaces = Map.copyOf(typeInterfaces);
        this.abstractTypes = Set.copyOf(abstractTypes);
    }

    public String file() { return file; }
    public List<ApiElement> apiElements() { return apiElements; }
    public List<CallSite> callSites() { return callSites; }
    public Set<String> referencedTypes() { return referencedTypes; }
    public Set<String> declaredTypes() { return declaredTypes; }
    /** Declared type fqn -> resolved supertype names (extends/implements). */
    public Map<String, List<String>> typeSupertypes() { return typeSupertypes; }
    /** Declared type fqn -> resolved direct superclass ("" when none). Classes/enums/records only. */
    public Map<String, String> typeSuperclasses() { return typeSuperclasses; }
    /** Declared type fqn -> resolved directly implemented/extended interface names. */
    public Map<String, List<String>> typeInterfaces() { return typeInterfaces; }
    /** FQNs of declared types that are abstract (interfaces and abstract classes). */
    public Set<String> abstractTypes() { return abstractTypes; }
    public Set<String> suppressedKeys() { return suppressedKeys; }
}
