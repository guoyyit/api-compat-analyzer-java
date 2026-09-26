package com.github.guoyyit.apicompatanalyzerjava.model;

/** A precise position in a source file. */
public record SourceLocation(String path, int line, int column) {
}
