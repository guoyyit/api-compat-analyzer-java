package com.github.guoyyit.apicompatanalyzerjava.web;

import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisReport;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisRejectedException;
import com.github.guoyyit.apicompatanalyzerjava.service.AnalysisService;
import com.github.guoyyit.apicompatanalyzerjava.storage.BaselineRepository;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.AnalyzeRequest;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.PublishBaselineRequest;
import com.github.guoyyit.apicompatanalyzerjava.web.dto.UpdateAndAnalyzeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for full analysis, baseline management and incremental updates. */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final BaselineRepository baselineRepository;

    public AnalysisController(AnalysisService analysisService,
                              BaselineRepository baselineRepository) {
        this.analysisService = analysisService;
        this.baselineRepository = baselineRepository;
    }

    @PostMapping("/analyze")
    public AnalysisReport analyze(@RequestBody AnalyzeRequest request) {
        AnalysisOptions options = optionsOrDefault(request.options());
        if (request.baselineFiles() != null && !request.baselineFiles().isEmpty()) {
            return analysisService.analyzeSnapshots(
                    new InMemorySourceFileReader(request.baselineFiles()),
                    new InMemorySourceFileReader(request.currentFiles()),
                    options);
        }
        if (request.baselineId() == null || request.baselineId().isBlank()) {
            throw new AnalysisRejectedException("provide baselineFiles or baselineId");
        }
        return analysisService.analyzeAgainstBaseline(request.baselineId(),
                new InMemorySourceFileReader(request.currentFiles()), options);
    }

    @PostMapping("/baselines/{baselineId}")
    @ResponseStatus(HttpStatus.CREATED)
    public String publishBaseline(@PathVariable String baselineId,
                                  @RequestBody PublishBaselineRequest request) {
        String effectiveId = request.baselineId() != null && !request.baselineId().isBlank()
                ? request.baselineId() : baselineId;
        String snapshotId = analysisService.publishBaseline(effectiveId,
                new InMemorySourceFileReader(request.files()),
                optionsOrDefault(request.options()));
        return "{\"baselineId\":\"" + effectiveId + "\",\"snapshotId\":\"" + snapshotId + "\"}";
    }

    @PostMapping("/incremental")
    public AnalysisReport updateAndAnalyze(@RequestBody UpdateAndAnalyzeRequest request) {
        if (request.baselineId() == null || request.baselineId().isBlank()) {
            throw new AnalysisRejectedException("baselineId is required");
        }
        return analysisService.updateAndAnalyze(request.baselineId(),
                new InMemorySourceFileReader(request.files()),
                optionsOrDefault(request.options()));
    }

    @GetMapping("/reports/latest")
    public AnalysisReport latestReport() {
        AnalysisReport report = analysisService.latestReport();
        if (report == null) {
            throw new AnalysisRejectedException("no report has been published yet");
        }
        return report;
    }

    @DeleteMapping("/baselines/{baselineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBaseline(@PathVariable String baselineId) {
        baselineRepository.delete(baselineId);
    }

    private static AnalysisOptions optionsOrDefault(AnalysisOptions options) {
        return options == null ? AnalysisOptions.defaults() : options;
    }
}
