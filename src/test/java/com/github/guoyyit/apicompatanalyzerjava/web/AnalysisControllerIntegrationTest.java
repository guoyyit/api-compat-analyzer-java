package com.github.guoyyit.apicompatanalyzerjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.apicompatanalyzerjava.model.AnalysisOptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AnalysisControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> file(String path, String content) {
        return Map.of("path", path, "content", content);
    }

    private String serviceApi(String body) {
        return "package com.acme;\npublic class Service { " + body + " }\n";
    }

    private String caller() {
        return "package com.acme;\n"
                + "public class Caller { public String use(Service s) { return s.greet(\"a\"); } }\n";
    }

    @Test
    void fullAnalysisReturnsBreakingChangeWithCallSite() throws Exception {
        String baselineService = serviceApi(
                "public String greet(String n) { return n; }");
        // Breaking: the String overload is removed; caller still invokes it.
        String currentService = serviceApi("public String greet() { return \"\"; }");

        Map<String, Object> request = Map.of(
                "baselineFiles", List.of(
                        file("com/acme/Service.java", baselineService),
                        file("com/acme/Caller.java", caller())),
                "currentFiles", List.of(
                        file("com/acme/Service.java", currentService),
                        file("com/acme/Caller.java", caller())),
                "options", AnalysisOptions.defaults());

        mvc.perform(post("/api/analysis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].callSites[0].path")
                        .value("com/acme/Caller.java"))
                .andExpect(jsonPath("$.impacts[0].callSites[0].symbol").value("greet"))
                .andExpect(jsonPath("$.impacts[0].callSites[0].line").isNumber());
    }

    @Test
    void baselinePublishThenIncrementalAndLatestReport() throws Exception {
        Map<String, Object> publishBody = Map.of(
                "files", List.of(
                        file("com/acme/Service.java", serviceApi("public String greet(String n) { return n; }")),
                        file("com/acme/Caller.java", caller())));
        mvc.perform(post("/api/analysis/baselines/b-http-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(publishBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baselineId").value("b-http-1"))
                .andExpect(jsonPath("$.snapshotId").isString());

        Map<String, Object> incrementalBody = Map.of(
                "baselineId", "b-http-1",
                "files", List.of(
                        file("com/acme/Service.java", serviceApi("public String greet(String n) { return \"v2\"; }")),
                        file("com/acme/Caller.java", caller())));
        mvc.perform(post("/api/analysis/incremental")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(incrementalBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incremental").value(false));

        mvc.perform(get("/api/analysis/reports/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reportId").isString());

        mvc.perform(delete("/api/analysis/baselines/b-http-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void missingBaselineReturns400() throws Exception {
        Map<String, Object> body = Map.of(
                "baselineId", "does-not-exist",
                "files", List.of(file("com/acme/Service.java", serviceApi("public String greet() { return \"\"; }"))));
        mvc.perform(post("/api/analysis/incremental")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ANALYSIS_REJECTED"));
    }

    @Test
    void timeoutReturns504AndStableErrorBody() throws Exception {
        // The current snapshot file blocks long enough to exceed an explicit short budget.
        java.util.Map<String, Object> slowFile = new java.util.HashMap<>();
        slowFile.put("path", "com/acme/Service.java");
        slowFile.put("content", serviceApi("public String greet() { return \"\"; }"));
        slowFile.put("delayMillis", 2000);

        Map<String, Object> request = Map.of(
                "baselineFiles", List.of(
                        file("com/acme/Service.java",
                                serviceApi("public String greet(String n) { return n; }"))),
                "currentFiles", List.of(slowFile),
                "options", new AnalysisOptions(100, 0, 0, 0));
        mvc.perform(post("/api/analysis/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.error").value("ANALYSIS_TIMEOUT"));
    }
}
