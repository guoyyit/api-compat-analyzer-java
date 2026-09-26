package com.github.guoyyit.apicompatanalyzerjava.support;

import com.github.guoyyit.apicompatanalyzerjava.analysis.DefaultApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.analysis.DefaultImpactResolver;
import com.github.guoyyit.apicompatanalyzerjava.analysis.DefaultIncrementalPlanner;
import com.github.guoyyit.apicompatanalyzerjava.config.AnalyzerProperties;
import com.github.guoyyit.apicompatanalyzerjava.parse.DefaultSnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaParserJavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.DefaultAnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.storage.FileBaselineRepository;

import java.nio.file.Path;

public final class TestServices {

    private TestServices() {
    }

    public static DefaultAnalysisService create(Path tempDir) {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.setBaselineDir(tempDir.resolve("baselines").toString());
        properties.setTimeoutMillis(30_000L);
        return new DefaultAnalysisService(
                new DefaultSnapshotBuilder(new JavaParserJavaSourceParser()),
                new DefaultApiDiffEngine(),
                new DefaultImpactResolver(),
                new DefaultIncrementalPlanner(),
                new FileBaselineRepository(properties),
                properties);
    }
}
