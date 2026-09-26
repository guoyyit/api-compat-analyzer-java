package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiKind;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiVisibility;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeNature;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatVerdict;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rules based {@link ApiDiffEngine}.
 *
 * <p>Elements are matched by stable id (owner + erased signature). Signature changes of
 * the same named member (overloads, visibility, return type, modifiers, super types) are
 * detected by grouping on owner + name. Rules follow binary/source compatibility of the
 * JVM: additions are compatible (with the abstract-method exception), removals and
 * narrowing/descriptor changes are breaking, widening and benign additions are not.
 */
@Component
public class DefaultApiDiffEngine implements ApiDiffEngine {

    @Override
    public List<ApiChange> diff(SnapshotModel baseline, SnapshotModel current) {
        List<ApiChange> changes = new ArrayList<>();

        // ADDED / REMOVED by stable id.
        for (ApiElement now : sorted(current.elementsById())) {
            ApiElement before = baseline.elementsById().get(now.id());
            if (before == null) {
                if (isPublicApi(now)) {
                    changes.add(added(now, baseline));
                }
            } else {
                ApiChange changed = signatureChange(before, now);
                if (changed != null) {
                    changes.add(changed);
                }
            }
        }
        for (ApiElement before : sorted(baseline.elementsById())) {
            if (!current.elementsById().containsKey(before.id()) && isPublicApi(before)) {
                changes.add(removed(before));
            }
        }

        changes.sort(Comparator.comparing(ApiChange::elementId)
                .thenComparing(c -> c.nature().name()));
        return List.copyOf(changes);
    }

    private List<ApiElement> sorted(Map<String, ApiElement> map) {
        List<ApiElement> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing(ApiElement::id));
        return list;
    }

    private ApiChange added(ApiElement now, SnapshotModel baseline) {
        boolean breaking = false;
        String reason = "new API element added";
        // Adding an abstract method to an existing non-final public/protected type is
        // breaking for source and binary compatibility of external implementations.
        if (now.kind() == ApiKind.METHOD && now.abstractFlag() && now.owner() != null) {
            ApiElement owner = baseline.elementsById().get(now.owner());
            if (owner != null && owner.kind() == ApiKind.TYPE && !owner.finalFlag()) {
                breaking = true;
                reason = "abstract method added to extensible type; existing implementations must implement it";
            }
        }
        return markSuppression(new ApiChange(ChangeNature.ADDED,
                breaking ? CompatVerdict.BREAKING : CompatVerdict.COMPATIBLE,
                now.id(), null, now, reason, false, null));
    }

    private ApiChange removed(ApiElement before) {
        ApiChange change = new ApiChange(ChangeNature.REMOVED, CompatVerdict.BREAKING,
                before.id(), before, null,
                "public API element removed; existing callers/implementations fail",
                false, null);
        return markSuppression(change);
    }

    private ApiChange signatureChange(ApiElement before, ApiElement now) {
        List<String> problems = new ArrayList<>();

        if (before.kind() != now.kind()) {
            problems.add("element kind changed from " + before.kind() + " to " + now.kind());
        }
        if (!Objects.equals(before.parameterTypes(), now.parameterTypes())) {
            problems.add("parameter types changed from "
                    + before.parameterTypes() + " to " + now.parameterTypes());
        }
        if (before.kind() == ApiKind.METHOD
                && !Objects.equals(before.resultType(), now.resultType())) {
            problems.add("return type changed from "
                    + before.resultType() + " to " + now.resultType());
        }
        if (before.kind() == ApiKind.FIELD
                && !Objects.equals(before.resultType(), now.resultType())) {
            problems.add("field type changed from "
                    + before.resultType() + " to " + now.resultType());
        }
        if (visibilityRank(now.visibility()) > visibilityRank(before.visibility())) {
            problems.add("access level narrowed from "
                    + before.visibility() + " to " + now.visibility());
        }
        if (before.staticFlag() != now.staticFlag()) {
            problems.add("static modifier changed ("
                    + before.staticFlag() + " -> " + now.staticFlag() + ")");
        }
        if (before.kind() == ApiKind.METHOD) {
            if (!before.finalFlag() && now.finalFlag()) {
                problems.add("method became final; overrides no longer compile/link");
            }
            if (before.kind() == ApiKind.METHOD && !before.abstractFlag() && now.abstractFlag()) {
                problems.add("concrete method became abstract");
            }
        }
        if (before.kind() == ApiKind.TYPE) {
            if (!before.finalFlag() && now.finalFlag()) {
                problems.add("type became final; can no longer be extended");
            }
            if (!before.abstractFlag() && now.abstractFlag()) {
                problems.add("type became abstract; can no longer be instantiated");
            }
            if (!Objects.equals(new ArrayList<>(before.superTypes()),
                    new ArrayList<>(now.superTypes()))) {
                problems.add("super types changed from "
                        + before.superTypes() + " to " + now.superTypes());
            }
        }

        if (problems.isEmpty()) {
            return null;
        }
        ApiChange change = new ApiChange(ChangeNature.SIGNATURE_CHANGED,
                CompatVerdict.BREAKING, now.id(), before, now,
                String.join("; ", problems), false, null);
        return markSuppression(change);
    }

    /**
     * Apply exemptions carried by either side. An element is exempt when it carries
     * {@code @ApiCompatExempt}; the optional {@code change} selector narrows the scope.
     */
    private ApiChange markSuppression(ApiChange change) {
        ApiElement element = change.newElement() != null
                ? change.newElement() : change.oldElement();
        if (element == null || !element.exempt()) {
            // Baseline-side exemption (for removals the new side is null).
            if (change.oldElement() != null && change.oldElement().exempt()) {
                element = change.oldElement();
            } else {
                return change;
            }
        }
        String selector = suppressionSelector(element);
        if (selector == null || selector.isBlank()
                || selector.equalsIgnoreCase(change.nature().name())
                || selector.equalsIgnoreCase("ALL")) {
            return new ApiChange(change.nature(), change.verdict(), change.elementId(),
                    change.oldElement(), change.newElement(), change.reason(),
                    true, element.exemptReason());
        }
        return change;
    }

    private String suppressionSelector(ApiElement element) {
        return element.exemptChange();
    }

    /** An element is part of the public API surface when public or protected. */
    private boolean isPublicApi(ApiElement element) {
        return element.visibility() == ApiVisibility.PUBLIC
                || element.visibility() == ApiVisibility.PROTECTED;
    }

    /** Smaller rank = more visible. */
    private int visibilityRank(ApiVisibility visibility) {
        return switch (visibility) {
            case PUBLIC -> 0;
            case PROTECTED -> 1;
            case PACKAGE -> 2;
            case PRIVATE -> 3;
        };
    }
}
