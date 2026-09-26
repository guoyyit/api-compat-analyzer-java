package com.github.guoyyit.apicompatanalyzerjava.model;

/** What happened to an API identity between the baseline and the current snapshot. */
public enum ChangeNature {
    ADDED,
    REMOVED,
    SIGNATURE_CHANGED
}
