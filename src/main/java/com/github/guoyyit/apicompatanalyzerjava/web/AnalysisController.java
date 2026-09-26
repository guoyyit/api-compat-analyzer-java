package com.github.guoyyit.apicompatanalyzerjava.web;

import com.github.guoyyit.apicompatanalyzerjava.model.CompatReport;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.service.BaselineStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** REST entry points for full analysis, incremental update and report reads. */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final BaselineStore baselineStore;

    public AnalysisController(AnalysisService analysisService, BaselineStore baselineStore) {
        this.analysisService = analysisService;
        this.baselineStore = baselineStore;
    }

    /** Request carrying a full source tree: file path -> source content. */
    public record SourcesRequest(String version, Map<String, String> sources) {
    }

    /** Incremental update request: changed/new files plus deleted file paths. */
    public record IncrementalRequest(String version, Map<String, String> changed,
                                     Set<String> deleted) {
    }

    @PostMapping("/baseline")
    public Map<String, Object> setBaseline(@RequestBody SourcesRequest request) {
        analysisService.setBaseline(request.sources(), request.version());
        return Map.of("baselineVersion", request.version(),
                "elements", analysisService.baselineSnapshot().elements().size());
    }

    @PostMapping("/baseline/promote")
    public Map<String, Object> promoteBaseline() {
        analysisService.promoteCurrentToBaseline();
        return Map.of("baselineVersion", analysisService.baselineSnapshot().version());
    }

    @PostMapping("/baseline/save")
    public Map<String, Object> saveBaseline(@RequestParam String path) {
        baselineStore.save(analysisService.baselineSnapshot(),
                analysisService.baselineSuppressions(), Path.of(path));
        return Map.of("saved", path);
    }

    @PostMapping("/baseline/load")
    public Map<String, Object> loadBaseline(@RequestParam String path) {
        BaselineStore.BaselineData data = baselineStore.load(Path.of(path));
        analysisService.installBaselineSnapshot(data.snapshot(), data.suppressedKeys());
        return Map.of("baselineVersion", data.snapshot().version(),
                "elements", data.snapshot().elements().size(),
                "suppressions", data.suppressedKeys().size());
    }

    @PostMapping("/full")
    public CompatReport full(@RequestBody SourcesRequest request) {
        return analysisService.analyzeFull(request.sources(), request.version());
    }

    @PostMapping("/incremental")
    public CompatReport incremental(@RequestBody IncrementalRequest request) {
        return analysisService.analyzeIncremental(
                request.changed() == null ? Map.of() : request.changed(),
                request.deleted() == null ? Set.of() : request.deleted(),
                request.version());
    }

    @GetMapping("/report")
    public ResponseEntity<CompatReport> report() {
        return analysisService.currentReport()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
