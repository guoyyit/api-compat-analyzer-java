package com.github.guoyyit.apicompatanalyzerjava.analysis;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiChange;
import com.github.guoyyit.apicompatanalyzerjava.model.ChangeNature;
import com.github.guoyyit.apicompatanalyzerjava.model.CompatVerdict;
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

class DefaultApiDiffEngineTest {

    private final DefaultSnapshotBuilder builder =
            new DefaultSnapshotBuilder(new JavaParserJavaSourceParser());
    private final DefaultApiDiffEngine engine = new DefaultApiDiffEngine();

    private SnapshotModel snapshot(Sources sources) {
        return builder.build(sources.reader(), new ResourceBudget(0, 0, 0, 0));
    }

    private ApiChange find(List<ApiChange> changes, String id, ChangeNature nature) {
        return changes.stream()
                .filter(c -> c.elementId().equals(id) && c.nature() == nature)
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no " + nature + " for " + id + " in " + changes));
    }

    @Test
    void addedMethodIsCompatibleButAbstractAdditionToExistingTypeIsBreaking() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a() {} }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a() {} public void b() {} "
                        + "public abstract int c(); }"));

        List<ApiChange> changes = engine.diff(base, next);
        ApiChange addedB = find(changes, "com.acme.Foo#b()", ChangeNature.ADDED);
        assertEquals(CompatVerdict.COMPATIBLE, addedB.verdict());
        ApiChange addedC = find(changes, "com.acme.Foo#c()", ChangeNature.ADDED);
        assertEquals(CompatVerdict.BREAKING, addedC.verdict());
    }

    @Test
    void removedPublicMethodIsBreaking() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a() {} }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { }"));
        ApiChange change = find(engine.diff(base, next),
                "com.acme.Foo#a()", ChangeNature.REMOVED);
        assertEquals(CompatVerdict.BREAKING, change.verdict());
    }

    @Test
    void wideningVisibilityIsCompatibleAndNarrowingBreaks() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { protected void a() {} public void b() {} }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a() {} void b() {} }"));

        List<ApiChange> changes = engine.diff(base, next);
        // Widening protected -> public is compatible: no change emitted for a().
        assertFalse(changes.stream().anyMatch(c -> c.elementId().equals("com.acme.Foo#a()")));
        ApiChange narrowed = find(changes, "com.acme.Foo#b()", ChangeNature.SIGNATURE_CHANGED);
        assertEquals(CompatVerdict.BREAKING, narrowed.verdict());
    }

    @Test
    void overloadedAdditionIsNotConfusedWithSignatureChange() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a(int x) {} }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a(int x) {} public void a(String s) {} }"));
        List<ApiChange> changes = engine.diff(base, next);
        assertEquals(1, changes.size());
        assertEquals(ChangeNature.ADDED, changes.get(0).nature());
        assertTrue(changes.get(0).elementId().contains("java.lang.String"));
    }

    @Test
    void returnTypeChangeIsBreaking() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public int a() { return 1; } }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public String a() { return \"\"; } }"));
        ApiChange change = find(engine.diff(base, next),
                "com.acme.Foo#a()", ChangeNature.SIGNATURE_CHANGED);
        assertEquals(CompatVerdict.BREAKING, change.verdict());
        assertTrue(change.reason().contains("return type"));
    }

    @Test
    void exemptionSuppressesBreakingChange() {
        SnapshotModel base = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { public void a() {} }"));
        SnapshotModel next = snapshot(Sources.create().addClass("com.acme.Foo",
                "public class __NAME__ { @ApiCompatExempt(reason=\"ok\") private void a() {} }"));
        ApiChange change = find(engine.diff(base, next),
                "com.acme.Foo#a()", ChangeNature.SIGNATURE_CHANGED);
        assertTrue(change.suppressed());
        assertEquals("ok", change.suppressReason());
    }
}
