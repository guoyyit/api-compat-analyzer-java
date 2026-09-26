package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiKind;
import com.github.guoyyit.apicompatanalyzerjava.model.ApiVisibility;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSiteKind;
import com.github.guoyyit.apicompatanalyzerjava.model.SourceLocation;
import com.github.guoyyit.apicompatanalyzerjava.model.Suppression;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JavaParser based extraction for one source file.
 *
 * <p>No symbol solver / classpath is used: names are resolved with the file's own
 * imports, package and {@code java.lang}. This keeps parsing hermetic and reproducible,
 * which is enough for comparing two snapshots of the same code base.
 */
@Component
public class JavaParserJavaSourceParser implements JavaSourceParser {

    static final String EXEMPT_ANNOTATION = "ApiCompatExempt";
    static final String PARSER_VERSION = "javaparser-3.26.4/v1";

    @Override
    public ParsedUnit parse(SourceFile file, String content) {
        JavaParser parser = new JavaParser();
        ParseResult<CompilationUnit> result = parser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IllegalArgumentException(
                    "failed to parse " + file.path() + ": "
                            + (result.getProblems().isEmpty() ? "unknown error"
                            : result.getProblems().get(0).getMessage()));
        }
        return new Worker(file, content, result.getResult().get()).run();
    }

    /** Per-file mutable extraction context. */
    private static final class Worker {
        private final SourceFile file;
        private final String content;
        private final String[] lines;
        private final CompilationUnit cu;
        private final String packageName;

        private final List<ApiElement> elements = new ArrayList<>();
        private final List<CallSite> callSites = new ArrayList<>();
        private final List<Suppression> suppressions = new ArrayList<>();
        private final Set<String> declaredTypes = new HashSet<>();
        private final Set<String> usedTypes = new HashSet<>();
        private final List<ParsedUnit.ImportEntry> imports = new ArrayList<>();
        private final Map<String, String> explicitImports = new LinkedHashMap<>();
        private final Map<String, String> staticImports = new LinkedHashMap<>();
        private final List<String> onDemandPackages = new ArrayList<>();

        Worker(SourceFile file, String content, CompilationUnit cu) {
            this.file = file;
            this.content = content;
            this.lines = content.split("\n", -1);
            this.cu = cu;
            this.packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        }

        ParsedUnit run() {
            collectImports();
            for (TypeDeclaration<?> type : cu.getTypes()) {
                walkType(type, packageName, true);
            }
            collectCallSites();
            return new ParsedUnit(file.path(),
                    com.github.guoyyit.apicompatanalyzerjava.util.Hashes.sha256(content),
                    packageName,
                    List.copyOf(elements), List.copyOf(callSites), List.copyOf(suppressions),
                    Set.copyOf(declaredTypes), Set.copyOf(usedTypes), List.copyOf(imports));
        }

        private void collectImports() {
            cu.getImports().forEach(imp -> {
                String name = imp.getNameAsString();
                boolean onDemand = imp.isAsterisk();
                boolean isStatic = imp.isStatic();
                String simple = onDemand ? name : name.substring(name.lastIndexOf('.') + 1);
                imports.add(new ParsedUnit.ImportEntry(simple, name, isStatic, onDemand));
                if (onDemand) {
                    onDemandPackages.add(name);
                    usedTypes.add("wildcard:" + name);
                } else if (isStatic) {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        staticImports.put(simple, name.substring(0, dot));
                        usedTypes.add(name.substring(0, dot));
                    }
                } else {
                    explicitImports.put(simple, name);
                    usedTypes.add(name);
                }
            });
        }

        private void walkType(TypeDeclaration<?> type, String enclosingFqn, boolean enclosingAccessible) {
            String fqn = enclosingFqn.isEmpty() ? type.getNameAsString() : enclosingFqn + "." + type.getNameAsString();
            declaredTypes.add(fqn);
            ApiVisibility visibility = visibilityOf(type);
            boolean accessible = enclosingAccessible
                    && (visibility == ApiVisibility.PUBLIC || visibility == ApiVisibility.PROTECTED);

            if (accessible) {
                List<String> supers = resolveSuperTypes(type);
                usedTypes.addAll(supers);
                ApiElement element = new ApiElement(
                        fqn, ApiKind.TYPE, null, type.getNameAsString(), null,
                        List.of(), visibility,
                        type.hasModifier(Modifier.Keyword.STATIC),
                        type.hasModifier(Modifier.Keyword.ABSTRACT) || isInterface(type),
                        type.hasModifier(Modifier.Keyword.FINAL),
                        List.copyOf(supers),
                        locationOf(type), false, null, null);
                element = applyExemption(element, type);
                elements.add(element);
            }

            for (Node member : type.getMembers()) {
                if (member instanceof TypeDeclaration<?> nested) {
                    walkType(nested, fqn, accessible);
                } else if (accessible && member instanceof MethodDeclaration method) {
                    addCallable(method, fqn, ApiKind.METHOD);
                } else if (accessible && member instanceof ConstructorDeclaration ctor) {
                    addCallable(ctor, fqn, ApiKind.CONSTRUCTOR);
                } else if (accessible && member instanceof AnnotationMemberDeclaration annotationMethod) {
                    addAnnotationMember(annotationMethod, fqn);
                } else if (accessible && member instanceof FieldDeclaration field) {
                    addField(field, fqn);
                } else if (accessible && member instanceof EnumConstantDeclaration constant) {
                    addEnumConstant(constant, fqn);
                }
            }
        }

        private void addCallable(CallableDeclaration<?> callable, String owner, ApiKind kind) {
            ApiVisibility visibility = visibilityOf(callable);
            List<String> params = new ArrayList<>();
            callable.getParameters().forEach(p -> params.add(erase(p.getType())));
            String id = owner + "#" + (kind == ApiKind.CONSTRUCTOR ? "<init>" : callable.getNameAsString())
                    + "(" + String.join(",", params) + ")";
            String resultType = null;
            if (kind == ApiKind.METHOD && callable instanceof MethodDeclaration method) {
                resultType = erase(method.getType());
            }
            ApiElement element = new ApiElement(
                    id, kind, owner, callable.getNameAsString(), resultType,
                    List.copyOf(params), visibility,
                    callable.hasModifier(Modifier.Keyword.STATIC),
                    callable.hasModifier(Modifier.Keyword.ABSTRACT),
                    callable.hasModifier(Modifier.Keyword.FINAL),
                    List.of(), locationOf(callable), false, null, null);
            elements.add(applyExemption(element, callable));
        }

        private void addAnnotationMember(AnnotationMemberDeclaration member, String owner) {
            String id = owner + "#" + member.getNameAsString() + "()";
            ApiElement element = new ApiElement(
                    id, ApiKind.METHOD, owner, member.getNameAsString(), erase(member.getType()),
                    List.of(), ApiVisibility.PUBLIC, true, false, false,
                    List.of(), locationOf(member), false, null, null);
            elements.add(applyExemption(element, member));
        }

        private void addField(FieldDeclaration field, String owner) {
            ApiVisibility visibility = visibilityOf(field);
            String type = erase(field.getElementType());
            for (var variable : field.getVariables()) {
                String id = owner + "#" + variable.getNameAsString();
                ApiElement element = new ApiElement(
                        id, ApiKind.FIELD, owner, variable.getNameAsString(), type,
                        List.of(), visibility,
                        field.hasModifier(Modifier.Keyword.STATIC),
                        false,
                        field.hasModifier(Modifier.Keyword.FINAL),
                        List.of(), locationOf(variable), false, null, null);
                elements.add(applyExemption(element, field));
            }
        }

        private void addEnumConstant(EnumConstantDeclaration constant, String owner) {
            String id = owner + "#" + constant.getNameAsString();
            ApiElement element = new ApiElement(
                    id, ApiKind.FIELD, owner, constant.getNameAsString(), owner,
                    List.of(), ApiVisibility.PUBLIC, true, false, true,
                    List.of(), locationOf(constant), false, null, null);
            elements.add(applyExemption(element, constant));
        }

        private ApiElement applyExemption(ApiElement element, Node node) {
            Optional<AnnotationExpr> annotation = findExemption(node);
            if (annotation.isEmpty()) {
                return element;
            }
            String reason = "";
            String change = "";
            AnnotationExpr expr = annotation.get();
            if (expr.isSingleMemberAnnotationExpr()) {
                reason = literalString(expr.asSingleMemberAnnotationExpr().getMemberValue());
            } else if (expr.isNormalAnnotationExpr()) {
                for (var pair : expr.asNormalAnnotationExpr().getPairs()) {
                    if ("reason".equals(pair.getNameAsString())) {
                        reason = literalString(pair.getValue());
                    } else if ("change".equals(pair.getNameAsString())) {
                        change = literalString(pair.getValue());
                    }
                }
            }
            SourceLocation loc = element.location();
            suppressions.add(new Suppression(element.id(), file.path(), loc.line(), reason, change));
            return new ApiElement(element.id(), element.kind(), element.owner(), element.name(),
                    element.resultType(), element.parameterTypes(), element.visibility(),
                    element.staticFlag(), element.abstractFlag(), element.finalFlag(),
                    element.superTypes(), loc, true, reason, change.isEmpty() ? null : change);
        }

        private Optional<AnnotationExpr> findExemption(Node node) {
            if (node instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated) {
                return annotated.getAnnotationByName(EXEMPT_ANNOTATION);
            }
            return Optional.empty();
        }

        private String literalString(Expression expression) {
            return expression.isStringLiteralExpr() ? expression.asStringLiteralExpr().asString() : "";
        }

        private List<String> resolveSuperTypes(TypeDeclaration<?> type) {
            List<String> supers = new ArrayList<>();
            if (type instanceof ClassOrInterfaceDeclaration cls) {
                for (ClassOrInterfaceType extended : cls.getExtendedTypes()) {
                    supers.add(resolveClassName(extended));
                }
                for (ClassOrInterfaceType implemented : cls.getImplementedTypes()) {
                    supers.add(resolveClassName(implemented));
                }
            } else if (type instanceof EnumDeclaration en) {
                en.getImplementedTypes().forEach(t -> supers.add(resolveClassName(t)));
            }
            return supers;
        }

        private void collectCallSites() {
            cu.findAll(MethodCallExpr.class).forEach(this::addMethodCall);
            cu.findAll(ObjectCreationExpr.class).forEach(this::addConstructorCall);
            cu.findAll(ExplicitConstructorInvocationStmt.class).forEach(this::addExplicitCtorCall);
            cu.findAll(FieldAccessExpr.class).forEach(this::addFieldAccess);
            cu.findAll(MethodReferenceExpr.class).forEach(this::addMethodReference);
            cu.findAll(ClassOrInterfaceType.class).forEach(this::addTypeUse);
            cu.findAll(NameExpr.class).forEach(this::addSimpleNameUse);
            dedupCallSites();
        }

        private void addMethodCall(MethodCallExpr call) {
            String owner;
            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                if (scope.isNameExpr() && "super".equals(scope.asNameExpr().getNameAsString())) {
                    owner = enclosingSuperFqn(call);
                } else if (scope.isNameExpr() && "this".equals(scope.asNameExpr().getNameAsString())) {
                    owner = enclosingTypeFqn(call);
                } else {
                    owner = resolveScope(scope, call);
                }
            } else {
                // Unqualified call: may be an inherited member; owner is the enclosing type.
                owner = enclosingTypeFqn(call);
            }
            callSites.add(new CallSite(file.path(), lineOf(call), call.getNameAsString(), owner,
                    CallSiteKind.METHOD_CALL, call.getArguments().size(), snippetOf(call)));
        }

        private void addConstructorCall(ObjectCreationExpr creation) {
            String fqn = resolveClassName(creation.getType());
            callSites.add(new CallSite(file.path(), lineOf(creation),
                    creation.getType().getNameAsString(), fqn,
                    CallSiteKind.CONSTRUCTOR_CALL, creation.getArguments().size(), snippetOf(creation)));
        }

        private void addExplicitCtorCall(ExplicitConstructorInvocationStmt stmt) {
            String owner;
            String symbol;
            if (stmt.isThis()) {
                owner = enclosingTypeFqn(stmt);
                symbol = "<init>";
            } else {
                owner = enclosingSuperFqn(stmt);
                symbol = "<init>";
            }
            callSites.add(new CallSite(file.path(), lineOf(stmt), symbol, owner,
                    CallSiteKind.CONSTRUCTOR_CALL, stmt.getArguments().size(), snippetOf(stmt)));
        }

        private void addFieldAccess(FieldAccessExpr access) {
            callSites.add(new CallSite(file.path(), lineOf(access), access.getNameAsString(),
                    resolveScope(access.getScope(), access), CallSiteKind.FIELD_ACCESS, 0, snippetOf(access)));
        }

        private void addMethodReference(MethodReferenceExpr reference) {
            String symbol = reference.getIdentifier();
            String owner = null;
            Expression scope = reference.getScope();
            if (scope instanceof FieldAccessExpr || scope instanceof NameExpr) {
                owner = resolveScope(scope, reference);
            }
            callSites.add(new CallSite(file.path(), lineOf(reference), symbol, owner,
                    CallSiteKind.METHOD_CALL, 0, snippetOf(reference)));
        }

        private void addTypeUse(ClassOrInterfaceType type) {
            String fqn = resolveClassName(type);
            callSites.add(new CallSite(file.path(), lineOf(type), type.getNameAsString(), fqn,
                    CallSiteKind.TYPE_USE, 0, snippetOf(type)));
        }

        /**
         * Bare identifier use (e.g. {@code counter++;} for an inherited field). Method
         * names, field-access scopes and locals/parameters are excluded so only possible
         * field references reach the impact resolver, which verifies against the model.
         */
        private void addSimpleNameUse(NameExpr nameExpr) {
            String name = nameExpr.getNameAsString();
            Node parent = nameExpr.getParentNode().orElse(null);
            if (parent instanceof MethodCallExpr call && name.equals(call.getNameAsString())) {
                return;
            }
            if (parent instanceof FieldAccessExpr access
                    && access.getNameAsString().equals(name)) {
                return;
            }
            if (isLocalOrParameter(nameExpr, name)) {
                return;
            }
            callSites.add(new CallSite(file.path(), lineOf(nameExpr), name,
                    enclosingTypeFqn(nameExpr), CallSiteKind.FIELD_ACCESS, 0, snippetOf(nameExpr)));
        }

        private boolean isLocalOrParameter(Node node, String name) {
            Optional<CallableDeclaration> callable = node.findAncestor(CallableDeclaration.class);
            if (callable.isEmpty()) {
                return false;
            }
            CallableDeclaration<?> declaration = callable.get();
            for (var parameter : declaration.getParameters()) {
                if (parameter.getNameAsString().equals(name)) {
                    return true;
                }
            }
            for (var declarator : declaration.findAll(
                    com.github.javaparser.ast.body.VariableDeclarator.class)) {
                if (declarator.getNameAsString().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        private void dedupCallSites() {
            Set<CallSite> unique = new HashSet<>(callSites);
            callSites.clear();
            callSites.addAll(unique);
        }

        private String resolveScope(Expression scope, Node context) {
            if (scope == null) {
                return null;
            }
            if (scope.isNameExpr()) {
                String simple = scope.asNameExpr().getNameAsString();
                String localType = resolveLocalType(simple, context);
                return localType != null ? localType : resolveSimpleName(simple);
            }
            if (scope.isFieldAccessExpr()) {
                FieldAccessExpr fa = scope.asFieldAccessExpr();
                String root = resolveScope(fa.getScope(), context);
                return root == null ? null : root + "." + fa.getNameAsString();
            }
            return null;
        }

        /**
         * Resolve a local variable / parameter name to its declared FQN within the
         * enclosing method or constructor. Declarations after the use site are ignored
         * (simple scoping approximation). Returns {@code null} when the name is not local.
         */
        private String resolveLocalType(String name, Node context) {
            Optional<CallableDeclaration> callable = context.findAncestor(CallableDeclaration.class);
            if (callable.isEmpty()) {
                return null;
            }
            CallableDeclaration<?> declaration = callable.get();
            for (var parameter : declaration.getParameters()) {
                if (parameter.getNameAsString().equals(name)) {
                    return erase(parameter.getType());
                }
            }
            for (var declarator : declaration.findAll(
                    com.github.javaparser.ast.body.VariableDeclarator.class)) {
                if (declarator.getNameAsString().equals(name)) {
                    Node fieldNode = declarator.getParentNode().orElse(null);
                    if (fieldNode instanceof FieldDeclaration) {
                        continue;
                    }
                    Optional<com.github.javaparser.Range> use = context.getRange();
                    Optional<com.github.javaparser.Range> decl = declarator.getRange();
                    if (use.isEmpty() || decl.isEmpty()
                            || !decl.get().begin.isBefore(use.get().begin)) {
                        continue;
                    }
                    return erase(declarator.getType());
                }
            }
            return null;
        }

        /**
         * Resolve a dotted class name as written in source using imports, the current
         * package and {@code java.lang}. Type arguments are erased by the caller.
         */
        private String resolveClassName(ClassOrInterfaceType type) {
            String raw = type.getNameWithScope();
            String[] parts = raw.split("\\.");
            String head = resolveSimpleName(parts[0]);
            if (head == null) {
                usedTypes.add(raw);
                return raw;
            }
            String fqn = head;
            for (int i = 1; i < parts.length; i++) {
                fqn += "." + parts[i];
            }
            usedTypes.add(fqn);
            return fqn;
        }

        private String resolveSimpleName(String simple) {
            String imported = explicitImports.get(simple);
            if (imported != null) {
                return imported;
            }
            if (staticImports.containsKey(simple)) {
                return staticImports.get(simple);
            }
            if (JAVA_LANG_TYPES.contains(simple)) {
                return "java.lang." + simple;
            }
            if (!packageName.isEmpty()) {
                return packageName + "." + simple;
            }
            return "java.lang." + simple;
        }

        /** Public types implicitly visible from {@code java.lang} (JLS 7.3 + 7.5.4). */
        private static final java.util.Set<String> JAVA_LANG_TYPES = java.util.Set.of(
                "Appendable", "AutoCloseable", "CharSequence", "Cloneable",
                "Comparable", "Iterable", "ProcessHandle", "Readable",
                "Runnable", "StackWalker", "System", "Thread",
                "Boolean", "Byte", "Character", "Class", "ClassLoader", "ClassValue",
                "Compiler", "Double", "Enum", "Float", "InheritableThreadLocal",
                "Integer", "Long", "Math", "Module", "ModuleLayer", "Number",
                "Object", "Package", "Process", "ProcessBuilder", "Record", "Runtime",
                "RuntimePermission", "SecurityManager", "Short", "StackTraceElement",
                "StackOverflowError", "StrictMath", "String", "StringBuffer",
                "StringBuilder", "ThreadGroup", "ThreadLocal", "Throwable", "Void",
                "Character.Subset", "Character.UnicodeBlock",
                "ArithmeticException", "ArrayIndexOutOfBoundsException",
                "ArrayStoreException", "ClassCastException", "ClassNotFoundException",
                "CloneNotSupportedException", "EnumConstantNotPresentException",
                "Exception", "IllegalAccessException", "IllegalArgumentException",
                "IllegalCallerException", "IllegalMonitorStateException",
                "IllegalStateException", "IllegalThreadStateException",
                "IndexOutOfBoundsException", "InstantiationException",
                "InterruptedException", "LayerInstantiationException",
                "NegativeArraySizeException", "NoSuchFieldException",
                "NoSuchMethodException", "NullPointerException",
                "NumberFormatException", "ReflectiveOperationException",
                "RuntimeException", "SecurityException", "StringIndexOutOfBoundsException",
                "TypeNotPresentException", "UnsupportedOperationException",
                "AbstractMethodError", "AssertionError", "BootstrapMethodError",
                "ClassCircularityError", "ClassFormatError", "Error",
                "ExceptionInInitializerError", "IllegalAccessError",
                "IncompatibleClassChangeError", "InstantiationError",
                "InternalError", "LinkageError", "NoClassDefFoundError",
                "NoSuchFieldError", "NoSuchMethodError", "OutOfMemoryError",
                "ThreadDeath", "UnknownError", "UnsatisfiedLinkError",
                "UnsupportedClassVersionError", "VerifyError", "VirtualMachineError",
                "Deprecated", "FunctionalInterface", "Override", "SafeVarargs",
                "SuppressWarnings"
        );

        private String erase(Type type) {
            if (type.isPrimitiveType()) {
                return type.asPrimitiveType().asString();
            }
            if (type.isVoidType()) {
                return "void";
            }
            if (type.isArrayType()) {
                return erase(type.asArrayType().getComponentType()) + "[]";
            }
            if (type.isClassOrInterfaceType()) {
                return resolveClassName(type.asClassOrInterfaceType());
            }
            if (type.isWildcardType()) {
                return "java.lang.Object";
            }
            if (type.isVarType() || type.isUnknownType()) {
                return "var";
            }
            return type.asString();
        }

        private boolean isInterface(TypeDeclaration<?> type) {
            return type instanceof ClassOrInterfaceDeclaration c && c.isInterface();
        }

        private ApiVisibility visibilityOf(Node node) {
            if (node instanceof MethodDeclaration m) {
                return fromModifiers(m.getModifiers());
            }
            if (node instanceof ConstructorDeclaration c) {
                return fromModifiers(c.getModifiers());
            }
            if (node instanceof FieldDeclaration f) {
                return fromModifiers(f.getModifiers());
            }
            if (node instanceof TypeDeclaration<?> t) {
                return fromModifiers(t.getModifiers());
            }
            if (node instanceof AnnotationMemberDeclaration) {
                return ApiVisibility.PUBLIC;
            }
            return ApiVisibility.PACKAGE;
        }

        private ApiVisibility fromModifiers(List<Modifier> modifiers) {
            for (Modifier modifier : modifiers) {
                Modifier.Keyword keyword = modifier.getKeyword();
                if (keyword == Modifier.Keyword.PUBLIC) {
                    return ApiVisibility.PUBLIC;
                }
                if (keyword == Modifier.Keyword.PROTECTED) {
                    return ApiVisibility.PROTECTED;
                }
                if (keyword == Modifier.Keyword.PRIVATE) {
                    return ApiVisibility.PRIVATE;
                }
            }
            return ApiVisibility.PACKAGE;
        }

        private String enclosingTypeFqn(Node node) {
            Optional<TypeDeclaration> owner = node.findAncestor(TypeDeclaration.class);
            return owner.map(t -> packageName.isEmpty() ? t.getNameAsString()
                    : packageName + "." + t.getNameAsString()).orElse(null);
        }

        private String enclosingSuperFqn(Node node) {
            Optional<ClassOrInterfaceDeclaration> owner = node.findAncestor(ClassOrInterfaceDeclaration.class);
            if (owner.isPresent() && !owner.get().getExtendedTypes().isEmpty()) {
                return resolveClassName(owner.get().getExtendedTypes().get(0));
            }
            return "java.lang.Object";
        }

        private int lineOf(Node node) {
            return node.getBegin().map(p -> p.line).orElse(-1);
        }

        private SourceLocation locationOf(Node node) {
            int line = node.getBegin().map(p -> p.line).orElse(-1);
            int column = node.getBegin().map(p -> p.column).orElse(-1);
            return new SourceLocation(file.path(), line, column);
        }

        private String snippetOf(Node node) {
            int line = lineOf(node);
            if (line <= 0 || line > lines.length) {
                return "";
            }
            return lines[line - 1].trim();
        }
    }
}
