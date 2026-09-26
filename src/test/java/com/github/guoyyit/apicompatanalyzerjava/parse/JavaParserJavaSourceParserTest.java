package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiKind;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiVisibility;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSiteKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserJavaSourceParserTest {

    private final JavaParserJavaSourceParser parser = new JavaParserJavaSourceParser();

    private ParsedUnit parse(String content) {
        SourceFile file = new SourceFile("com/acme/Foo.java", content.length());
        return parser.parse(file, content);
    }

    @Test
    void extractsPublicTypesMethodsFieldsWithStableIds() {
        ParsedUnit unit = parse("""
                package com.acme;
                public class Foo {
                    public int count;
                    public String name() { return "x"; }
                    private void hidden() {}
                    protected void overloaded(String s) {}
                }
                """);

        Map<String, ApiElement> byId = unit.apiElements().stream()
                .collect(Collectors.toMap(ApiElement::id, Function.identity()));
        assertTrue(byId.containsKey("com.acme.Foo"));
        assertTrue(byId.containsKey("com.acme.Foo#count"));
        assertTrue(byId.containsKey("com.acme.Foo#name()"));
        assertTrue(byId.containsKey("com.acme.Foo#overloaded(java.lang.String)"));
        // Private members are still modeled (needed to detect visibility narrowing),
        // but flagged PRIVATE so the diff engine never treats them as public API.
        assertEquals(ApiVisibility.PRIVATE, byId.get("com.acme.Foo#hidden()").visibility());

        ApiElement name = byId.get("com.acme.Foo#name()");
        assertEquals(ApiKind.METHOD, name.kind());
        assertEquals("java.lang.String", name.resultType());
        assertEquals(ApiVisibility.PUBLIC, name.visibility());
    }

    @Test
    void resolvesParameterTypesWithImportsAndArrays() {
        ParsedUnit unit = parse("""
                package com.acme;
                import java.util.List;
                public class Foo {
                    public void take(List<String> values, byte[] raw, int n) {}
                }
                """);
        ApiElement method = unit.apiElements().stream()
                .filter(e -> e.name().equals("take")).findFirst().orElseThrow();
        assertEquals(List.of("java.util.List", "byte[]", "int"), method.parameterTypes());
    }

    @Test
    void collectsCallSitesWithFileLineAndSymbol() {
        ParsedUnit unit = parse("""
                package com.acme;
                public class Foo {
                    private Bar bar = new Bar();
                    public String go() {
                        Helper.staticUtil();
                        return bar.name();
                    }
                }
                class Helper { static void staticUtil() {} }
                class Bar { String name() { return ""; } }
                """);
        List<CallSite> constructorCalls = unit.callSites().stream()
                .filter(c -> c.kind() == CallSiteKind.CONSTRUCTOR_CALL
                        && c.symbol().equals("Bar"))
                .toList();
        assertEquals(1, constructorCalls.size());
        CallSite site = constructorCalls.get(0);
        assertEquals("com/acme/Foo.java", site.path());
        assertEquals(3, site.line());
        assertNotNull(site.resolvedOwner());

        assertTrue(unit.callSites().stream()
                .anyMatch(c -> c.kind() == CallSiteKind.METHOD_CALL
                        && c.symbol().equals("staticUtil")));
        assertTrue(unit.callSites().stream()
                .anyMatch(c -> c.kind() == CallSiteKind.METHOD_CALL
                        && c.symbol().equals("name")));
    }

    @Test
    void recordsExemptionAnnotationWithReason() {
        ParsedUnit unit = parse("""
                package com.acme;
                public class Foo {
                    @ApiCompatExempt(reason = "planned removal", change = "REMOVED")
                    public void oldApi() {}
                }
                """);
        ApiElement oldApi = unit.apiElements().stream()
                .filter(e -> e.id().equals("com.acme.Foo#oldApi()")).findFirst().orElseThrow();
        assertTrue(oldApi.exempt());
        assertEquals("planned removal", oldApi.exemptReason());
        assertEquals("REMOVED", oldApi.exemptChange());
        assertEquals(1, unit.suppressions().size());
    }

    @Test
    void rejectsUnparseableSource() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("this is not java"));
    }
}
