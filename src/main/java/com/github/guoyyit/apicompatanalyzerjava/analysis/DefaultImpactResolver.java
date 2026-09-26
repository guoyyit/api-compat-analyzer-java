package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiKind;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSiteKind;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatVerdict;
import com.github.guoyyit.apicompatanalyzerjava.model.ImpactFinding;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locates the concrete call sites a breaking change affects.
 *
 * <p>Matching uses the parsed owner hint (a best-effort FQN) and the type hierarchy: a
 * call to a method inherited or overridden from a super type is found through the
 * subtype closure. Only non-suppressed breaking changes produce findings.
 */
@Component
public class DefaultImpactResolver implements ImpactResolver {

    @Override
    public List<ImpactFinding> resolve(List<ApiChange> breakingChanges, SnapshotModel current) {
        Map<String, Set<String>> closures = hierarchyClosures(current);
        List<ImpactFinding> findings = new ArrayList<>();
        for (ApiChange change : breakingChanges) {
            if (change.verdict() != CompatVerdict.BREAKING || change.suppressed()) {
                continue;
            }
            List<CallSite> sites = findCallSites(current, change, closures);
            findings.add(new ImpactFinding(change.elementId(), change.reason(), List.copyOf(sites)));
        }
        findings.sort(Comparator.comparing(ImpactFinding::elementId));
        return List.copyOf(findings);
    }

    @Override
    public List<CallSite> findCallSites(SnapshotModel current, String elementId) {
        ApiElement element = current.elementsById().get(elementId);
        if (element == null) {
            return List.of();
        }
        Map<String, Set<String>> closures = hierarchyClosures(current);
        ApiChange synthetic = new ApiChange(null, CompatVerdict.BREAKING, elementId,
                null, element, "", false, null);
        return findCallSites(current, synthetic, closures);
    }

    private List<CallSite> findCallSites(SnapshotModel current, ApiChange change,
                                         Map<String, Set<String>> closures) {
        ApiElement element = change.oldElement() != null ? change.oldElement() : change.newElement();
        if (element == null) {
            return List.of();
        }
        Set<CallSite> hits = new HashSet<>();
        for (CallSite site : current.callSites()) {
            if (matches(site, element, current, closures)) {
                hits.add(site);
            }
        }
        List<CallSite> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparing(CallSite::path)
                .thenComparingInt(CallSite::line)
                .thenComparing(CallSite::symbol));
        return sorted;
    }

    private boolean matches(CallSite site, ApiElement element, SnapshotModel current,
                            Map<String, Set<String>> closures) {
        String owner = element.owner();
        switch (element.kind()) {
            case TYPE -> {
                return site.kind() == CallSiteKind.TYPE_USE
                        && typeNameMatches(site, element, closures);
            }
            case CONSTRUCTOR -> {
                if (site.kind() != CallSiteKind.CONSTRUCTOR_CALL) {
                    return false;
                }
                if (owner != null && (site.resolvedOwner() == null
                        || !reaches(site.resolvedOwner(), owner, closures))) {
                    return false;
                }
                return arityCompatible(site.argumentCount(), element.parameterTypes().size());
            }
            case METHOD -> {
                if (site.kind() != CallSiteKind.METHOD_CALL) {
                    return false;
                }
                if (!site.symbol().equals(element.name())) {
                    return false;
                }
                if (!arityCompatible(site.argumentCount(), element.parameterTypes().size())) {
                    return false;
                }
                return site.resolvedOwner() == null
                        || owner == null
                        || reaches(site.resolvedOwner(), owner, closures)
                        || site.resolvedOwner().equals(owner);
            }
            case FIELD -> {
                if (site.kind() != CallSiteKind.FIELD_ACCESS) {
                    return false;
                }
                if (!site.symbol().equals(element.name())) {
                    return false;
                }
                // Bare references carry the enclosing type; qualified ones the textual owner.
                return site.resolvedOwner() == null
                        || owner == null
                        || reaches(site.resolvedOwner(), owner, closures)
                        || site.resolvedOwner().equals(owner);
            }
        }
        return false;
    }

    private boolean typeNameMatches(CallSite site, ApiElement typeElement,
                                    Map<String, Set<String>> closures) {
        String expectedSimple = simpleName(typeElement.id());
        if (!site.symbol().equals(expectedSimple)) {
            return false;
        }
        if (site.resolvedOwner() == null) {
            return true;
        }
        return reaches(site.resolvedOwner(), typeElement.id(), closures)
                || site.resolvedOwner().equals(typeElement.id());
    }

    /**
     * Whether a call whose resolved owner is {@code callOwner} can dispatch to a member
     * declared by {@code declaredOn}. True when the owners are equal, or either type is
     * in the other's hierarchy (subtype for override call sites, super type for
     * inherited access).
     */
    private boolean reaches(String callOwner, String declaredOn,
                            Map<String, Set<String>> closures) {
        if (callOwner.equals(declaredOn)) {
            return true;
        }
        Set<String> callClosure = closures.get(callOwner);
        if (callClosure != null && callClosure.contains(declaredOn)) {
            return true;
        }
        Set<String> declaredClosure = closures.get(declaredOn);
        return declaredClosure != null && declaredClosure.contains(callOwner);
    }

    private boolean arityCompatible(int callArity, int declaredArity) {
        // Arity is a necessary condition; overloaded members with identical arity may
        // over-match, which is preferred to missing a real call site without a solver.
        return callArity == declaredArity;
    }

    private String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * Type -> full hierarchy closure (itself + transitively every super type and every
     * sub type). The parsed graph only stores edges for types declared in the snapshot;
     * edges to external types terminate there, which is correct for a two-snapshot diff.
     */
    private Map<String, Set<String>> hierarchyClosures(SnapshotModel snapshot) {
        Map<String, Set<String>> superEdges = snapshot.subtypeGraph();
        Map<String, Set<String>> subEdges = new HashMap<>();
        for (Map.Entry<String, Set<String>> edge : superEdges.entrySet()) {
            for (String parent : edge.getValue()) {
                subEdges.computeIfAbsent(parent, k -> new HashSet<>()).add(edge.getKey());
            }
        }
        Map<String, Set<String>> closures = new HashMap<>();
        Set<String> allTypes = new HashSet<>(superEdges.keySet());
        subEdges.keySet().forEach(allTypes::add);
        for (String type : allTypes) {
            Set<String> closure = new HashSet<>();
            closure.add(type);
            grow(type, superEdges, closure);
            grow(type, subEdges, closure);
            closures.put(type, closure);
        }
        return closures;
    }

    private void grow(String start, Map<String, Set<String>> edges, Set<String> closure) {
        List<String> frontier = new ArrayList<>(edges.getOrDefault(start, Set.of()));
        while (!frontier.isEmpty()) {
            String next = frontier.remove(frontier.size() - 1);
            if (closure.add(next)) {
                frontier.addAll(edges.getOrDefault(next, Set.of()));
            }
        }
    }
}
