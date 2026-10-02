package com.github.guoyyit.apicompatanalyzerjava.diff;

import com.github.guoyyit.apicompatanalyzerjava.model.AccessLevel;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeKind;
import com.github.guoyyit.apicompatanalyzerjava.model.ElementKind;
import com.github.guoyyit.apicompatanalyzerjava.model.TypeRelation;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
                handleAddedElement(newEl, baseline, current, changes);
            }
        }
        detectSuperclassChanges(baseline, current, changes);

        changes.sort(Comparator.comparing(ApiChange::elementKey).thenComparing(c -> c.kind().ordinal()));

        List<ApiChange> result = new ArrayList<>(changes.size());
        for (ApiChange c : changes) {
            boolean suppressed = isSuppressed(c.elementKey(), suppressedKeys);
            result.add(new ApiChange(c.kind(), c.breaking(), c.elementKey(), c.description(),
                    c.oldDetail(), c.newDetail(), c.affectedCallSites(), suppressed));
        }
        return result;
    }

    /**
     * Classifies a newly appearing element. Ordinary additions are compatible,
     * but a new abstract method (an abstract class/interface method without a
     * default implementation) is breaking: every concrete downstream type that
     * inherits it without providing an implementation fails to compile.
     * A method on a newly added type is always compatible, since the type itself
     * did not exist in the baseline.
     */
    private void handleAddedElement(ApiElement newEl, ApiSnapshot baseline, ApiSnapshot current,
                                    List<ApiChange> changes) {
        boolean ownerExistedInBaseline =
                baseline.elements().containsKey("TYPE:" + newEl.ownerType());
        if (newEl.kind() == ElementKind.METHOD && newEl.modifiers().contains("abstract")
                && ownerExistedInBaseline) {
            List<CallSite> sites = abstractMethodImpact(newEl.ownerType(), newEl, current);
            changes.add(change(ChangeKind.ADDED, true, newEl,
                    describe(newEl) + " was added as an abstract method; concrete implementors must implement it",
                    "", detailOf(newEl), sites));
            return;
        }
        changes.add(change(ChangeKind.ADDED, false, newEl,
                describe(newEl) + " was added", "", detailOf(newEl), List.of()));
    }

    /**
     * Locates concrete downstream types that inherit the newly added abstract
     * method without implementing it, and returns their declaration and usage
     * sites (file, line, column and hit symbol).
     */
    private static List<CallSite> abstractMethodImpact(String ownerFqn, ApiElement method,
                                                       ApiSnapshot current) {
        Map<String, TypeRelation> hierarchy = current.typeHierarchy();
        Map<String, ApiElement> elements = current.elements();
        String params = "(" + String.join(",", method.parameterTypes()) + ")";
        Set<CallSite> result = new LinkedHashSet<>();

        // Declaration and usage sites of the declaring type itself: TYPE
        // references plus calls to this very method (polymorphic calls through
        // an interface-typed reference are attributed to the interface).
        collectOwnerMethodSites(ownerFqn, method, current.referencesByKey(), result);
        addDeclarationSite(ownerFqn, current, result);

        // Plus concrete descendants that still inherit the method as abstract.
        for (String descendant : descendants(ownerFqn, hierarchy, false)) {
            TypeRelation rel = hierarchy.get(descendant);
            if (rel != null && rel.concrete()
                    && isAbstractAt(descendant, method.name(), params, elements)) {
                collectTypeSites(descendant, current.referencesByKey(), result);
                addDeclarationSite(descendant, current, result);
            }
        }
        return sortSites(result);
    }

    /** True when the effective method at {@code type} is abstract (no concrete implementation in place). */
    private static boolean isAbstractAt(String type, String methodName, String params,
                                        Map<String, ApiElement> elements) {
        ApiElement effective = elements.get("METHOD:" + type + "#" + methodName + params);
        return effective != null && effective.modifiers().contains("abstract");
    }

    /**
     * A direct superclass replacement, removal or addition changes the type's
     * contract (inherited members and behaviour change); it is always breaking
     * and affects the type itself and every subclass together with their usage
     * sites.
     */
    private static void detectSuperclassChanges(ApiSnapshot baseline, ApiSnapshot current,
                                                List<ApiChange> changes) {
        Map<String, TypeRelation> oldHierarchy = baseline.typeHierarchy();
        Map<String, TypeRelation> newHierarchy = current.typeHierarchy();
        if (oldHierarchy.isEmpty()) {
            return; // baseline persisted before hierarchy tracking: nothing comparable
        }
        Set<String> types = new TreeSet<>();
        types.addAll(oldHierarchy.keySet());
        types.addAll(newHierarchy.keySet());
        for (String type : types) {
            TypeRelation oldRel = oldHierarchy.get(type);
            TypeRelation newRel = newHierarchy.get(type);
            if (oldRel == null || newRel == null) {
                continue; // type addition/removal is reported separately
            }
            String oldSuper = oldRel.superclass();
            String newSuper = newRel.superclass();
            if (oldSuper.equals(newSuper)) {
                continue;
            }
            Set<CallSite> sites = new LinkedHashSet<>();
            collectTypeSites(type, current.referencesByKey(), sites);
            addDeclarationSite(type, current, sites);
            for (String subclass : descendants(type, newHierarchy, true)) {
                collectTypeSites(subclass, current.referencesByKey(), sites);
                addDeclarationSite(subclass, current, sites);
            }
            String description = "Type " + type + " superclass changed from "
                    + (oldSuper.isEmpty() ? "(none)" : oldSuper)
                    + " to " + (newSuper.isEmpty() ? "(none)" : newSuper);
            ApiElement placeholder = new ApiElement(ElementKind.TYPE, "", type,
                    "CLASS", List.of(), AccessLevel.PUBLIC, Set.of(), "", -1);
            changes.add(new ApiChange(ChangeKind.SUPERCLASS_CHANGED, true, placeholder.key(),
                    description, oldSuper, newSuper, sortSites(sites)));
        }
    }

    /**
     * Types directly/transitively derived from {@code root} through the
     * hierarchy. When {@code classEdgesOnly} is set only {@code extends} edges
     * are followed (subclass impact of a superclass change).
     */
    static List<String> descendants(String root, Map<String, TypeRelation> hierarchy,
                                    boolean classEdgesOnly) {
        Map<String, List<String>> children = new HashMap<>();
        for (Map.Entry<String, TypeRelation> e : hierarchy.entrySet()) {
            String child = e.getKey();
            if (!e.getValue().superclass().isEmpty()) {
                children.computeIfAbsent(e.getValue().superclass(), k -> new ArrayList<>()).add(child);
            }
            if (!classEdgesOnly) {
                for (String itf : e.getValue().interfaces()) {
                    children.computeIfAbsent(itf, k -> new ArrayList<>()).add(child);
                }
            }
        }
        List<String> result = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(root, List.of()));
        Set<String> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (seen.add(next)) {
                result.add(next);
                queue.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return result;
    }

    private static List<CallSite> sortSites(Set<CallSite> sites) {
        List<CallSite> sorted = new ArrayList<>(sites);
        sorted.sort(Comparator.comparing(CallSite::file).thenComparingInt(CallSite::line)
                .thenComparingInt(CallSite::column).thenComparing(CallSite::referencedKey));
        return sorted;
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
                collectTypeSites(element.name(), refs, result);
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
        return sortSites(result);
    }

    /** Collects all declaration and usage sites of a type (TYPE/FIELD/MEMBER references). */
    static void collectTypeSites(String fqn, Map<String, List<CallSite>> refs, Set<CallSite> out) {
        collect(refs, "TYPE:" + fqn, out);
        String memberPrefix = "MEMBER:" + fqn + "#";
        String fieldPrefix = "FIELD:" + fqn + "#";
        refs.forEach((key, sites) -> {
            if (key.startsWith(memberPrefix) || key.startsWith(fieldPrefix)) {
                out.addAll(sites);
            }
        });
    }

    /** Synthesizes the type's own declaration site (file/line, hit symbol = the type FQN). */
    private static void addDeclarationSite(String fqn, ApiSnapshot current, Set<CallSite> out) {
        ApiElement typeElement = current.elements().get("TYPE:" + fqn);
        if (typeElement != null && typeElement.line() > 0) {
            out.add(new CallSite(typeElement.sourceFile(), typeElement.line(), 1,
                    fqn, "TYPE:" + fqn));
        }
    }

    /** TYPE references of a type plus MEMBER references matching the given method's name/arity. */
    private static void collectOwnerMethodSites(String fqn, ApiElement method,
                                                Map<String, List<CallSite>> refs,
                                                Set<CallSite> out) {
        collect(refs, "TYPE:" + fqn, out);
        int arity = method.parameterTypes().size();
        String prefix = "MEMBER:" + fqn + "#" + method.name() + "/";
        refs.forEach((key, sites) -> {
            if (key.startsWith(prefix) && arityMatches(key, arity)) {
                out.addAll(sites);
            }
        });
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
