package com.github.guoyyit.apicompatanalyzerjava.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.apicompatanalyzerjava.model.AccessLevel;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiSnapshot;
import com.github.guoyyit.apicompatanalyzerjava.model.ElementKind;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persists a baseline snapshot (API elements plus exemption keys) as JSON so
 * that later versions can be compared against it, satisfying the requirement
 * that exemptions declared in source become part of the baseline.
 */
@Component
public class BaselineStore {

    public record BaselineData(ApiSnapshot snapshot, Set<String> suppressedKeys, String fingerprint) {
        public BaselineData(ApiSnapshot snapshot, Set<String> suppressedKeys) {
            this(snapshot, suppressedKeys, "");
        }
    }

    record ElementDto(String kind, String ownerType, String name, String returnType,
                      List<String> parameterTypes, String access, Set<String> modifiers,
                      String sourceFile, int line) {
    }

    record BaselineDto(String version, String fingerprint, Set<String> suppressedKeys,
                       List<ElementDto> elements,
                       Map<String, List<String>> typeSupertypes,
                       Map<String, String> typeSuperclasses) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void save(ApiSnapshot snapshot, Set<String> suppressedKeys, Path file) {
        save(snapshot, suppressedKeys, "", file);
    }

    public void save(ApiSnapshot snapshot, Set<String> suppressedKeys, String fingerprint, Path file) {
        List<ElementDto> elements = snapshot.elements().values().stream()
                .map(el -> new ElementDto(el.kind().name(), el.ownerType(), el.name(),
                        el.returnType(), el.parameterTypes(), el.access().name(),
                        el.modifiers(), el.sourceFile(), el.line()))
                .toList();
        BaselineDto dto = new BaselineDto(snapshot.version(), fingerprint, suppressedKeys, elements,
                snapshot.typeSupertypes(), snapshot.typeSuperclasses());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dto);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save baseline to " + file, e);
        }
    }

    public BaselineData load(Path file) {
        BaselineDto dto;
        try {
            dto = objectMapper.readValue(file.toFile(), BaselineDto.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load baseline from " + file, e);
        }
        Map<String, ApiElement> elements = new LinkedHashMap<>();
        for (ElementDto d : dto.elements()) {
            ApiElement el = new ApiElement(ElementKind.valueOf(d.kind()), d.ownerType(), d.name(),
                    d.returnType(), d.parameterTypes() == null ? List.of() : d.parameterTypes(),
                    AccessLevel.valueOf(d.access()),
                    d.modifiers() == null ? Set.of() : d.modifiers(),
                    d.sourceFile(), d.line());
            elements.putIfAbsent(el.key(), el);
        }
        ApiSnapshot snapshot = new ApiSnapshot(dto.version(), elements, Map.of(), Map.of(), Map.of(),
                dto.typeSupertypes() == null ? Map.of() : dto.typeSupertypes(),
                dto.typeSuperclasses() == null ? Map.of() : dto.typeSuperclasses());
        Set<String> suppressed = dto.suppressedKeys() == null ? Set.of() : dto.suppressedKeys();
        return new BaselineData(snapshot, suppressed, dto.fingerprint() == null ? "" : dto.fingerprint());
    }
}
