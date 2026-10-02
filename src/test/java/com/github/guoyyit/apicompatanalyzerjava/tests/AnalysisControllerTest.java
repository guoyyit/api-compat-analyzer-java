package com.github.guoyyit.apicompatanalyzerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullAnalysisFlowOverRest() throws Exception {
        String baseline = """
                {"version": "1.0", "sources": {"Service.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V1));
        mockMvc.perform(post("/api/analysis/baseline")
                        .contentType(MediaType.APPLICATION_JSON).content(baseline))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elements", greaterThan(0)));

        String full = """
                {"version": "2.0", "sources": {"Service.java": %s, "Caller.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V2), json(Fixtures.CALLER));
        mockMvc.perform(post("/api/analysis/full")
                        .contentType(MediaType.APPLICATION_JSON).content(full))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is("2.0")))
                .andExpect(jsonPath("$.changes[?(@.breaking == true)]").isNotEmpty());

        mockMvc.perform(get("/api/analysis/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is("2.0")));
    }

    @Test
    void incrementalEndpointAcceptsChangedAndDeleted() throws Exception {
        String baseline = """
                {"version": "1.0", "sources": {"Service.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V1));
        mockMvc.perform(post("/api/analysis/baseline")
                        .contentType(MediaType.APPLICATION_JSON).content(baseline))
                .andExpect(status().isOk());

        String full = """
                {"version": "1.0", "sources": {"Service.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V1));
        mockMvc.perform(post("/api/analysis/full")
                        .contentType(MediaType.APPLICATION_JSON).content(full))
                .andExpect(status().isOk());

        String incremental = """
                {"version": "2.0", "changed": {"Service.java": %s}, "deleted": []}
                """.formatted(json(Fixtures.SERVICE_V2));
        mockMvc.perform(post("/api/analysis/incremental")
                        .contentType(MediaType.APPLICATION_JSON).content(incremental))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentVersion", is("2.0")));
    }

    @Test
    void repeatedFullAnalysesReturnTheSameDeterministicReportId() throws Exception {
        String baseline = """
                {"version": "1.0", "sources": {"Service.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V1));
        mockMvc.perform(post("/api/analysis/baseline")
                        .contentType(MediaType.APPLICATION_JSON).content(baseline))
                .andExpect(status().isOk());

        String full = """
                {"version": "2.0", "sources": {"Service.java": %s, "Caller.java": %s}}
                """.formatted(json(Fixtures.SERVICE_V2), json(Fixtures.CALLER));
        String firstId = postFullAndExtractId(full);
        String secondId = postFullAndExtractId(full);
        assertThat(firstId).isNotBlank();
        assertThat(secondId).isEqualTo(firstId);
    }

    private String postFullAndExtractId(String body) throws Exception {
        String response = mockMvc.perform(post("/api/analysis/full")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("reportVersion").asText();
    }

    /** Produces a JSON string literal for the given Java source. */
    private static String json(String source) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : source.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
