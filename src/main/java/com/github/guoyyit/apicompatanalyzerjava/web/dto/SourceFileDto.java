package com.github.guoyyit.apicompatanalyzerjava.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One source file in a request body.
 *
 * @param path        path relative to the snapshot root
 * @param content     UTF-8 source content
 * @param delayMillis optional artificial read delay (milliseconds), used to exercise
 *                    the time-boundary/timeout path over HTTP; 0 means no delay
 */
public final class SourceFileDto {

    private final String path;
    private final String content;
    private final long delayMillis;

    @JsonCreator
    public SourceFileDto(@JsonProperty("path") String path,
                         @JsonProperty("content") String content,
                         @JsonProperty("delayMillis") Long delayMillis) {
        this.path = path;
        this.content = content;
        this.delayMillis = delayMillis == null ? 0L : delayMillis;
    }

    /** Backwards compatible constructor for callers that do not set a delay. */
    public SourceFileDto(String path, String content) {
        this(path, content, 0L);
    }

    public String getPath() {
        return path;
    }

    public String getContent() {
        return content;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public String path() {
        return path;
    }

    public String content() {
        return content;
    }

    public long delayMillis() {
        return delayMillis;
    }
}
