package com.github.guoyyit.apicompatanalyzerjava.model;

/** Classification of a detected API change. */
public enum ChangeKind {
    ADDED,
    REMOVED,
    SIGNATURE_CHANGED,
    ACCESS_NARROWED,
    ACCESS_WIDENED,
    SUPERCLASS_CHANGED
}
