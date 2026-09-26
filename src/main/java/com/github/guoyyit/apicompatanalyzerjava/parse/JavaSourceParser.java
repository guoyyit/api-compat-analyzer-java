package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.AccessLevel;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.ElementKind;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.Modifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JavaParser-based implementation of {@link SourceParser}.
 *
 * <p>Extraction rules:
 * <ul>
 *   <li>API surface = public/protected types and their public/protected members.</li>
 *   <li>Call sites are recorded with a best-effort static owner resolution using
 *       imports, same-package types, declared fields, parameters and local variables.</li>
 *   <li>Suppressions: {@code @ApiCompatIgnore} annotation or a comment containing
 *       {@code api-compat:ignore} attached to the declaration.</li>
 * </ul>
 */
@Component
public class JavaSourceParser implements SourceParser {

    public static final String IGNORE_ANNOTATION = "ApiCompatIgnore";
    public static final String IGNORE_MARKER = "api-compat:ignore";

    @Override
    public ParsedUnit parse(String file, String source) {
        ParseResult<CompilationUnit> result = new JavaParser().parse(source);
        CompilationUnit cu = result.getResult()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Cannot parse " + file + ": " + result.getProblems()));
        FileContext ctx = new FileContext(file);
        ctx.pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        collectImports(cu, ctx);
        // Pass 1: declare types and collect members/API elements.
        for (TypeDeclaration<?> td : cu.getTypes()) {
            visitType(td, "", true, false, ctx);
        }
        // Pass 2: collect call sites per member body.
        cu.walk(node -> {
            if (node instanceof MethodDeclaration md) {
                String typeFqn = enclosingTypeOf(md, ctx);
                String params = md.getParameters().stream()
                        .map(pa -> normalizeType(pa.getTypeAsString()))
                        .reduce((a, b) -> a + "," + b).orElse("");
                collectCallSites(md, typeFqn + "#" + md.getNameAsString() + "(" + params + ")",
                        typeFqn, ctx);
            } else if (node instanceof ConstructorDeclaration cd) {
                String typeFqn = enclosingTypeOf(cd, ctx);
                collectCallSites(cd, typeFqn + "#<init>", typeFqn, ctx);
            } else if (node instanceof FieldDeclaration fd) {
                String typeFqn = enclosingTypeOf(fd, ctx);
                collectVarTypes(fd, ctx);
                collectCallSites(fd, typeFqn + "#" + fd.getVariable(0).getNameAsString(),
                        typeFqn, ctx);
            }
        });
        return new ParsedUnit(file, ctx.elements, ctx.callSites, ctx.referencedTypes,
                ctx.declaredTypes, ctx.suppressedKeys, ctx.supertypes);
    }

    // ------------------------------------------------------------------
    // File context
    // ------------------------------------------------------------------

    private static final class FileContext {
        final String file;
        String pkg = "";
        final Map<String, String> imports = new HashMap<>();        // simple -> fqn
        final Map<String, String> staticImports = new HashMap<>();  // member name -> owner fqn
        final Map<String, String> declaredSimple = new HashMap<>(); // simple -> fqn (this file)
        final Map<String, String> superOf = new HashMap<>();        // type fqn -> supertype name
        final Map<String, List<String>> supertypes = new HashMap<>(); // type fqn -> resolved supertypes
        final Map<String, String> fieldTypes = new HashMap<>();     // field name -> type (this file)
        final List<ApiElement> elements = new ArrayList<>();
        final List<CallSite> callSites = new ArrayList<>();
        final Set<String> referencedTypes = new LinkedHashSet<>();
        final Set<String> declaredTypes = new LinkedHashSet<>();
        final Set<String> suppressedKeys = new LinkedHashSet<>();
        FileContext(String file) {
            this.file = file;
        }

        /** Resolves a type name as written in source to a (best-effort) qualified name. */
        String resolveType(String name) {
            String base = stripTypeArgs(name);
            if (base.isEmpty() || isPrimitive(base)) {
                return base;
            }
            if (base.contains(".")) {
                return base;
            }
            String declared = declaredSimple.get(base);
            if (declared != null) {
                return declared;
            }
            String imported = imports.get(base);
            if (imported != null) {
                return imported;
            }
            return pkg.isEmpty() ? base : pkg + "." + base;
        }
    }

