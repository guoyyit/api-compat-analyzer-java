package com.github.guoyyit.apicompatanalyzerjava.increment;

import com.github.guoyyit.apicompatanalyzerjava.model.AccessLevel;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ElementKind;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an immutable {@link ApiSnapshot} from parsed units.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Merge per-file API elements and call sites deterministically (sorted by file).</li>
 *   <li>Expand inheritance: public/protected members of in-set supertypes become
 *       effective members of subtypes unless overridden/hidden.</li>
 *   <li>Compute the file dependency graph used by incremental updates.</li>
 * </ul>
 */
public final class SnapshotBuilder {

    private SnapshotBuilder() {
    }

    public static ApiSnapshot build(String version, Collection<ParsedUnit> units,
                                    Map<String, String> fileHashes) {
        List<ParsedUnit> sorted = units.stream()
                .sorted(Comparator.comparing(ParsedUnit::file))
                .toList();

        Map<String, ApiElement> elements = new LinkedHashMap<>();
        Map<String, List<CallSite>> refs = new HashMap<>();
        Map<String, List<String>> supertypes = new HashMap<>();
        Map<String, String> typeToFile = new HashMap<>();
        Map<String, List<String>> simpleNameToFiles = new HashMap<>();

        for (ParsedUnit unit : sorted) {
            for (ApiElement el : unit.apiElements()) {
                elements.putIfAbsent(el.key(), el);
            }
            for (CallSite cs : unit.callSites()) {
                refs.computeIfAbsent(cs.referencedKey(), k -> new ArrayList<>()).add(cs);
            }
            supertypes.putAll(unit.typeSupertypes());
            for (String declared : unit.declaredTypes()) {
                typeToFile.putIfAbsent(declared, unit.file());
                String simple = declared.substring(declared.lastIndexOf('.') + 1);
                simpleNameToFiles.computeIfAbsent(simple, k -> new ArrayList<>()).add(unit.file());
            }
        }

        expandInheritance(elements, supertypes);

        Map<String, Set<String>> deps = new HashMap<>();
        for (ParsedUnit unit : sorted) {
            Set<String> fileDeps = new LinkedHashSet<>();
            for (String ref : unit.referencedTypes()) {
                String owner = typeToFile.get(ref);
                if (owner != null && !owner.equals(unit.file())) {
                    fileDeps.add(owner);
                }
                String simple = ref.substring(ref.lastIndexOf('.') + 1);
                for (String candidate : simpleNameToFiles.getOrDefault(simple, List.of())) {
                    if (!candidate.equals(unit.file())) {
                        fileDeps.add(candidate);
                    }
                }
            }
            deps.put(unit.file(), fileDeps);
        }

        return new ApiSnapshot(version, elements, refs, fileHashes, deps);
    }

    /**
     * Copies effective (public/protected, non-constructor) members of in-set
     * supertypes into each subtype, unless the subtype declares an override
     * (same name + parameters) or a hidden field (same name).
     */
    static void expandInheritance(Map<String, ApiElement> elements, Map<String, List<String>> supertypes) {
        Map<String, List<ApiElement>> membersByOwner = new HashMap<>();
        Set<String> declaredTypes = new LinkedHashSet<>();
        Map<String, String> simpleToFqn = new HashMap<>();
        for (ApiElement el : elements.values()) {
            if (el.kind() == ElementKind.TYPE) {
                declaredTypes.add(el.name());
                simpleToFqn.putIfAbsent(el.name().substring(el.name().lastIndexOf('.') + 1), el.name());
            } else {
                membersByOwner.computeIfAbsent(el.ownerType(), k -> new ArrayList<>()).add(el);
            }
        }

        Map<String, List<ApiElement>> effective = new HashMap<>();
        Set<String> processed = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>(declaredTypes);
        pending.sort(String::compareTo);

        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            List<String> next = new ArrayList<>();
            for (String type : pending) {
                List<String> supers = resolveSupers(type, supertypes, declaredTypes, simpleToFqn);
                boolean allProcessed = processed.containsAll(supers);
                if (!allProcessed && pending.stream().anyMatch(supers::contains)) {
                    next.add(type);
                    continue;
                }
                processType(type, supers, membersByOwner, effective, elements);
                processed.add(type);
                progress = true;
            }
            pending = next;
        }
        // Cycle fallback: process remaining types in deterministic order.
        for (String type : pending) {
            List<String> supers = resolveSupers(type, supertypes, declaredTypes, simpleToFqn);
            processType(type, supers, membersByOwner, effective, elements);
        }
    }

    private static void processType(String type, List<String> supers,
                                    Map<String, List<ApiElement>> membersByOwner,
                                    Map<String, List<ApiElement>> effective,
                                    Map<String, ApiElement> elements) {
        List<ApiElement> own = membersByOwner.getOrDefault(type, List.of());
        List<ApiElement> eff = new ArrayList<>(own);
        Set<String> ownMethodSigs = new LinkedHashSet<>();
        Set<String> ownFieldNames = new LinkedHashSet<>();
        for (ApiElement el : own) {
            if (el.kind() == ElementKind.METHOD) {
                ownMethodSigs.add(el.name() + "(" + String.join(",", el.parameterTypes()) + ")");
            } else if (el.kind() == ElementKind.FIELD) {
                ownFieldNames.add(el.name());
            }
        }
        for (String superType : supers) {
            for (ApiElement parent : effective.getOrDefault(superType,
                    membersByOwner.getOrDefault(superType, List.of()))) {
                if (parent.kind() == ElementKind.CONSTRUCTOR || parent.access() == AccessLevel.PRIVATE) {
                    continue;
                }
                if (parent.kind() == ElementKind.METHOD
                        && ownMethodSigs.contains(parent.name() + "(" + String.join(",", parent.parameterTypes()) + ")")) {
                    continue; // overridden
                }
                if (parent.kind() == ElementKind.FIELD && ownFieldNames.contains(parent.name())) {
                    continue; // hidden
                }
                ApiElement inherited = parent.withOwner(type);
                if (!elements.containsKey(inherited.key())) {
                    elements.put(inherited.key(), inherited);
                    eff.add(inherited);
                }
            }
        }
        effective.put(type, eff);
    }

    private static List<String> resolveSupers(String type, Map<String, List<String>> supertypes,
                                              Set<String> declaredTypes, Map<String, String> simpleToFqn) {
        List<String> result = new ArrayList<>();
        for (String sup : supertypes.getOrDefault(type, List.of())) {
            if (declaredTypes.contains(sup)) {
                result.add(sup);
            } else {
                String simple = sup.substring(sup.lastIndexOf('.') + 1);
                String fqn = simpleToFqn.get(simple);
                if (fqn != null && !fqn.equals(type)) {
                    result.add(fqn);
                }
            }
        }
        return result;
    }
}
