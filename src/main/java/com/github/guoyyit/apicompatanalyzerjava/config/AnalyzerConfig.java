package com.github.guoyyit.apicompatanalyzerjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Enables typed analyzer configuration. */
@Configuration
@EnableConfigurationProperties(AnalyzerProperties.class)
public class AnalyzerConfig {
}
