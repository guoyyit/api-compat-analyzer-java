package com.github.guoyyit.apicompatanalyzerjava.tests;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.parse.JavaSourceParser;
import com.github.guoyyit.apicompatanalyzerjava.parse.ParsedUnit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaSourceParserTest {

    private final JavaSourceParser parser = new JavaSourceParser();

    @Test
    void extractsPublicAndProtectedApiOnly() {
        ParsedUnit unit = parser.parse("Service.java", Fixtures.SERVICE_V1);

        assertThat(unit.apiElements())
                .extracting(ApiElement::key)
                .contains(
                        "TYPE:com.example.Service",
                        "FIELD:com.example.Service#LIMIT",
                        "METHOD:com.example.Service#compute(int)",
                        "METHOD:com.example.Service#name()",
                        "METHOD:com.example.Service#hook()")
                .doesNotContain(
                        "METHOD:com.example.Service#packagePrivate()",
                        "METHOD:com.example.Service#hidden()");
    }

    @Test
    void recordsCallSitesWithFileLineAndSymbol() {
        ParsedUnit unit = parser.parse("Caller.java", Fixtures.CALLER);

        List<CallSite> computeCalls = unit.callSites().stream()
                .filter(c -> c.referencedKey().equals("MEMBER:com.example.Service#compute/1"))
                .toList();
        assertThat(computeCalls).hasSize(1);
        CallSite call = computeCalls.get(0);
        assertThat(call.file()).isEqualTo("Caller.java");
        assertThat(call.line()).isEqualTo(5);
        assertThat(call.enclosingSymbol()).contains("Caller");

        assertThat(unit.callSites())
                .extracting(CallSite::referencedKey)
                .contains("FIELD:com.example.Service#LIMIT",
                        "MEMBER:com.example.Service#<init>/0",
                        "TYPE:com.example.Service");
    }

    @Test
    void detectsAnnotationSuppression() {
        ParsedUnit unit = parser.parse("Legacy.java", Fixtures.IGNORED_ANNOTATION);
        assertThat(unit.suppressedKeys())
                .contains("METHOD:com.example.Legacy#oldWay()")
                .doesNotContain("METHOD:com.example.Legacy#kept()");
    }

    @Test
    void detectsCommentSuppression() {
        ParsedUnit unit = parser.parse("Legacy2.java", Fixtures.IGNORED_COMMENT);
        assertThat(unit.suppressedKeys()).contains("METHOD:com.example.Legacy2#oldWay()");
    }

    @Test
    void recordsSupertypesAndDependencies() {
        ParsedUnit unit = parser.parse("Child.java", Fixtures.CHILD);
        assertThat(unit.typeSupertypes())
                .containsEntry("com.example.Child", List.of("com.example.Base"));
        assertThat(unit.typeSuperclasses())
                .containsEntry("com.example.Child", "com.example.Base");
        assertThat(unit.referencedTypes()).contains("com.example.Base");
        assertThat(unit.declaredTypes()).contains("com.example.Child");
        assertThat(unit.callSites())
                .extracting(CallSite::referencedKey)
                .contains("TYPE:com.example.Base");
    }

    @Test
    void recordsSuperclassInterfacesAndAbstractness() {
        String source = """
                package com.example;
                public class Impl extends Base implements Repository {
                }
                """;
        ParsedUnit unit = parser.parse("Impl.java", source);
        assertThat(unit.typeSuperclasses()).containsEntry("com.example.Impl", "com.example.Base");
        assertThat(unit.typeInterfaces()).containsEntry("com.example.Impl", List.of("com.example.Repository"));
        assertThat(unit.abstractTypes()).doesNotContain("com.example.Impl");

        ParsedUnit iface = parser.parse("Repository.java", Fixtures.REPO_V1);
        assertThat(iface.abstractTypes()).contains("com.example.Repository");
        assertThat(iface.typeSuperclasses()).doesNotContainKey("com.example.Repository");
        assertThat(iface.typeInterfaces())
                .doesNotContainKey("com.example.Repository");

        ParsedUnit abs = parser.parse("Shape.java", Fixtures.SHAPE_V1);
        assertThat(abs.abstractTypes()).doesNotContain("com.example.Shape");
    }

    @Test
    void interfaceAbstractMethodsAreFlaggedDefaultMethodsAreNot() {
        ParsedUnit abstractIface = parser.parse("Repository.java", Fixtures.REPO_V2_ABSTRACT);
        ApiElement save = abstractIface.apiElements().stream()
                .filter(el -> el.key().equals("METHOD:com.example.Repository#save()"))
                .findFirst().orElseThrow();
        assertThat(save.modifiers()).contains("abstract");

        ParsedUnit defaultIface = parser.parse("RepositoryDefault.java", Fixtures.REPO_V2_DEFAULT);
        ApiElement defaultSave = defaultIface.apiElements().stream()
                .filter(el -> el.key().equals("METHOD:com.example.Repository#save()"))
                .findFirst().orElseThrow();
        assertThat(defaultSave.modifiers()).contains("default");
        assertThat(defaultSave.modifiers()).doesNotContain("abstract");
    }

    @Test
    void normalizesTypeSignatures() {
        assertThat(JavaSourceParser.normalizeType("java.lang.String")).isEqualTo("String");
        assertThat(JavaSourceParser.normalizeType("String...")).isEqualTo("String[]");
        assertThat(JavaSourceParser.normalizeType("java.util.List < String >"))
                .isEqualTo("java.util.List<String>");
    }

    @Test
    void distinguishesOverloadsBySignature() {
        String source = """
                package com.example;
                public class Over {
                    public void m(int x) {}
                    public void m(String s) {}
                }
                """;
        ParsedUnit unit = parser.parse("Over.java", source);
        assertThat(unit.apiElements())
                .extracting(ApiElement::key)
                .contains("METHOD:com.example.Over#m(int)", "METHOD:com.example.Over#m(String)");
    }
}
