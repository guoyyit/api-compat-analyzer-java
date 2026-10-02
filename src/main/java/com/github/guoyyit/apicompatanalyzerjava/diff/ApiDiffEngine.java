package com.github.guoyyit.apicompatanalyzerjava.diff;

import com.github.guoyyit.apicompatanalyzerjava.model.AccessLevel;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeKind;
import com.github.guoyyit.apicompatanalyzerjava.model.ElementKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two API snapshots and classifies each change as breaking or
 * compatible, attaching the affected call sites found in the current
 * snapshot's reference index.
 */
@Component
public class ApiDiffEngine {

    /**
     * @param baseline       the previous API snapshot
     * @param current        the current API snapshot (its reference index locates call sites)
     * @param suppressedKeys element keys covered by exemption declarations
     */
    public List<ApiChange> diff(ApiSnapshot baseline, ApiSnapshot current, Set<String> suppressedKeys) {
        List<ApiChange> changes = new ArrayList<>();
        Map<String, ApiElement> oldElements = baseline.elements();
        Map<String, ApiElement> newElements = current.elements();
        Map<String, ApiElement> newTypes = typeElements(newElements);

        for (ApiElement oldEl : oldElements.values()) {
            ApiElement newEl = newElements.get(oldEl.key());
            if (newEl == null) {
                changes.add(change(ChangeKind.REMOVED, true, oldEl,
                        describe(oldEl) + " was removed", detailOf(oldEl), "",
                        affectedCallSites(oldEl, current)));
            } else {
                compareElement(oldEl, newEl, current, changes);
            }
        }
        for (ApiElement newEl : newElements.values()) {
            if (!oldElements.containsKey(newEl.key())) {
                handleAdded(newEl, newTypes, current, changes);
            }
        }
        detectSuperclassChanges(baseline, current, newTypes, changes);

        changes.sort(Comparator.comparing(ApiChange::elementKey).thenComparing(c -> c.kind().ordinal()));

        List<ApiChange> result = new ArrayList<>(changes.size());
        for (ApiChange c : changes) {
            boolean suppressed = isSuppressed(c.elementKey(), suppressedKeys);
            result.add(new ApiChange(c.kind(), c.breaking(), c.elementKey(), c.description(),
                    c.oldDetail(), c.newDetail(), c.affectedCallSites(), suppressed));
        }
        return result;
    }

    private static Map<String, ApiElement> typeElements(Map<String, ApiElement> elements) {
        Map<String, ApiElement> types = new HashMap<>();
        for (ApiElement el : elements.values()) {
            if (el.kind() == ElementKind.TYPE) {
                types.put(el.name(), el);
            }
        }
        return types;
    }

    /**
     * Classifies a newly appearing element. Effective members inherited from a
     * supertype produce no change of their own (the owning type's change does).
     * A new abstract obligation (abstract method on a class, or a non-default
     * method on an interface) is breaking and lists every concrete subtype that
     * does not inherit an implementation of the method.
     */
    private void handleAdded(ApiElement newEl, Map<String, ApiElement> types,
                             ApiSnapshot current, List<ApiChange> changes) {
        if (newEl.kind() == ElementKind.TYPE) {
            changes.add(change(ChangeKind.ADDED, false, newEl,
                    describe(newEl) + " was added", "", detailOf(newEl), List.of()));
            return;
        }
        if (isInheritedEffectiveMember(newEl, types)) {
            return; // inherited expansion noise; declared owner change is reported separately
        }
        if (newEl.kind() == ElementKind.METHOD && imposesNewObligation(newEl, types)) {
            List<CallSite> sites = obligationCallSites(newEl, current, types);
            changes.add(change(ChangeKind.ABSTRACT_METHOD_ADDED, true, newEl,
                    describe(newEl) + " was added as an abstract method; downstream implementors "
                            + "must provide an implementation",
                    "", detailOf(newEl), sites));
            return;
        }
        changes.add(change(ChangeKind.ADDED, false, newEl,
                describe(newEl) + " was added", "", detailOf(newEl), List.of()));
    }

    private static boolean isInheritedEffectiveMember(ApiElement el, Map<String, ApiElement> types) {
        ApiElement ownerType = types.get(el.ownerType());
        return ownerType != null && !ownerType.sourceFile().equals(el.sourceFile());
    }

