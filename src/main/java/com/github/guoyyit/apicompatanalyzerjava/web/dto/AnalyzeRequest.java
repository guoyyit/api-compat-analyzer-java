package com.github.guoyyit.apicompatanalyzerjava.web.dto;

import java.util.List;

/**
 * Analysis request over in-memory source files.
 *
 * @param baselineFiles baseline snapshot files; {@code null} means use {@code baselineId}
 * @param currentFiles  current snapshot files
 * @param baselineId    stored baseline selector when {@code baselineFiles} is absent
 * @param options       optional time / memory boundaries
 */
public record AnalyzeRequest(
        List<SourceFileDto> baselineFiles,
        List<SourceFileDto> currentFiles,
        String baselineId,
        com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions options
) {
}
