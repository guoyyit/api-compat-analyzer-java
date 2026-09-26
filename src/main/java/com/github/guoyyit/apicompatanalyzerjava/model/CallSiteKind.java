package com.github.guoyyit.apicompatanalyzerjava.model;

/** Kind of a reference located in source code. */
public enum CallSiteKind {
    METHOD_CALL,
    CONSTRUCTOR_CALL,
    FIELD_ACCESS,
    TYPE_USE
}
