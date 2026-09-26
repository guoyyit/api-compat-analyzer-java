package com.github.guoyyit.apicompatanalyzerjava.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable description of one public API element
 * (type, method, constructor or field) extracted from source.
 */
public final class ApiElement {
    private final ElementKind kind;
    private final String ownerType;
    private final String name;
    private final String returnType;
    private final List<String> parameterTypes;
    private final AccessLevel access;
    private final Set<String> modifiers;
    private final String sourceFile;
    private final int line;

    public ApiElement(ElementKind kind, String ownerType, String name, String returnType,
                      List<String> parameterTypes, AccessLevel access, Set<String> modifiers,
                      String sourceFile, int line) {
        this.kind = kind;
        this.ownerType = ownerType;
        this.name = name;
        this.returnType = returnType;
        this.parameterTypes = List.copyOf(parameterTypes);
        this.access = access;
        this.modifiers = Set.copyOf(modifiers);
        this.sourceFile = sourceFile;
        this.line = line;
    }

    /** Stable identity used for diffing and suppressions. */
    public String key() {
        return switch (kind) {
            case TYPE -> "TYPE:" + name;
            case FIELD -> "FIELD:" + ownerType + "#" + name;
            case METHOD -> "METHOD:" + ownerType + "#" + name + "(" + String.join(",", parameterTypes) + ")";
            case CONSTRUCTOR -> "CONSTRUCTOR:" + ownerType + "#<init>(" + String.join(",", parameterTypes) + ")";
        };
    }

    /** Returns a copy owned by another type (used for inheritance expansion). */
    public ApiElement withOwner(String newOwner) {
        return new ApiElement(kind, newOwner, name, returnType, parameterTypes,
                access, modifiers, sourceFile, line);
    }

    public ElementKind kind() { return kind; }
    public String ownerType() { return ownerType; }
    public String name() { return name; }
    public String returnType() { return returnType; }
    public List<String> parameterTypes() { return parameterTypes; }
    public AccessLevel access() { return access; }
    public Set<String> modifiers() { return modifiers; }
    public String sourceFile() { return sourceFile; }
    public int line() { return line; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiElement other)) return false;
        return Objects.equals(key(), other.key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key());
    }
}
