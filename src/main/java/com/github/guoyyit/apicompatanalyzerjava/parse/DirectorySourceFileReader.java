package com.github.guoyyit.apicompatanalyzerjava.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Reads {@code .java} files from a directory snapshot, recursively. */
public class DirectorySourceFileReader implements SourceFileReader {

    private final Path root;

    public DirectorySourceFileReader(Path root) {
        this.root = root;
    }

    @Override
    public List<SourceFile> listFiles() throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("snapshot root is not a directory: " + root);
        }
        List<SourceFile> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        Path relative = root.relativize(p);
                        files.add(new SourceFile(relative.toString().replace('\\', '/'),
                                sizeQuietly(p)));
                    });
        }
        files.sort((a, b) -> a.path().compareTo(b.path()));
        return files;
    }

    @Override
    public String readContent(SourceFile file) throws IOException {
        return Files.readString(root.resolve(file.path()), StandardCharsets.UTF_8);
    }

    private static long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }
}
