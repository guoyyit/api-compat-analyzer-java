package com.github.guoyyit.apicompatanalyzerjava.web;

import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFile;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFileReader;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.SourceFileDto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SourceFileReader} backed by request body contents.
 *
 * <p>Files are stored once in insertion order; duplicate paths overwrite earlier copies
 * so a repeated request describes a deterministic file set.
 */
public class InMemorySourceFileReader implements SourceFileReader {

    private final Map<String, String> contents = new LinkedHashMap<>();
    private final Map<String, Long> delays = new LinkedHashMap<>();

    public InMemorySourceFileReader(List<SourceFileDto> files) {
        if (files != null) {
            for (SourceFileDto file : files) {
                if (file != null && file.path() != null && !file.path().isBlank()) {
                    contents.put(file.path(), file.content() == null ? "" : file.content());
                    if (file.delayMillis() > 0) {
                        delays.put(file.path(), file.delayMillis());
                    }
                }
            }
        }
    }

    @Override
    public List<SourceFile> listFiles() {
        List<SourceFile> files = new ArrayList<>();
        contents.forEach((path, content) ->
                files.add(new SourceFile(path, content.getBytes(StandardCharsets.UTF_8).length)));
        return files;
    }

    @Override
    public String readContent(SourceFile file) throws IOException {
        Long delay = delays.get(file.path());
        if (delay != null && delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("delayed read interrupted", e);
            }
        }
        String content = contents.get(file.path());
        if (content == null) {
            throw new IOException("unknown file: " + file.path());
        }
        return content;
    }
}
