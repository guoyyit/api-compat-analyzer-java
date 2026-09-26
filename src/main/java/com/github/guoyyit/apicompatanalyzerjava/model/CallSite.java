package com.github.guoyyit.apicompatanalyzerjava.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;

import java.util.Objects;

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
/** One location in source that references an API element. */
public final class CallSite {
    private final String file;
    private final int line;
    private final int column;
    private final String enclosingSymbol;
    private final String referencedKey;

    public CallSite(String file, int line, int column, String enclosingSymbol, String referencedKey) {
        this.file = file;
        this.line = line;
        this.column = column;
        this.enclosingSymbol = enclosingSymbol;
        this.referencedKey = referencedKey;
    }

    public String file() { return file; }
    public int line() { return line; }
    public int column() { return column; }
    public String enclosingSymbol() { return enclosingSymbol; }
    public String referencedKey() { return referencedKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallSite other)) return false;
        return line == other.line && column == other.column
                && Objects.equals(file, other.file)
                && Objects.equals(referencedKey, other.referencedKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, line, column, referencedKey);
    }
}
