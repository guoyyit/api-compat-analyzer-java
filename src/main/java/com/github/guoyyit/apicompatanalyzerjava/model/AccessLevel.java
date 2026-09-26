package com.github.guoyyit.apicompatanalyzerjava.model;

/** Java access levels ordered by visibility rank. */
public enum AccessLevel {
    PRIVATE(0),
    PACKAGE_PRIVATE(1),
    PROTECTED(2),
    PUBLIC(3);

    private final int rank;

    AccessLevel(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
