package com.github.guoyyit.apicompatanalyzerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.List;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
/** One detected change between two API snapshots. */
public final class ApiChange {
    private final ChangeKind kind;
    private final boolean breaking;
    private final String elementKey;
    private final String description;
    private final String oldDetail;
    private final String newDetail;
    private final List<CallSite> affectedCallSites;
    private final boolean suppressed;

    public ApiChange(ChangeKind kind, boolean breaking, String elementKey, String description,
                     String oldDetail, String newDetail, List<CallSite> affectedCallSites) {
        this(kind, breaking, elementKey, description, oldDetail, newDetail, affectedCallSites, false);
    }

    public ApiChange(ChangeKind kind, boolean breaking, String elementKey, String description,
                     String oldDetail, String newDetail, List<CallSite> affectedCallSites,
                     boolean suppressed) {
        this.kind = kind;
        this.breaking = breaking;
        this.elementKey = elementKey;
        this.description = description;
        this.oldDetail = oldDetail;
        this.newDetail = newDetail;
        this.affectedCallSites = List.copyOf(affectedCallSites);
        this.suppressed = suppressed;
    }

    public ChangeKind kind() { return kind; }
    public boolean breaking() { return breaking; }
    public String elementKey() { return elementKey; }
    public String description() { return description; }
    public String oldDetail() { return oldDetail; }
    public String newDetail() { return newDetail; }
    public List<CallSite> affectedCallSites() { return affectedCallSites; }
    /** True when an exemption declaration covers this change. */
    public boolean suppressed() { return suppressed; }
}
