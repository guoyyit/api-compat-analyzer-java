package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.analysis.DefaultIncrementalPlanner;
import com.github.guoyyit.apicompatanalyzerjava.analysis.FileDependencyIndex;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalConsistencyTest {

    /** Parser that records how many times each path was parsed. */
    private static final class CountingParser implements JavaSourceParser {
        private final JavaParserJavaSourceParser delegate = new JavaParserJavaSourceParser();
        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public ParsedUnit parse(SourceFile file, String content) {
            counts.computeIfAbsent(file.path(), k -> new AtomicInteger()).incrementAndGet();
            return delegate.parse(file, content);
        }

        int count(String path) {
            return counts.getOrDefault(path, new AtomicInteger()).get();
        }
    }

    private Sources project() {
        return Sources.create()
                .addClass("com.acme.api.Service",
                        "public class __NAME__ { public String api() { return \"v\"; } }")
                .addClass("com.acme.client.Client",
                        "import com.acme.api.Service;\n"
                                + "public class __NAME__ { public String run(Service s) { return s.api(); } }")
                .addClass("com.acme.Util",
                        "public class __NAME__ { public static int helper() { return 1; } }");
    }

    @Test
    void incrementalReparsesOnlyChangedFileAndDependentsAndEqualsFull() {
        CountingParser parser = new CountingParser();
        DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder(parser);
        ResourceBudget budget = new ResourceBudget(0, 0, 0, 0);

        SnapshotModel v1 = builder.build(project().reader(), budget);

        Sources v2Sources = project().addClass("com.acme.api.Service",
                "public class __NAME__ { public String api() { return \"changed\"; } "
                        + "public int newOne() { return 2; } }");

        // Changed-path set from raw content hashes.
        Map<String, String> newHashes = new java.util.TreeMap<>();
        try {
            for (SourceFile f : v2Sources.reader().listFiles()) {
                newHashes.put(f.path(),
                        com.github.guoyyit.apicompatanalyzerjava.util.Hashes
                                .sha256(v2Sources.reader().readContent(f)));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Set<String> plan = new DefaultIncrementalPlanner().plan(
                v1.fileHashes(), newHashes, FileDependencyIndex.from(v1));

        assertTrue(plan.contains("com/acme/api/Service.java"));
        // Client depends on Service, so it is in the re-parse closure.
        assertTrue(plan.contains("com/acme/client/Client.java"));
        // Unrelated Util is unchanged and not affected: excluded and not re-parsed.
        assertFalse(plan.contains("com/acme/Util.java"));

        SnapshotModel incremental = builder.buildIncremental(v2Sources.reader(), budget, v1, plan);
        // Use a fresh parser/builder for the full reference build so the parse counters
        // of the incremental path stay meaningful.
        SnapshotModel full = new DefaultSnapshotBuilder(new JavaParserJavaSourceParser())
                .build(v2Sources.reader(), budget);

        assertEquals(full.snapshotId(), incremental.snapshotId());
        assertEquals(full.elementsById().keySet(), incremental.elementsById().keySet());
        assertEquals(full.callSites(), incremental.callSites());

        // Util parsed exactly once in total (only during the initial full build).
        assertEquals(1, parser.count("com/acme/Util.java"));
        // Service parsed twice (v1 + v2), Client twice (v1 + affected re-parse).
        assertEquals(2, parser.count("com/acme/api/Service.java"));
        assertEquals(2, parser.count("com/acme/client/Client.java"));
    }

    @Test
    void addingAFileIsDetectedAndDeletingOneDropsIt() {
        DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder(
                new JavaParserJavaSourceParser());
        ResourceBudget budget = new ResourceBudget(0, 0, 0, 0);
        SnapshotModel v1 = builder.build(project().reader(), budget);

        Sources deleted = Sources.create()
                .addClass("com.acme.api.Service",
                        "public class __NAME__ { public String api() { return \"v\"; } }")
                .addClass("com.acme.client.Client",
                        "import com.acme.api.Service;\n"
                                + "public class __NAME__ { public String run(Service s) { return s.api(); } }");
        Map<String, String> newHashes = new java.util.TreeMap<>();
        try {
            for (SourceFile f : deleted.reader().listFiles()) {
                newHashes.put(f.path(),
                        com.github.guoyyit.apicompatanalyzerjava.util.Hashes
                                .sha256(deleted.reader().readContent(f)));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Set<String> plan = new DefaultIncrementalPlanner().plan(
                v1.fileHashes(), newHashes, FileDependencyIndex.from(v1));
        assertTrue(plan.contains("com/acme/Util.java"));

        SnapshotModel incremental = builder.buildIncremental(deleted.reader(), budget, v1, plan);
        assertFalse(incremental.units().containsKey("com/acme/Util.java"));
    }
}
