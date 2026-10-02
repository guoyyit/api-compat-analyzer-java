package com.github.guoyyit.apicompatanalyzerjava.model;

/** Classification of a detected API change. */
public enum ChangeKind {
    ADDED,
    REMOVED,
    SIGNATURE_CHANGED,
    ACCESS_NARROWED,
    ACCESS_WIDENED,
    /** An abstract (non-default) method newly required of downstream implementors. */
    ABSTRACT_METHOD_ADDED,
    /** A class's direct superclass was added, removed or replaced. */
    SUPERCLASS_CHANGED
}
