package com.github.guoyyit.apicompatanalyzerjava.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON serialization for {@link SnapshotModel} baselines.
 *
 * <p>Output is deterministic (sorted map keys, no timestamps) so storing the same
 * snapshot twice yields byte-identical files and content hashes stay reproducible.
 */
public final class SnapshotJsonCodec {

    private final ObjectMapper mapper;

    public SnapshotJsonCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public String toJson(SnapshotModel snapshot) {
        return canonicalJson(snapshot);
    }

    /** Deterministic JSON of any value; used for reproducible report fingerprints. */
    public String canonicalJson(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode value", e);
        }
    }

    public SnapshotModel fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<SnapshotModel>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to decode snapshot", e);
        }
    }

    public void writeFile(Path file, SnapshotModel snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, toJson(snapshot), StandardCharsets.UTF_8);
    }

    public SnapshotModel readFile(Path file) throws IOException {
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }
}
