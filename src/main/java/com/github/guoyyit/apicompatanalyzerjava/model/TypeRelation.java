package com.github.guoyyit.apicompatanalyzerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;

/**
 * Immutable inheritance relation of one declared type: its resolved direct
 * superclass name (empty for none) and its directly implemented/extended
 * interface names. Used for superclass-change detection and for locating
 * downstream implementors of newly abstract interface/class methods.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public final class TypeRelation {
    private final boolean concrete;
    private final String superclass;
    private final List<String> interfaces;

    public TypeRelation(boolean concrete, String superclass, List<String> interfaces) {
        this.concrete = concrete;
        this.superclass = superclass == null ? "" : superclass;
        this.interfaces = List.copyOf(interfaces);
    }

    /** True for instantiable types (classes/enums/records that are not abstract). */
    public boolean concrete() {
        return concrete;
    }

    /** Resolved direct superclass name, or "" when the type declares none. */
    public String superclass() {
        return superclass;
    }

    public List<String> interfaces() {
        return interfaces;
    }
}
