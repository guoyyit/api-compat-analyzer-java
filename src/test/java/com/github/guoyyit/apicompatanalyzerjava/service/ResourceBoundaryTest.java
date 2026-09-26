package com.github.guoyyit.apicompatanalyzerjava.service;

import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.AnalysisBudgetException;
import com.github.guoyyit.apicompatanalyzerjava.parse.DefaultSnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaParserJavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import com.github.guoyyit.apicompatanalyzerjava.parse.ResourceBudget;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFile;
import com.github.guoyyit.apicompatanalyzerjava.parse.SourceFileReader;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import com.github.guoyyit.apicompatanalyzerjava.support.TestServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceBoundaryTest {

    private Sources tiny() {
        return Sources.create().addClass("com.acme.A",
                "public class __NAME__ { public void x() {} }");
    }

    @Test
    void timeoutAbortsAndPublishesNoPartialReport(@TempDir Path dir) {
        var service = TestServices.create(dir);
        SourceFileReader slow = new SourceFileReader() {
            @Override
            public List<SourceFile> listFiles() {
                return List.of(new SourceFile("com/acme/A.java", 1));
            }

            @Override
            public String readContent(SourceFile file) throws IOException {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                return "package com.acme; public class A {}";
            }
        };
        assertThrows(AnalysisTimeoutException.class, () -> service.analyzeSnapshots(
                tiny().reader(), slow, new AnalysisOptions(100, 0, 0, 0)));
        // No report must have been published.
        assertEquals(null, service.latestReport());
    }

    @Test
    void maxFilesBudgetRejectsOversizedSnapshotDirectly() {
        SourceFileReader reader = new SourceFileReader() {
            @Override
            public List<SourceFile> listFiles() {
                List<SourceFile> files = new ArrayList<>();
                files.add(new SourceFile("a/A.java", 1));
                files.add(new SourceFile("b/B.java", 1));
                return files;
            }

            @Override
            public String readContent(SourceFile file) {
                return "class X {}";
            }
        };
        ResourceBudget budget = new ResourceBudget(0, 1, 0, 0);
        DefaultSnapshotBuilder builder =
                new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
        assertThrows(AnalysisBudgetException.class, () -> builder.build(reader, budget));
    }

    @Test
    void oversizedFileIsRejected() {
        String content = "class A { int v = " + "1".repeat(9_900) + "; }";
        SourceFileReader reader = new SourceFileReader() {
            @Override
            public List<SourceFile> listFiles() {
                return List.of(new SourceFile("a/A.java",
                        content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
            }

            @Override
            public String readContent(SourceFile file) {
                return content;
            }
        };
        ResourceBudget budget = new ResourceBudget(0, 0, 100, 0);
        DefaultSnapshotBuilder builder =
                new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
        assertThrows(AnalysisBudgetException.class, () -> builder.build(reader, budget));
    }

    @Test
    void afterFailedRunPreviousReportRemainsObservable(@TempDir Path dir) {
        var service = TestServices.create(dir);
        AnalysisReport good = service.analyzeSnapshots(
                tiny().reader(), tiny().reader(), AnalysisOptions.defaults());
        assertNotNull(good);

        SourceFileReader broken = new SourceFileReader() {
            @Override
            public List<SourceFile> listFiles() {
                return List.of(new SourceFile("a/A.java", 1));
            }

            @Override
            public String readContent(SourceFile file) {
                throw new IllegalStateException("boom");
            }
        };
        assertThrows(RuntimeException.class, () -> service.analyzeSnapshots(
                tiny().reader(), broken, AnalysisOptions.defaults()));
        // The single previous consistent version is still readable.
        assertEquals(good.reportId(), service.latestReport().reportId());
    }
}
