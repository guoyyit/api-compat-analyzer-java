package com.github.guoyyit.apicompatanalyzerjava.service.support;

import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFile;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFileReader;
import com.github.guoyyit.apicompatanalyzerjava.util.Hashes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.TreeMap;

/** Helpers for deciding which files changed before committing to a re-parse. */
public final class IncrementalSupport {

    private IncrementalSupport() {
    }

    /**
     * Hash every file's raw UTF-8 content. Reading is unavoidable because the client may
     * provide contents in-memory; parsing (the expensive step) still only happens for the
     * changed/affected set.
     */
    public static Map<String, String> contentHashes(SourceFileReader reader) {
        try {
            Map<String, String> hashes = new TreeMap<>();
            for (SourceFile file : reader.listFiles()) {
                hashes.put(file.path(), Hashes.sha256(reader.readContent(file)));
            }
            return hashes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
