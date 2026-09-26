package com.github.guoyyit.apicompatanalyzerjava.parse;

/** Parses a single Java source file into a {@link ParsedUnit}. */
public interface JavaSourceParser {

    ParsedUnit parse(SourceFile file, String content);
}
