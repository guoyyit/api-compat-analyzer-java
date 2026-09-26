package com.github.guoyyit.apicompatanalyzerjava.parse;

/** Parses one Java source file into a {@link ParsedUnit}. */
public interface SourceParser {
    ParsedUnit parse(String file, String source);
}
