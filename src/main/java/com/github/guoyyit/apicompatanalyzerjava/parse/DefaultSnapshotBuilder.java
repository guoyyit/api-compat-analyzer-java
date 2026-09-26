package com.github.guoyyit.apicompatanalyzerjava.parse;

import com.github.guoyyit.apicompatanalyzerjava.model.ApiElement;
import com.github.guoyyit.apicompatanalyzerjava.model.CallSite;
import com.github.guoyyit.apicompatanalyzerjava.model.SnapshotModel;
import com.github.guoyyit.apicompatanalyzerjava.model.Suppression;
import com.github.guoyyit.apicompatanalyzerjava.util.Hashes;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link SnapshotModel} from parsed units.
 *
 * <p>The same {@link #assemble} routine backs full and incremental builds. Only file
 * content hashes, paths and a parser-version tag enter the snapshot id, so two builds of
 * the same sources always produce the same id and model.
 */
@Component
public class DefaultSnapshotBuilder implements SnapshotBuilder {

    private final JavaSourceParser parser;

    public DefaultSnapshotBuilder(JavaSourceParser parser) {
        this.parser = parser;
    }

    @Override
    public SnapshotModel build(SourceFileReader reader, ResourceBudget budget) {
        List<SourceFile> files = list(reader);
        budget.checkFileAccepted(files.size());
        files.forEach(budget::checkFileSize);
        Map<String, ParsedUnit> units = new LinkedHashMap<>();
        for (SourceFile file : files) {
            budget.tick();
            units.put(file.path(), parseOne(reader, file));
        }
        budget.tick();
        return assemble(units);
    }

    @Override
    public SnapshotModel buildIncremental(SourceFileReader reader, ResourceBudget budget,
                                          SnapshotModel previous, Set<String> changedPaths) {
        List<SourceFile> files = list(reader);
        budget.checkFileAccepted(files.size());
        files.forEach(budget::checkFileSize);

        Map<String, ParsedUnit> units = new LinkedHashMap<>();
        // Reuse untouched units in path order; deleted files are simply not carried over.
        for (SourceFile file : files) {
            if (changedPaths.contains(file.path()) || !previous.units().containsKey(file.path())) {
                budget.tick();
                units.put(file.path(), parseOne(reader, file));
            } else {
                units.put(file.path(), previous.units().get(file.path()));
            }
        }
        budget.tick();
        return assemble(units);
    }

    private List<SourceFile> list(SourceFileReader reader) {
        try {
            List<SourceFile> files = new ArrayList<>(reader.listFiles());
            files.sort((a, b) -> a.path().compareTo(b.path()));
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ParsedUnit parseOne(SourceFileReader reader, SourceFile file) {
        try {
            String content = reader.readContent(file);
            return parser.parse(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Aggregate parsed units into the immutable snapshot model. Deterministic ordering. */
    public SnapshotModel assemble(Map<String, ParsedUnit> parsedUnits) {
        Map<String, String> fileHashes = new TreeMap<>();
        Map<String, ParsedUnit> sortedUnits = new TreeMap<>();
        Map<String, ApiElement> elementsById = new TreeMap<>();
        List<CallSite> callSites = new ArrayList<>();
        List<Suppression> suppressions = new ArrayList<>();
        Map<String, Set<String>> subtypeGraph = new TreeMap<>();
        List<String> sourceFiles = new ArrayList<>();

        for (Map.Entry<String, ParsedUnit> entry : new TreeMap<>(parsedUnits).entrySet()) {
            String path = entry.getKey();
            ParsedUnit unit = entry.getValue();
            sortedUnits.put(path, unit);
            sourceFiles.add(path);
            // Full content hash: any edit (including body-only edits) invalidates the file.
            fileHashes.put(path, unit.contentHash());

            for (ApiElement element : unit.apiElements()) {
                elementsById.put(element.id(), element);
            }
            callSites.addAll(unit.callSites());
            suppressions.addAll(unit.suppressions());
        }

        // Subtype graph: declared type -> declared super types that are declared in snapshot.
        for (ParsedUnit unit : sortedUnits.values()) {
            for (String type : unit.declaredTypes()) {
                Set<String> supers = new java.util.TreeSet<>();
                for (ApiElement element : unit.apiElements()) {
                    if (element.kind() == com.github.guoyyit.apicompatanalyzerjava.model.ApiKind.TYPE
                            && element.id().equals(type)) {
                        supers.addAll(element.superTypes());
                    }
                }
                subtypeGraph.put(type, supers);
            }
        }
        callSites.sort((a, b) -> {
            int c = a.path().compareTo(b.path());
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.line(), b.line());
            if (c != 0) {
                return c;
            }
            return a.symbol().compareTo(b.symbol());
        });
        suppressions.sort((a, b) -> {
            int c = a.elementId().compareTo(b.elementId());
            return c != 0 ? c : a.path().compareTo(b.path());
        });

        String snapshotId = Hashes.contentHash(versionedHashes(fileHashes));
        return new SnapshotModel(snapshotId, Map.copyOf(fileHashes), Map.copyOf(sortedUnits),
                Map.copyOf(elementsById), List.copyOf(callSites), deepImmutableGraph(subtypeGraph),
                List.copyOf(suppressions), List.copyOf(sourceFiles));
    }

    private Map<String, String> versionedHashes(Map<String, String> fileHashes) {
        Map<String, String> parts = new LinkedHashMap<>(fileHashes);
        parts.put("__parser__", JavaParserJavaSourceParser.PARSER_VERSION);
        return parts;
    }

    private Map<String, Set<String>> deepImmutableGraph(Map<String, Set<String>> graph) {
        Map<String, Set<String>> copy = new TreeMap<>();
        graph.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Map.copyOf(copy);
    }
}
