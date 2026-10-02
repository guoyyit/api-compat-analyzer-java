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

import static org.assertj.core.api.Assertions.assertThat;

/** Requirements 2, 3, 5: abstract/interface method additions and compatible additions. */
class InheritanceChangeTest {

    private static AnalysisService newService() {
        return new AnalysisService(new JavaSourceParser(), new ApiDiffEngine(),
                new IncrementalAnalyzer(), new SuppressionStore(), new AnalysisProperties());
    }

    private static ApiChange change(CompatReport report, String elementKey, ChangeKind kind) {
        return report.changes().stream()
                .filter(c -> c.elementKey().equals(elementKey) && c.kind() == kind)
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------
    // Requirement 2: new abstract method on a public inheritable type
    // ------------------------------------------------------------------

    @Test
    void newAbstractMethodOnConcreteTypeIsBreakingAndListsImplementor() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "Shape.java", Fixtures.SHAPE_V1,
                "Square.java", Fixtures.SHAPE_IMPL,
                "ShapeUser.java", Fixtures.SHAPE_USER), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "Shape.java", Fixtures.SHAPE_V2_ABSTRACT,
                "Square.java", Fixtures.SHAPE_IMPL,
                "ShapeUser.java", Fixtures.SHAPE_USER), "2.0");

        ApiChange declared = change(report, "METHOD:com.example.Shape#area()", ChangeKind.ADDED);
        assertThat(declared).isNotNull();
        assertThat(declared.breaking()).isTrue();

        ApiChange inherited = change(report, "METHOD:com.example.Square#area()", ChangeKind.ADDED);
        assertThat(inherited).isNotNull();
        assertThat(inherited.breaking()).isTrue();

        // Downstream implementor declaration site: Square.java (extends Shape).
        assertThat(inherited.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("Square.java"))
                .isNotEmpty()
                .allSatisfy(cs -> {
                    assertThat(cs.line()).isPositive();
                    assertThat(cs.column()).isPositive();
                    assertThat(cs.enclosingSymbol()).contains("Square");
                });
        // Usage sites of the implementor type are listed with file/line/symbol.
        assertThat(inherited.affectedCallSites())
                .extracting(CallSite::file).contains("ShapeUser.java");
        assertThat(inherited.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("ShapeUser.java"))
                .allSatisfy(cs -> assertThat(cs.enclosingSymbol()).contains("ShapeUser"));
    }

    // ------------------------------------------------------------------
    // Requirement 3: interface method additions
    // ------------------------------------------------------------------

    @Test
    void newInterfaceMethodBreaksOldImplementors() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "Repository.java", Fixtures.REPO_V1,
                "JdbcRepository.java", Fixtures.REPO_IMPL,
                "RepoUser.java", Fixtures.REPO_USER), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "Repository.java", Fixtures.REPO_V2_ABSTRACT,
                "JdbcRepository.java", Fixtures.REPO_IMPL,
                "RepoUser.java", Fixtures.REPO_USER), "2.0");

        ApiChange declared = change(report, "METHOD:com.example.Repository#save()", ChangeKind.ADDED);
        assertThat(declared).isNotNull();
        assertThat(declared.breaking()).isTrue();

        ApiChange inherited = change(report,
                "METHOD:com.example.JdbcRepository#save()", ChangeKind.ADDED);
        assertThat(inherited).isNotNull();
        assertThat(inherited.breaking()).isTrue();
        assertThat(inherited.affectedCallSites())
                .extracting(CallSite::file).contains("JdbcRepository.java");
        assertThat(inherited.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("JdbcRepository.java"))
                .allSatisfy(cs -> {
                    assertThat(cs.line()).isPositive();
                    assertThat(cs.enclosingSymbol()).contains("JdbcRepository");
                });
        // Polymorphic usage sites are attributed to the interface itself.
        assertThat(declared.affectedCallSites())
                .extracting(CallSite::file).contains("RepoUser.java");
        assertThat(declared.affectedCallSites())
                .filteredOn(cs -> cs.file().equals("RepoUser.java"))
                .allSatisfy(cs -> assertThat(cs.enclosingSymbol()).contains("RepoUser"));
    }

    @Test
    void newDefaultInterfaceMethodIsCompatible() {
        AnalysisService service = newService();
        service.setBaseline(Map.of(
                "Repository.java", Fixtures.REPO_V1,
                "JdbcRepository.java", Fixtures.REPO_IMPL), "1.0");

        CompatReport report = service.analyzeFull(Map.of(
                "Repository.java", Fixtures.REPO_V2_DEFAULT,
                "JdbcRepository.java", Fixtures.REPO_IMPL), "2.0");

        ApiChange declared = change(report, "METHOD:com.example.Repository#save()", ChangeKind.ADDED);
        assertThat(declared).isNotNull();
        assertThat(declared.breaking()).isFalse();

        // The default method is inherited but requires no implementation.
        ApiChange inherited = change(report,
                "METHOD:com.example.JdbcRepository#save()", ChangeKind.ADDED);
        assertThat(inherited).isNotNull();
        assertThat(inherited.breaking()).isFalse();
        assertThat(report.breakingCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Requirement 5: normal additions, overloads and widening stay compatible
    // ------------------------------------------------------------------

    @Test
    void newNormalMethodOverloadAndWideningStayCompatible() {
        String v1 = """
                package com.example;
                public class Api {
                    protected void m(int x) {}
                }
                """;
        String v2 = """
                package com.example;
                public class Api {
                    public void m(int x) {}
                    public void m(String s) {}
                    public void extra() {}
                }
                """;
        AnalysisService service = newService();
        service.setBaseline(Map.of("Api.java", v1), "1.0");
        CompatReport report = service.analyzeFull(Map.of("Api.java", v2), "2.0");

        assertThat(report.breakingCount()).isZero();
        assertThat(change(report, "METHOD:com.example.Api#m(String)", ChangeKind.ADDED)
                .breaking()).isFalse();
        assertThat(change(report, "METHOD:com.example.Api#extra()", ChangeKind.ADDED)
                .breaking()).isFalse();
        assertThat(change(report, "METHOD:com.example.Api#m(int)", ChangeKind.ACCESS_WIDENED)
                .breaking()).isFalse();
    }

    @Test
    void brandNewAbstractTypeAndInterfaceAreCompatibleAdditions() {
        String v2 = """
                package com.example;
                public abstract class Widget {
                    public abstract int spin();
                }
                """;
        AnalysisService service = newService();
        service.setBaseline(Map.of("Placeholder.java",
                "package com.example;\npublic class Placeholder {\n}\n"), "1.0");
        CompatReport report = service.analyzeFull(
                Map.of("Widget.java", v2,
                        "Placeholder.java",
                        "package com.example;\npublic class Placeholder {\n}\n"), "2.0");

        assertThat(report.breakingCount()).isZero();
        ApiChange typeAdded = change(report, "TYPE:com.example.Widget", ChangeKind.ADDED);
        assertThat(typeAdded).isNotNull();
        assertThat(typeAdded.breaking()).isFalse();
        ApiChange methodAdded = change(report, "METHOD:com.example.Widget#spin()", ChangeKind.ADDED);
        assertThat(methodAdded).isNotNull();
        assertThat(methodAdded.breaking()).isFalse();
    }
}