    private static boolean imposesNewObligation(ApiElement method, Map<String, ApiElement> types) {
        ApiElement ownerType = types.get(method.ownerType());
        if (ownerType == null) {
            return false;
        }
        boolean isInterface = "INTERFACE".equals(ownerType.returnType());
        if (isInterface) {
            // Default and static interface methods have an implementation; abstract ones do not.
            return !method.modifiers().contains("default") && !method.modifiers().contains("static");
        }
        return method.modifiers().contains("abstract");
    }

    /**
     * Collects declaration and usage sites of every concrete subtype that is
     * missing an inherited implementation of the new abstract method.
     */
    private List<CallSite> obligationCallSites(ApiElement method, ApiSnapshot current,
                                               Map<String, ApiElement> types) {
        String owner = method.ownerType();
        String memberKey = "METHOD:" + owner + "#" + method.name()
                + "(" + String.join(",", method.parameterTypes()) + ")";
        Set<CallSite> sites = new LinkedHashSet<>();
        for (String subtype : concreteSubtypesMissingImplementation(owner, memberKey, current, types)) {
            ApiElement subtypeElement = types.get(subtype);
            if (subtypeElement != null) {
                sites.addAll(affectedCallSites(subtypeElement, current));
            }
        }
        return sortSites(sites);
    }