    private static void collectImports(CompilationUnit cu, FileContext ctx) {
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isAsterisk()) {
                continue;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (imp.isStatic()) {
                int idx = name.lastIndexOf('.');
                ctx.staticImports.put(simple, name.substring(0, idx));
                ctx.referencedTypes.add(name.substring(0, idx));
            } else {
                ctx.imports.put(simple, name);
                ctx.referencedTypes.add(name);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pass 1: API elements
    // ------------------------------------------------------------------

    private void visitType(TypeDeclaration<?> td, String enclosingFqn, boolean enclosingApi,
                           boolean enclosingIgnored, FileContext ctx) {
        String simple = td.getNameAsString();
        String fqn = enclosingFqn.isEmpty() ? qualify(ctx.pkg, simple) : enclosingFqn + "." + simple;
        ctx.declaredSimple.putIfAbsent(simple, fqn);
        ctx.declaredTypes.add(fqn);
        ctx.referencedTypes.add(fqn);

        AccessLevel access = accessOf(td, isInterfaceType(td) || td.isAnnotationDeclaration());
        boolean apiType = enclosingApi && (access == AccessLevel.PUBLIC || access == AccessLevel.PROTECTED);
        boolean ignored = enclosingIgnored || isIgnored(td);

        List<String> supers = new ArrayList<>();
        if (td.isClassOrInterfaceDeclaration()) {
            var coi = td.asClassOrInterfaceDeclaration();
            coi.getExtendedTypes().forEach(t -> supers.add(ctx.resolveType(t.getNameAsString())));
            coi.getImplementedTypes().forEach(t -> supers.add(ctx.resolveType(t.getNameAsString())));
            if (!coi.isInterface() && !coi.getExtendedTypes().isEmpty()) {
                ctx.superOf.put(fqn, coi.getExtendedTypes().get(0).getNameAsString());
            }
        } else if (td.isEnumDeclaration()) {
            td.asEnumDeclaration().getImplementedTypes()
                    .forEach(t -> supers.add(ctx.resolveType(t.getNameAsString())));
        } else if (td instanceof RecordDeclaration rd) {
            rd.getImplementedTypes().forEach(t -> supers.add(ctx.resolveType(t.getNameAsString())));
        }
        if (!supers.isEmpty()) {
            ctx.supertypes.put(fqn, supers);
            for (String sup : supers) {
                ctx.referencedTypes.add(sup);
                addCallSite(td, "TYPE:" + sup, fqn, ctx);
            }
        }

        {
            if (apiType) {
                ApiElement typeElement = new ApiElement(ElementKind.TYPE, enclosingFqn, fqn,
                        typeKind(td), List.of(), access, modifiersOf(td), ctx.file, lineOf(td));
                ctx.elements.add(typeElement);
                if (ignored) {
                    ctx.suppressedKeys.add(typeElement.key());
                }
            }
            for (BodyDeclaration<?> member : td.getMembers()) {
                visitMember(member, td, fqn, apiType, ignored, ctx);
            }
            if (td.isEnumDeclaration()) {
                for (EnumConstantDeclaration ec : td.asEnumDeclaration().getEntries()) {
                    if (!apiType) {
                        continue;
                    }
                    ApiElement e = new ApiElement(ElementKind.FIELD, fqn, ec.getNameAsString(), fqn,
                            List.of(), AccessLevel.PUBLIC, Set.of("static", "final"), ctx.file, lineOf(ec));
                    ctx.elements.add(e);
                    if (ignored || isIgnored(ec)) {
                        ctx.suppressedKeys.add(e.key());
                    }
                }
            }
            if (td instanceof RecordDeclaration rd && apiType) {
                for (Parameter component : rd.getParameters()) {
                    ApiElement e = new ApiElement(ElementKind.METHOD, fqn, component.getNameAsString(),
                            normalizeType(component.getTypeAsString()), List.of(), AccessLevel.PUBLIC,
                            Set.of(), ctx.file, lineOf(component));
                    ctx.elements.add(e);
                    if (ignored) {
                        ctx.suppressedKeys.add(e.key());
                    }
                }
            }
        }
    }

    private void visitMember(BodyDeclaration<?> member, TypeDeclaration<?> owner, String ownerFqn,
                             boolean apiType, boolean ownerIgnored, FileContext ctx) {
        if (member instanceof TypeDeclaration<?> nested) {
            visitType(nested, ownerFqn, apiType, ownerIgnored, ctx);
            return;
        }
        if (!apiType) {
            return;
        }
        boolean implicitPublic = isInterfaceType(owner) || owner.isAnnotationDeclaration();
        if (member instanceof MethodDeclaration md) {
            AccessLevel access = accessOf(md, implicitPublic);
            if (!isApiAccess(access)) {
                return;
            }
            List<String> params = md.getParameters().stream()
                    .map(p -> normalizeType(p.getTypeAsString())).toList();
            ApiElement e = new ApiElement(ElementKind.METHOD, ownerFqn, md.getNameAsString(),
                    normalizeType(md.getTypeAsString()), params, access, modifiersOf(md),
                    ctx.file, lineOf(md));
            ctx.elements.add(e);
            if (ownerIgnored || isIgnored(md)) {
                ctx.suppressedKeys.add(e.key());
            }
        } else if (member instanceof ConstructorDeclaration cd) {
            boolean enumCtor = owner.isEnumDeclaration();
            AccessLevel access = accessOf(cd, false);
            if (enumCtor && access == AccessLevel.PACKAGE_PRIVATE) {
                access = AccessLevel.PRIVATE; // enum constructors are implicitly private
            }
            if (!isApiAccess(access)) {
                return;
            }
            List<String> params = cd.getParameters().stream()
                    .map(p -> normalizeType(p.getTypeAsString())).toList();
            ApiElement e = new ApiElement(ElementKind.CONSTRUCTOR, ownerFqn, "<init>", "",
                    params, access, modifiersOf(cd), ctx.file, lineOf(cd));
            ctx.elements.add(e);
            if (ownerIgnored || isIgnored(cd)) {
                ctx.suppressedKeys.add(e.key());
            }
        } else if (member instanceof FieldDeclaration fd) {
            AccessLevel access = accessOf(fd, implicitPublic);
            if (!isApiAccess(access)) {
                return;
            }
            Set<String> mods = modifiersOf(fd);
            if (implicitPublic) {
                mods = new LinkedHashSet<>(mods);
                mods.add("static");
                mods.add("final");
            }
            for (VariableDeclarator var : fd.getVariables()) {
                ApiElement e = new ApiElement(ElementKind.FIELD, ownerFqn, var.getNameAsString(),
                        normalizeType(fd.getElementType().asString()), List.of(), access, mods,
                        ctx.file, lineOf(fd));
                ctx.elements.add(e);
                if (ownerIgnored || isIgnored(fd)) {
                    ctx.suppressedKeys.add(e.key());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Pass 2: call sites
    // ------------------------------------------------------------------

    private void collectVarTypes(FieldDeclaration fd, FileContext ctx) {
        String type = fd.getElementType().asString();
        for (VariableDeclarator var : fd.getVariables()) {
            ctx.fieldTypes.put(var.getNameAsString(), type);
        }
    }

    private void collectCallSites(com.github.javaparser.ast.Node member, String symbol,
                                  String enclosingType, FileContext ctx) {
        Map<String, String> localTypes = new HashMap<>();
        // parameters
        if (member instanceof MethodDeclaration md) {
            for (Parameter p : md.getParameters()) {
                localTypes.put(p.getNameAsString(), p.getTypeAsString());
                addTypeCallSite(p.getType(), symbol, ctx);
            }
            addTypeCallSite(md.getType(), symbol, ctx);
            md.getThrownExceptions().forEach(t -> addTypeCallSite(t, symbol, ctx));
        } else if (member instanceof ConstructorDeclaration cd) {
            for (Parameter p : cd.getParameters()) {
                localTypes.put(p.getNameAsString(), p.getTypeAsString());
                addTypeCallSite(p.getType(), symbol, ctx);
            }
            cd.getThrownExceptions().forEach(t -> addTypeCallSite(t, symbol, ctx));
        } else if (member instanceof FieldDeclaration fd) {
            addTypeCallSite(fd.getElementType(), symbol, ctx);
        }

        member.walk(node -> {
            if (node instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator var : vde.getVariables()) {
                    String type = var.getTypeAsString();
                    if ("var".equals(type)) {
                        type = var.getInitializer()
                                .filter(Expression::isObjectCreationExpr)
                                .map(init -> init.asObjectCreationExpr().getTypeAsString())
                                .orElse(type);
                    }
                    localTypes.put(var.getNameAsString(), type);
                    addTypeCallSite(var.getType(), symbol, ctx);
                }
            } else if (node instanceof CatchClause cc) {
                localTypes.put(cc.getParameter().getNameAsString(), cc.getParameter().getTypeAsString());
                addTypeCallSite(cc.getParameter().getType(), symbol, ctx);
            } else if (node instanceof MethodCallExpr call) {
                handleMethodCall(call, symbol, enclosingType, localTypes, ctx);
            } else if (node instanceof ObjectCreationExpr creation) {
                String owner = ctx.resolveType(creation.getTypeAsString());
                int arity = creation.getArguments().size();
                addCallSite(creation, "MEMBER:" + owner + "#<init>/" + arity, symbol, ctx);
                addCallSite(creation, "TYPE:" + owner, symbol, ctx);
                ctx.referencedTypes.add(owner);
            } else if (node instanceof FieldAccessExpr fa) {
                String owner = ownerOfScope(fa.getScope(), enclosingType, localTypes, ctx);
                if (!owner.isEmpty()) {
                    addCallSite(fa, "FIELD:" + owner + "#" + fa.getNameAsString(), symbol, ctx);
                }
            } else if (node instanceof MethodReferenceExpr ref) {
                String owner = ownerOfScope(ref.getScope(), enclosingType, localTypes, ctx);
                addCallSite(ref, "MEMBER:" + owner + "#" + ref.getIdentifier() + "/-1", symbol, ctx);
            }
        });
    }

    private void handleMethodCall(MethodCallExpr call, String symbol, String enclosingType,
                                  Map<String, String> localTypes, FileContext ctx) {
        String owner;
        Optional<Expression> scope = call.getScope();
        if (scope.isPresent()) {
            owner = ownerOfScope(scope.get(), enclosingType, localTypes, ctx);
        } else {
            String staticOwner = ctx.staticImports.get(call.getNameAsString());
            owner = staticOwner != null ? staticOwner : enclosingType;
        }
        int arity = call.getArguments().size();
        addCallSite(call, "MEMBER:" + owner + "#" + call.getNameAsString() + "/" + arity, symbol, ctx);
    }

    private String ownerOfScope(Expression scope, String enclosingType,
                                Map<String, String> localTypes, FileContext ctx) {
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            String local = localTypes.get(name);
            if (local != null) {
                return ctx.resolveType(local);
            }
            String field = ctx.fieldTypes.get(name);
            if (field != null) {
                return ctx.resolveType(field);
            }
            if (isTypeName(name, ctx)) {
                String resolved = ctx.resolveType(name);
                ctx.referencedTypes.add(resolved);
                return resolved;
            }
            return "";
        }
        if (scope.isThisExpr()) {
            return enclosingType;
        }
        if (scope.isSuperExpr()) {
            String superName = ctx.superOf.get(enclosingType);
            return superName != null ? ctx.resolveType(superName) : "";
        }
        if (scope.isFieldAccessExpr()) {
            FieldAccessExpr fa = scope.asFieldAccessExpr();
            String field = ctx.fieldTypes.get(fa.getNameAsString());
            if (field != null) {
                return ctx.resolveType(field);
            }
            return "";
        }
        if (scope.isObjectCreationExpr()) {
            return ctx.resolveType(scope.asObjectCreationExpr().getTypeAsString());
        }
        if (scope.isTypeExpr()) {
            return ctx.resolveType(scope.asTypeExpr().getTypeAsString());
        }
        return "";
    }

    private void addTypeCallSite(Type type, String symbol, FileContext ctx) {
        if (type == null || type.isPrimitiveType() || type.isVoidType()) {
            return;
        }
        type.walk(node -> {
            if (node instanceof ClassOrInterfaceType cit) {
                String resolved = ctx.resolveType(cit.getNameAsString());
                ctx.referencedTypes.add(resolved);
                addCallSite(cit, "TYPE:" + resolved, symbol, ctx);
            }
        });
    }

    private void addCallSite(com.github.javaparser.ast.Node node, String key, String symbol, FileContext ctx) {
        int line = node.getBegin().map(p -> p.line).orElse(-1);
        int col = node.getBegin().map(p -> p.column).orElse(-1);
        ctx.callSites.add(new CallSite(ctx.file, line, col, symbol, key));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isInterfaceType(TypeDeclaration<?> td) {
        return td.isClassOrInterfaceDeclaration() && td.asClassOrInterfaceDeclaration().isInterface();
    }

    private static String typeKind(TypeDeclaration<?> td) {
        if (td.isClassOrInterfaceDeclaration()) {
            return td.asClassOrInterfaceDeclaration().isInterface() ? "INTERFACE" : "CLASS";
        }
        if (td.isEnumDeclaration()) {
            return "ENUM";
        }
        if (td.isAnnotationDeclaration()) {
            return "ANNOTATION";
        }
        if (td.isRecordDeclaration()) {
            return "RECORD";
        }
        return "TYPE";
    }

    /** Computes the fqn of the type enclosing the given node by walking ancestors. */
    private static String enclosingTypeOf(com.github.javaparser.ast.Node node, FileContext ctx) {
        List<String> names = new ArrayList<>();
        com.github.javaparser.ast.Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof TypeDeclaration<?> td) {
                names.add(0, td.getNameAsString());
            }
            current = current.getParentNode().orElse(null);
        }
        String joined = String.join(".", names);
        return ctx.pkg.isEmpty() ? joined : ctx.pkg + "." + joined;
    }

    private static boolean isApiAccess(AccessLevel access) {
        return access == AccessLevel.PUBLIC || access == AccessLevel.PROTECTED;
    }

    private static AccessLevel accessOf(NodeWithModifiers<?> node, boolean implicitPublic) {
        if (node.hasModifier(Modifier.Keyword.PUBLIC)) {
            return AccessLevel.PUBLIC;
        }
        if (node.hasModifier(Modifier.Keyword.PROTECTED)) {
            return AccessLevel.PROTECTED;
        }
        if (node.hasModifier(Modifier.Keyword.PRIVATE)) {
            return AccessLevel.PRIVATE;
        }
        return implicitPublic ? AccessLevel.PUBLIC : AccessLevel.PACKAGE_PRIVATE;
    }

    private static Set<String> modifiersOf(NodeWithModifiers<?> node) {
        Set<String> mods = new LinkedHashSet<>();
        for (Modifier.Keyword kw : new Modifier.Keyword[]{
                Modifier.Keyword.STATIC, Modifier.Keyword.FINAL, Modifier.Keyword.ABSTRACT,
                Modifier.Keyword.DEFAULT, Modifier.Keyword.NATIVE, Modifier.Keyword.SYNCHRONIZED}) {
            if (node.hasModifier(kw)) {
                mods.add(kw.asString());
            }
        }
        return mods;
    }

    private static boolean isIgnored(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        boolean annotated = node.getAnnotations().stream()
                .anyMatch(a -> {
                    String n = a.getNameAsString();
                    return n.equals(IGNORE_ANNOTATION) || n.endsWith("." + IGNORE_ANNOTATION);
                });
        if (annotated) {
            return true;
        }
        return node instanceof com.github.javaparser.ast.Node n
                && n.getComment().map(c -> c.getContent().contains(IGNORE_MARKER)).orElse(false);
    }

    private static boolean isTypeName(String name, FileContext ctx) {
        return ctx.imports.containsKey(name) || ctx.declaredSimple.containsKey(name)
                || (!name.isEmpty() && Character.isUpperCase(name.charAt(0)));
    }

    /** Normalizes a type as written in source to a canonical signature form. */
    public static String normalizeType(String type) {
        String s = type.trim()
                .replaceAll("\\s*<\\s*", "<")
                .replaceAll("\\s*>\\s*", ">")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\[\\s*]", "[]")
                .replaceAll("\\s*\\.\\.\\.", "[]")
                .replaceAll("\\s+", " ");
        while (s.startsWith("java.lang.")) {
            s = s.substring("java.lang.".length());
        }
        s = s.replace("<? extends ", "? extends ").replace("<? super ", "? super ");
        return s;
    }

    private static String stripTypeArgs(String name) {
        int idx = name.indexOf('<');
        String base = idx < 0 ? name : name.substring(0, idx);
        return base.replace("[]", "").trim();
    }

    private static boolean isPrimitive(String name) {
        return switch (name) {
            case "byte", "short", "int", "long", "float", "double", "char", "boolean", "void" -> true;
            default -> false;
        };
    }

    private static String qualify(String pkg, String simple) {
        return pkg.isEmpty() ? simple : pkg + "." + simple;
    }

    private static int lineOf(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(-1);
    }
}
