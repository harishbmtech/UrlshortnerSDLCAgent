package com.example.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack test of the URL shortener: HTTP -> controllers -> service -> JPA -> H2 (Flyway schema).
 */
@SpringBootTest(properties = {"shortener.rate-limit.capacity=1000", "spring.ai.model.chat=none"})
@AutoConfigureMockMvc
class ShortUrlApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createRedirectStatsAndDisable() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/docs\",\"alias\":\"it-docs\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/urls/it-docs"))
                .andExpect(jsonPath("$.code").value("it-docs"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/it-docs"));

        mvc.perform(get("/it-docs").header("Referer", "https://news.example"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"))
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(get("/api/v1/urls/it-docs/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(1))
                .andExpect(jsonPath("$.topReferrers[0].referrer").value("https://news.example"));

        mvc.perform(delete("/api/v1/urls/it-docs")).andExpect(status().isNoContent());
        mvc.perform(get("/it-docs")).andExpect(status().isGone());
    }

    @Test
    void validationAndErrorMapping() throws Exception {
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"http://169.254.169.254/latest/meta-data\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"alias\":\"dup-alias\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.org\",\"alias\":\"dup-alias\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/nosuchcode")).andExpect(status().isNotFound());
    }
}
