package com.github.guoyyit.apicompatanalyzerjava.web.dto;

import java.util.List;

/**
 * Incremental update request: {@code files} is the complete current file set;
 * unchanged files still need path (content may be {@code null} when {@code contentHashes}
 * matches what the server cached).
 */
public record UpdateAndAnalyzeRequest(
        String baselineId,
        List<SourceFileDto> files,
        com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions options
) {
}
