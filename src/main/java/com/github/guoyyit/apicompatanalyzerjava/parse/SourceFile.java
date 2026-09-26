package com.github.guoyyit.apicompatanalyzerjava.parse;

/**
 * One source file fed to the parser. Abstraction lets tests provide in-memory projects
 * without touching the file system.
 *
 * @param path path relative to a snapshot root, e.g. {@code com/acme/Foo.java}
 * @param size file size in bytes as seen by the reader
 */
public record SourceFile(String path, long size) {
}
