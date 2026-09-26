package com.github.guoyyit.apicompatanalyzerjava.storage;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalyzerProperties;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.parse.DefaultSnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaParserJavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ResourceBudget;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisRejectedException;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBaselineRepositoryTest {

    private AnalyzerProperties props(Path dir) {
        AnalyzerProperties properties = new AnalyzerProperties();
        properties.setBaselineDir(dir.toString());
        return properties;
    }

    @Test
    void savesAndLoadsSnapshotIncludingSuppressions(@TempDir Path dir) {
        var repo = new FileBaselineRepository(props(dir));
        var builder = new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
        Sources sources = Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { @ApiCompatExempt(reason=\"accepted\") public void a() {} }");
        SnapshotModel snapshot = builder.build(sources.reader(), new ResourceBudget(0, 0, 0, 0));

        repo.save("v1", snapshot);
        assertTrue(repo.exists("v1"));

        SnapshotModel loaded = repo.load("v1");
        assertEquals(snapshot.snapshotId(), loaded.snapshotId());
        assertEquals(snapshot.elementsById().keySet(), loaded.elementsById().keySet());
        assertEquals(1, loaded.suppressions().size());
        assertEquals("accepted", loaded.suppressions().get(0).reason());
        assertEquals(snapshot, loaded);
    }

    @Test
    void secondSaveIsAtomicAndOverwrites(@TempDir Path dir) {
        var repo = new FileBaselineRepository(props(dir));
        var builder = new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
        SnapshotModel first = builder.build(Sources.create().addClass("com.acme.A",
                "public class __NAME__ {}").reader(), new ResourceBudget(0, 0, 0, 0));
        SnapshotModel second = builder.build(Sources.create().addClass("com.acme.A",
                "public class __NAME__ { public int x; }").reader(),
                new ResourceBudget(0, 0, 0, 0));

        repo.save("v1", first);
        repo.save("v1", second);
        assertEquals(second.snapshotId(), repo.load("v1").snapshotId());
    }

    @Test
    void missingBaselineIsRejectedAndDeleteWorks(@TempDir Path dir) {
        var repo = new FileBaselineRepository(props(dir));
        assertFalse(repo.exists("nope"));
        assertThrows(AnalysisRejectedException.class, () -> repo.load("nope"));

        var builder = new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
        SnapshotModel snapshot = builder.build(Sources.create().addClass("com.acme.A",
                "public class __NAME__ {}").reader(), new ResourceBudget(0, 0, 0, 0));
        repo.save("tmp", snapshot);
        repo.delete("tmp");
        assertFalse(repo.exists("tmp"));
    }

    @Test
    void rejectsUnsafeBaselineIds(@TempDir Path dir) {
        var repo = new FileBaselineRepository(props(dir));
        assertThrows(AnalysisRejectedException.class,
                () -> repo.exists("../escape"));
    }
}
