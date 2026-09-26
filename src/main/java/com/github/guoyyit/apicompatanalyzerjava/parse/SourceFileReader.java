package com.github.guoyyit.apicompatanalyzerjava.parse;

import java.io.IOException;
import java.util.List;

/** Abstraction over a source snapshot (a directory, a set of strings in tests, etc.). */
public interface SourceFileReader {

    /** Enumerate all {@code .java} files of the snapshot. */
    List<SourceFile> listFiles() throws IOException;

    /** Read the full UTF-8 content of one file. */
    String readContent(SourceFile file) throws IOException;
}
