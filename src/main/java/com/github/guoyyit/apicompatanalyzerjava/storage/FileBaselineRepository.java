package com.github.guoyyit.apicompatanalyzerjava.storage;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalyzerProperties;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisRejectedException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JSON file based {@link BaselineRepository}.
 *
 * <p>Writes are atomic (temp file + move), so a crashed publish never leaves a
 * half-written baseline that later runs could read.
 */
@Component
public class FileBaselineRepository implements BaselineRepository {

    private final Path baselineDir;
    private final SnapshotJsonCodec codec = new SnapshotJsonCodec();

    public FileBaselineRepository(AnalyzerProperties properties) {
        this.baselineDir = Paths.get(properties.getBaselineDir());
    }

    private Path fileFor(String baselineId) {
        if (baselineId == null || !baselineId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new AnalysisRejectedException(
                    "baselineId must match [A-Za-z0-9._-]{1,128}");
        }
        return baselineDir.resolve(baselineId + ".json");
    }

    @Override
    public void save(String baselineId, SnapshotModel snapshot) {
        Path target = fileFor(baselineId);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".baseline-", ".tmp");
            codec.writeFile(tmp, snapshot);
            try {
                Files.move(tmp, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist baseline " + baselineId, e);
        }
    }

    @Override
    public SnapshotModel load(String baselineId) {
        Path target = fileFor(baselineId);
        if (!Files.isRegularFile(target)) {
            throw new AnalysisRejectedException("baseline not found: " + baselineId);
        }
        try {
            return codec.readFile(target);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read baseline " + baselineId, e);
        }
    }

    @Override
    public boolean exists(String baselineId) {
        return Files.isRegularFile(fileFor(baselineId));
    }

    @Override
    public void delete(String baselineId) {
        try {
            Files.deleteIfExists(fileFor(baselineId));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete baseline " + baselineId, e);
        }
    }
}
