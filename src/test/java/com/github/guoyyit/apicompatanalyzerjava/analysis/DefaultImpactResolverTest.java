package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeNature;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatVerdict;
import com.github.guoyyit.apicompatanalyzerjava.model.ImpactFinding;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.parse.DefaultSnapshotBuilder;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaParserJavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ResourceBudget;
import com.github.guoyyit.apicompatanalyzerjava.support.Sources;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultImpactResolverTest {

    private final DefaultSnapshotBuilder builder =
            new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
    private final DefaultApiDiffEngine diff = new DefaultApiDiffEngine();
    private final DefaultImpactResolver resolver = new DefaultImpactResolver();

    private SnapshotModel build(Sources sources) {
        return builder.build(sources.reader(), new ResourceBudget(0, 0, 0, 0));
    }

    @Test
    void locatesConcreteCallerFileLineAndSymbolForRemovedMethod() {
        Sources base = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public String greet(String name) { return name; } }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.greet(\"x\"); } }");
        Sources next = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public String use(Service s) { return s.greet(\"x\"); } }");

        SnapshotModel current = build(next);
        List<ApiChange> changes = diff.diff(build(base), current);
        List<ImpactFinding> findings = resolver.resolve(changes, current);

        ImpactFinding finding = findings.stream()
                .filter(f -> f.elementId().equals("com.acme.Service#greet(java.lang.String)"))
                .findFirst().orElseThrow();
        assertEquals(1, finding.callSites().size());
        CallSite site = finding.callSites().get(0);
        assertEquals("com/acme/Caller.java", site.path());
        assertEquals(2, site.line());
        assertEquals("greet", site.symbol());
        assertTrue(site.snippet().contains("greet"));
    }

    @Test
    void followsInheritanceForOverriddenAndInheritedCalls() {
        Sources base = Sources.create()
                .addClass("com.acme.Base",
                        "public class __NAME__ { public void hook() {} }")
                .addClass("com.acme.Derived",
                        "public class __NAME__ extends Base { public void run() { hook(); } }");
        Sources next = Sources.create()
                .addClass("com.acme.Base", "public class __NAME__ { }")
                .addClass("com.acme.Derived",
                        "public class __NAME__ extends Base { public void run() { hook(); } }");

        SnapshotModel current = build(next);
        List<ImpactFinding> findings = resolver.resolve(diff.diff(build(base), current), current);

        ImpactFinding finding = findings.stream()
                .filter(f -> f.elementId().equals("com.acme.Base#hook()")).findFirst().orElseThrow();
        assertTrue(finding.callSites().stream().anyMatch(s ->
                s.path().equals("com/acme/Derived.java") && s.symbol().equals("hook")));
    }

    @Test
    void findsConstructorAndTypeUsages() {
        Sources base = Sources.create()
                .addClass("com.acme.Payload", "public class __NAME__ { public Payload() {} }")
                .addClass("com.acme.Client",
                        "public class __NAME__ { public Object make() { return new Payload(); } }");
        Sources next = Sources.create()
                .addClass("com.acme.Payload", "public class __NAME__ { private __NAME__() {} }")
                .addClass("com.acme.Client",
                        "public class __NAME__ { public Object make() { return new Payload(); } }");

        SnapshotModel current = build(next);
        List<ImpactFinding> findings = resolver.resolve(diff.diff(build(base), current), current);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().flatMap(f -> f.callSites().stream())
                .anyMatch(s -> s.path().equals("com/acme/Client.java")
                        && s.symbol().equals("Payload")));
    }

    @Test
    void suppressedBreakingChangeProducesNoImpactFinding() {
        Sources base = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { public void gone() {} }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public void use(Service s) { s.gone(); } }");
        Sources next = Sources.create()
                .addClass("com.acme.Service",
                        "public class __NAME__ { @ApiCompatExempt(reason=\"accepted\") private void gone() {} }")
                .addClass("com.acme.Caller",
                        "public class __NAME__ { public void use(Service s) { s.gone(); } }");

        SnapshotModel current = build(next);
        List<ImpactFinding> findings = resolver.resolve(diff.diff(build(base), current), current);
        assertTrue(findings.isEmpty());
    }
}
