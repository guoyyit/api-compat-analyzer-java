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
import java.util.Comparator;
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
                changes.add(change(ChangeKind.ADDED, false, newEl,
                        describe(newEl) + " was added", "", detailOf(newEl), List.of()));
            }
        }

        changes.sort(Comparator.comparing(ApiChange::elementKey).thenComparing(c -> c.kind().ordinal()));

        List<ApiChange> result = new ArrayList<>(changes.size());
        for (ApiChange c : changes) {
            boolean suppressed = isSuppressed(c.elementKey(), suppressedKeys);
            result.add(new ApiChange(c.kind(), c.breaking(), c.elementKey(), c.description(),
                    c.oldDetail(), c.newDetail(), c.affectedCallSites(), suppressed));
        }
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
