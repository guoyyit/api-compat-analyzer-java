package com.github.guoyyit.apicompatanalyzerjava.model;

import java.util.List;

/**
 * Immutable description of one publicly visible API element extracted from source.
 *
 * @param id             stable identity used for diffing (owner + name + erased parameters)
 * @param kind           element kind
 * @param owner          fully qualified name of the declaring type, {@code null} for a TYPE
 * @param name           simple name of the element
 * @param resultType     erased return type / field type, {@code null} for TYPE and CONSTRUCTOR
 * @param parameterTypes erased formal parameter types (empty for non-method elements)
 * @param visibility     access level
 * @param staticFlag     whether the element is static
 * @param abstractFlag   whether the element (or type) is abstract
 * @param finalFlag      whether the element (or type) is final
 * @param superTypes     resolved-ish super types for a TYPE, empty otherwise
 * @param location       declaration location
 * @param exempt         whether the element carries an {@code @ApiCompatExempt} annotation
 * @param exemptReason   exemption reason, {@code null} when not exempt
 * @param exemptChange   optional change selector (e.g. {@code REMOVED}), {@code null} for all
 */
public record ApiElement(
        String id,
        ApiKind kind,
        String owner,
        String name,
        String resultType,
        List<String> parameterTypes,
        ApiVisibility visibility,
        boolean staticFlag,
        boolean abstractFlag,
        boolean finalFlag,
        List<String> superTypes,
        SourceLocation location,
        boolean exempt,
        String exemptReason,
        String exemptChange
) {
}
