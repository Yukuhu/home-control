package dev.andre.homecontrol.web;

import dev.andre.homecontrol.testsupport.WebSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON content API had no caller; the dashboard reads rails and search results as HTML. */
class ContentApiRemovedTest extends WebSliceTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void theJsonContentApiIsGone() throws Exception {
        mockMvc.perform(get("/sources")).andExpect(status().isNotFound());
        mockMvc.perform(get("/sources/tmdb/rails/trending")).andExpect(status().isNotFound());
        mockMvc.perform(post("/sources/tmdb/rails/trending/refresh")).andExpect(status().isNotFound());
        mockMvc.perform(get("/search").param("q", "matrix")).andExpect(status().isNotFound());
        mockMvc.perform(post("/search").param("source", "youtube").param("q", "bunny")).andExpect(status().isNotFound());
    }
}
