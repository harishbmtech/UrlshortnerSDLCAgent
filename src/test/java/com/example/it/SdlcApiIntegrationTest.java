package com.example.it;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The orchestrator through its REST API with Spring wiring (offline LLM): start -> human gate -> publish.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "sdlc.output-dir=target/sdlc-it"})
@AutoConfigureMockMvc
class SdlcApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void brownfieldRunThroughHumanReleaseGate() throws Exception {
        String body = mvc.perform(post("/api/v1/sdlc/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"Add an optional max-clicks limit to existing short links: once a link "
                                + "has been followed N times it must stop redirecting and return 410 Gone.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AWAITING_RELEASE_APPROVAL"))
                .andExpect(jsonPath("$.spec.changeType").value("BROWNFIELD"))
                .andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.runId");

        // unauthorised approver is refused
        mvc.perform(post("/api/v1/sdlc/runs/" + runId + "/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gate\":\"release_approval\",\"verdict\":\"APPROVE\",\"approver\":\"intern\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/sdlc/runs/" + runId + "/decisions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gate\":\"release_approval\",\"verdict\":\"APPROVE\",\"approver\":\"release-manager\",\"comment\":\"ship it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.publishedTo").exists());

        mvc.perform(get("/api/v1/sdlc/runs/" + runId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVerified").value(true));
        mvc.perform(get("/api/v1/sdlc/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runsCompleted").exists());
        mvc.perform(get("/api/v1/sdlc/runs/" + runId + "/summary")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/sdlc/runs/does-not-exist")).andExpect(status().isNotFound());
    }
}