    private static List<String> concreteSubtypesMissingImplementation(
            String rootType, String methodKey, ApiSnapshot current, Map<String, ApiElement> types) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(directSubtypes(rootType, current, seen));
        while (!queue.isEmpty()) {
            String subtype = queue.removeFirst();
            if (!seen.add(subtype)) {
                continue;
            }
            for (String child : directSubtypes(subtype, current, new HashSet<>())) {
                if (!seen.contains(child)) {
                    queue.addLast(child);
                }
            }
            ApiElement typeElement = types.get(subtype);
            if (typeElement == null || !"CLASS".equals(typeElement.returnType())
                    || typeElement.modifiers().contains("abstract")) {
                continue; // out of tree or abstract: only its concrete descendants can break
            }
            ApiElement effective = current.elements().get(
                    methodKey.replace(rootType, subtype));
            if (effective != null && !effective.modifiers().contains("abstract")) {
                continue; // inherits or declares a concrete implementation
            }
            result.add(subtype);
        }
        result.sort(String::compareTo);
        return result;
    }

    private static List<String> directSubtypes(String typeFqn, ApiSnapshot current, Set<String> filter) {
        List<String> children = new ArrayList<>();
        current.typeSupertypes().forEach((candidate, supers) -> {
            if (!filter.contains(candidate) && supers.contains(typeFqn)) {
                children.add(candidate);
            }
        });
        children.sort(String::compareTo);
        return children;
    }

    private static List<CallSite> sortSites(Set<CallSite> sites) {
        List<CallSite> sorted = new ArrayList<>(sites);
        sorted.sort(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line)
                .thenComparingInt(CallSite::column).thenComparing(CallSite::referencedKey));
        return sorted;
    }

    /** Direct superclass added/removed/replaced is always breaking. */
    private void detectSuperclassChanges(ApiSnapshot baseline, ApiSnapshot current,
                                         Map<String, ApiElement> newTypes, List<ApiChange> changes) {
        baseline.typeSuperclasses().forEach((typeFqn, oldSuper) -> {
            if (!newTypes.containsKey(typeFqn)) {
                return; // type removal is already reported
            }
            String newSuper = current.typeSuperclasses().getOrDefault(typeFqn, "");
            if (oldSuper.equals(newSuper)) {
                return;
            }
            ApiElement typeElement = newTypes.get(typeFqn);
            if (!"CLASS".equals(typeElement.returnType())) {
                return;
            }
            String description;
            if (oldSuper.isEmpty()) {
                description = "Type " + typeFqn + " now extends superclass " + newSuper;
            } else if (newSuper.isEmpty()) {
                description = "Type " + typeFqn + " no longer extends superclass " + oldSuper;
            } else {
                description = "Type " + typeFqn + " superclass changed from " + oldSuper + " to " + newSuper;
            }
            Set<CallSite> sites = new LinkedHashSet<>();
            for (String affected : typeAndTransitiveSubtypes(typeFqn, current)) {
                ApiElement affectedElement = newTypes.get(affected);
                if (affectedElement != null) {
                    sites.addAll(affectedCallSites(affectedElement, current));
                }
            }
            changes.add(new ApiChange(ChangeKind.SUPERCLASS_CHANGED, true,
                    "TYPE:" + typeFqn, description, oldSuper, newSuper, sortSites(sites)));
        });
    }

    private static List<String> typeAndTransitiveSubtypes(String rootType, ApiSnapshot current) {
        List<String> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootType);
        while (!queue.isEmpty()) {
            String type = queue.removeFirst();
            if (result.contains(type)) {
                continue;
            }
            result.add(type);
            queue.addAll(directSubtypes(type, current, new HashSet<>(result)));
        }
        result.sort(String::compareTo);
        return result;
    }

    private void compareElement(ApiElement oldEl, ApiElement newEl, ApiSnapshot current,
                                List<ApiChange> changes) {
        // Access level changes.
        int oldRank = oldEl.access().rank();
        int newRank = newEl.access().rank();
        if (newRank < oldRank) {
            changes.add(change(ChangeKind.ACCESS_NARROWED, true, newEl,
                    describe(newEl) + " access narrowed from " + oldEl.access() + " to " + newEl.access(),
                    oldEl.access().name(), newEl.access().name(), affectedCallSites(newEl, current)));
        } else if (newRank > oldRank) {
            changes.add(change(ChangeKind.ACCESS_WIDENED, false, newEl,
                    describe(newEl) + " access widened from " + oldEl.access() + " to " + newEl.access(),
                    oldEl.access().name(), newEl.access().name(), List.of()));
        }

        // Return/field type or type-kind changes.
        if (!oldEl.returnType().equals(newEl.returnType())) {
            boolean breaking = oldEl.kind() != ElementKind.TYPE || isTypeKindChange(oldEl, newEl);
            if (oldEl.kind() == ElementKind.TYPE) {
                changes.add(change(ChangeKind.SIGNATURE_CHANGED, true, newEl,
                        describe(newEl) + " changed kind from " + oldEl.returnType() + " to " + newEl.returnType(),
                        oldEl.returnType(), newEl.returnType(), affectedCallSites(newEl, current)));
            } else {
                changes.add(change(ChangeKind.SIGNATURE_CHANGED, true, newEl,
                        describe(newEl) + " changed type from " + oldEl.returnType() + " to " + newEl.returnType(),
                        oldEl.returnType(), newEl.returnType(), affectedCallSites(newEl, current)));
            }
        }

        // Modifier changes.
        Set<String> oldMods = oldEl.modifiers();
        Set<String> newMods = newEl.modifiers();
        if (!oldMods.contains("final") && newMods.contains("final")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, true, newEl,
                    describe(newEl) + " became final", "non-final", "final",
                    affectedCallSites(newEl, current)));
        }
        if (oldMods.contains("final") && !newMods.contains("final")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, false, newEl,
                    describe(newEl) + " is no longer final", "final", "non-final", List.of()));
        }
        if (!oldMods.contains("abstract") && newMods.contains("abstract")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, true, newEl,
                    describe(newEl) + " became abstract", "non-abstract", "abstract",
                    affectedCallSites(newEl, current)));
        }
        if (oldMods.contains("abstract") && !newMods.contains("abstract")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, false, newEl,
                    describe(newEl) + " is no longer abstract", "abstract", "non-abstract", List.of()));
        }
        if (oldMods.contains("static") && !newMods.contains("static")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, true, newEl,
                    describe(newEl) + " is no longer static", "static", "instance",
                    affectedCallSites(newEl, current)));
        }
        if (!oldMods.contains("static") && newMods.contains("static")) {
            changes.add(change(ChangeKind.SIGNATURE_CHANGED, false, newEl,
                    describe(newEl) + " became static", "instance", "static", List.of()));
        }
    }

    private static boolean isTypeKindChange(ApiElement oldEl, ApiElement newEl) {
        return true; // any kind change of a type is breaking (class -> interface etc.)
    }

    /** Locates call sites in the current snapshot that reference the given element. */
    static List<CallSite> affectedCallSites(ApiElement element, ApiSnapshot current) {
        Map<String, List<CallSite>> refs = current.referencesByKey();
        Set<CallSite> result = new LinkedHashSet<>();
        switch (element.kind()) {
            case TYPE -> {
                String fqn = element.name();
                // The type declaration itself is always an affected location.
                if (element.line() > 0) {
                    result.add(new CallSite(element.sourceFile(), element.line(), -1,
                            fqn, "TYPE:" + fqn));
                }
                collect(refs, "TYPE:" + fqn, result);
                String memberPrefix = "MEMBER:" + fqn + "#";
                String fieldPrefix = "FIELD:" + fqn + "#";
                refs.forEach((key, sites) -> {
                    if (key.startsWith(memberPrefix) || key.startsWith(fieldPrefix)) {
                        result.addAll(sites);
                    }
                });
            }
            case FIELD -> {
                collect(refs, "FIELD:" + element.ownerType() + "#" + element.name(), result);
            }
            case METHOD, CONSTRUCTOR -> {
                String name = element.kind() == ElementKind.CONSTRUCTOR ? "<init>" : element.name();
                int arity = element.parameterTypes().size();
                String prefix = "MEMBER:" + element.ownerType() + "#" + name + "/";
                String unknownPrefix = "MEMBER:#" + name + "/";
                refs.forEach((key, sites) -> {
                    if ((key.startsWith(prefix) || key.startsWith(unknownPrefix))
                            && arityMatches(key, arity)) {
                        result.addAll(sites);
                    }
                });
            }
        }
        List<CallSite> sorted = new ArrayList<>(result);
        sorted.sort(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line)
                .thenComparingInt(CallSite::column));
        return sorted;
    }

    private static boolean arityMatches(String key, int arity) {
        int slash = key.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String suffix = key.substring(slash + 1);
        return suffix.equals("-1") || suffix.equals(String.valueOf(arity));
    }

    private static void collect(Map<String, List<CallSite>> refs, String key, Set<CallSite> out) {
        List<CallSite> sites = refs.get(key);
        if (sites != null) {
            out.addAll(sites);
        }
    }

    private boolean isSuppressed(String elementKey, Set<String> suppressedKeys) {
        if (suppressedKeys.contains(elementKey)) {
            return true;
        }
        // A suppressed type exempts all of its members (and nested types).
        String owner = ownerOf(elementKey);
        while (owner != null && !owner.isEmpty()) {
            if (suppressedKeys.contains("TYPE:" + owner)) {
                return true;
            }
            int idx = owner.lastIndexOf('.');
            owner = idx < 0 ? "" : owner.substring(0, idx);
        }
        return false;
    }

    private static String ownerOf(String elementKey) {
        int hash = elementKey.indexOf('#');
        if (hash < 0) {
            return ""; // TYPE elements have no owner in the key
        }
        int colon = elementKey.indexOf(':');
        return elementKey.substring(colon + 1, hash);
    }

    private static ApiChange change(ChangeKind kind, boolean breaking, ApiElement el,
                                    String description, String oldDetail, String newDetail,
                                    List<CallSite> callSites) {
        return new ApiChange(kind, breaking, el.key(), description, oldDetail, newDetail, callSites);
    }

    private static String describe(ApiElement el) {
        return switch (el.kind()) {
            case TYPE -> "Type " + el.name();
            case FIELD -> "Field " + el.ownerType() + "#" + el.name();
            case METHOD -> "Method " + el.ownerType() + "#" + el.name()
                    + "(" + String.join(",", el.parameterTypes()) + ")";
            case CONSTRUCTOR -> "Constructor " + el.ownerType()
                    + "(" + String.join(",", el.parameterTypes()) + ")";
        };
    }

    private static String detailOf(ApiElement el) {
        return el.access() + " " + String.join(" ", new TreeSet<>(el.modifiers())) + " "
                + el.returnType() + " " + el.name()
                + (el.parameterTypes().isEmpty() ? "" : "(" + String.join(",", el.parameterTypes()) + ")");
    }
}
