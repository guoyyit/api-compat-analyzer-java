package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.Suppression;

import java.util.List;
import java.util.Set;

/**
 * Parse output of one source file.
 *
 * @param path          file path relative to the snapshot root
 * @param packageName   declared package, empty string for default package
 * @param apiElements   public/protected API elements declared in the file
 * @param callSites     references found in method bodies and type headers
 * @param suppressions  exemption annotations found in the file
 * @param declaredTypes fully qualified names of all top level and nested types in the file
 * @param usedTypes     fully qualified names this file depends on (imports, headers, bodies)
 * @param imports       import declarations
 */
public record ParsedUnit(
        String path,
        String contentHash,
        String packageName,
        List<ApiElement> apiElements,
        List<CallSite> callSites,
        List<Suppression> suppressions,
        Set<String> declaredTypes,
        Set<String> usedTypes,
        List<ImportEntry> imports
) {

    /** One import declaration (regular, static and on-demand). */
    public record ImportEntry(String simpleName, String qualifiedName, boolean isStatic, boolean onDemand) {
    }
}
