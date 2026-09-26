package com.github.guoyyit.apicompatanalyzerjava.support;

import com.github.guoyyit.apicompatanalyzerjava.web.InMemorySourceFileReader;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.SourceFileDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tiny in-memory project builder for tests. */
public final class Sources {

    private final Map<String, String> files = new LinkedHashMap<>();

    public static Sources create() {
        return new Sources();
    }

    public Sources add(String path, String content) {
        files.put(path, content);
        return this;
    }

    public Sources addClass(String fqn, String body) {
        String path = fqn.replace('.', '/') + ".java";
        int dot = fqn.lastIndexOf('.');
        String pkg = dot < 0 ? "" : fqn.substring(0, dot);
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        String content = (pkg.isEmpty() ? "" : "package " + pkg + ";\n") + body
                .replace("__NAME__", simple);
        files.put(path, content);
        return this;
    }

    public InMemorySourceFileReader reader() {
        List<SourceFileDto> dtos = new ArrayList<>();
        files.forEach((path, content) -> dtos.add(new SourceFileDto(path, content)));
        return new InMemorySourceFileReader(dtos);
    }

    public Map<String, String> asMap() {
        return Map.copyOf(files);
    }
}
