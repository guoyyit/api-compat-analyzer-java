package com.github.guoyyit.apicompatanalyzerjava.web.dto;

import java.util.List;

/** Request body for publishing a baseline snapshot. */
public record PublishBaselineRequest(
        String baselineId,
        List<SourceFileDto> files,
        com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions options
) {
}
