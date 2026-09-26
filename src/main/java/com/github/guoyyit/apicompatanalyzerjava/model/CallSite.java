package com.github.guoyyit.apicompatanalyzerjava.model;

/**
 * A single reference to an API symbol located in source code.
 *
 * @param path           source file path (relative to snapshot root)
 * @param line           1-based line number
 * @param symbol         hit symbol as written (simple or qualified name)
 * @param resolvedOwner  best-effort resolved fully qualified owner type, may be {@code null}
 * @param kind           reference kind
 * @param argumentCount  number of arguments / 0 for non invocations
 * @param snippet        trimmed source line for human inspection
 */
public record CallSite(
        String path,
        int line,
        String symbol,
        String resolvedOwner,
        CallSiteKind kind,
        int argumentCount,
        String snippet
) {
}
