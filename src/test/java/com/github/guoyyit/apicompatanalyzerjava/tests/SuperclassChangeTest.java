package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.config.AnalysisProperties;
import com.github.guoyyit.apicompatanalyzerjava.diff.ApiDiffEngine;
import com.github.guoyyit.apicompatanalyzerjava.increment.IncrementalAnalyzer;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeKind;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.suppress.SuppressionStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 4: direct superclass replacement, removal and addition are breaking. */
class SuperclassChangeTest {

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    private static ApiChange change(CompatReport report, String elementKey, ChangeKind kind) {
        return report.changes().stream()
                .filter(c -> c.elementKey().equals(elementKey) && c.kind() == kind)
                .findFirst().orElse(null);
    }

    @Test
    void replacingSuperclassIsBreakingAndListsSubclassAndUsages() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_OLD_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_NEW_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER), "2.0");

        ApiChange superclassChange = change(report, "TYPE:com.example.ChildType",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(superclassChange).isNotNull();
        assertThat(superclassChange.breaking()).isTrue();
        assertThat(superclassChange.oldDetail()).isEqualTo("com.example.ParentOld");
        assertThat(superclassChange.newDetail()).isEqualTo("com.example.ParentNew");

        // Subclass declaration site.
        assertThat(superclassChange.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("ChildType.java"))
                .isNotEmpty()
                .allSatisfy(cs -> {
                    assertThat(cs.line()).isPositive();
                    assertThat(cs.column()).isPositive();
                    assertThat(cs.enclosingSymbol()).isEqualTo("com.example.ChildType");
                });
        // Usage sites of the affected subclass.
        assertThat(superclassChange.affectedCallSites())
                .extracting(CallSite::file).contains("ChildUser.java");

        // Member change propagated through the old parent: oldFeature() disappears
        // from ChildType and the call site is reported with file/line/symbol.
        ApiChange inheritedRemoved = change(report,
                "METHOD:com.example.ChildType#oldFeature()", ChangeKind.REMOVED);
        assertThat(inheritedRemoved).isNotNull();
        assertThat(inheritedRemoved.breaking()).isTrue();
        assertThat(inheritedRemoved.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("ChildUser.java"))
                .isNotEmpty()
                .allSatisfy(cs -> {
                    assertThat(cs.line()).isPositive();
                    assertThat(cs.enclosingSymbol()).contains("ChildUser");
                });
    }

    @Test
    void addingSuperclassIsBreaking() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "ParentNew.java", Fixtures.PARENT_NEW,
                "Alone.java", Fixtures.ALONE), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "ParentNew.java", Fixtures.PARENT_NEW,
                "Alone.java", Fixtures.ALONE_WITH_PARENT), "2.0");

        ApiChange change = change(report, "TYPE:com.example.Alone",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(change).isNotNull();
        assertThat(change.breaking()).isTrue();
        assertThat(change.oldDetail()).isEmpty();
        assertThat(change.newDetail()).isEqualTo("com.example.ParentNew");
        assertThat(change.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("Alone.java"))
                .isNotEmpty();
    }

    @Test
    void removingSuperclassIsBreaking() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "ParentNew.java", Fixtures.PARENT_NEW,
                "Alone.java", Fixtures.ALONE_WITH_PARENT), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "ParentNew.java", Fixtures.PARENT_NEW,
                "Alone.java", Fixtures.ALONE), "2.0");

        ApiChange change = change(report, "TYPE:com.example.Alone",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(change).isNotNull();
        assertThat(change.breaking()).isTrue();
        assertThat(change.oldDetail()).isEqualTo("com.example.ParentNew");
        assertThat(change.newDetail()).isEmpty();
    }

    @Test
    void unchangedSuperclassIsNotReported() {
        AnalysisService service = newService();
        Map<String, String> tree = Map.of(
                "ParentNew.java", Fixtures.PARENT_NEW,
                "Alone.java", Fixtures.ALONE_WITH_PARENT);
        service.setBaseline(tree, "1.0");
        CompatReport report = service.analyzeFull(tree, "2.0");
        assertThat(change(report, "TYPE:com.example.Alone",
                ChangeKind.SUPERCLASS_CHANGED)).isNull();
    }

    @Test
    void superclassChangeIsDetectedOnIncrementalPath() {
        Map<String, String> v1 = Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_OLD_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER);

        AnalysisService incremental = newService();
        incremental.setBaseline(v1, "1.0");
        incremental.analyzeFull(v1, "1.0");
        CompatReport inc = incremental.analyzeIncremental(
                Map.of("ChildType.java", Fixtures.CHILD_NEW_PARENT), Set.of(), "2.0");

        AnalysisService full = newService();
        full.setBaseline(v1, "1.0");
        CompatReport fullReport = full.analyzeFull(Map.of(
                "ParentOld.java", Fixtures.PARENT_OLD,
                "ParentNew.java", Fixtures.PARENT_NEW,
                "ChildType.java", Fixtures.CHILD_NEW_PARENT,
                "ChildUser.java", Fixtures.CHILD_USER), "2.0");

        ApiChange incChange = change(inc, "TYPE:com.example.ChildType",
                ChangeKind.SUPERCLASS_CHANGED);
        assertThat(incChange).isNotNull();
        assertThat(incChange.affectedCallSites())
                .extracting(CallSite::file).contains("ChildUser.java", "ChildType.java");

        assertThat(inc.reportVersion()).isEqualTo(fullReport.reportVersion());
        assertThat(inc.changes()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(fullReport.changes());
    }
}
