package dev.andre.homecontrol.web;

import dev.andre.homecontrol.config.Json;
import dev.andre.homecontrol.testsupport.FullAppReset;
import dev.andre.homecontrol.testsupport.FullAppTest;
import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ThemePageTest extends FullAppTest {

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext app;

    @AfterEach
    void reset() {
        FullAppReset.reset(app);
    }

    @Test
    void bothBuiltinsAreListedAndProtectedByTheServer() throws Exception {
        mvc.perform(get("/themes/catalog.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultId").value("default"))
                .andExpect(jsonPath("$.themes[0].id").value("default"))
                .andExpect(jsonPath("$.themes[0].builtIn").value(true))
                .andExpect(jsonPath("$.themes[1].id").value("cyberpunk"))
                .andExpect(jsonPath("$.themes[1].builtIn").value(true));
        for (String id : new String[] {"default", "cyberpunk"}) {
            mvc.perform(post("/setup/appearance/" + id + "/remove"))
                    .andExpect(status().isConflict());
            byte[] zip = export(id);
            mvc.perform(multipart("/setup/appearance/preview")
                    .file(new MockMultipartFile("package", "theme.zip", "application/zip", zip)))
                    .andExpect(status().isConflict());
        }
    }

    @Test
    void exportedThemeCanBeRenamedReviewedInstalledAndExportedAgain() throws Exception {
        byte[] zip = derivative(export("cyberpunk"), "shared-neon", "1", "Shared <Neon>", true);
        MockHttpSession session = new MockHttpSession();
        MvcResult review = preview(session, zip);
        assertThat(review.getResponse().getContentAsString()).contains("Shared &lt;Neon&gt;");
        assertThat(review.getResponse().getContentAsString()).contains("src=\"data:image/png;base64,");
        mvc.perform(get("/themes/catalog.json"))
                .andExpect(content().string(not(containsString("shared-neon"))));
        mvc.perform(post("/setup/appearance/install").session(session).param("token", token(review)))
                .andExpect(status().is3xxRedirection());
        String catalog = mvc.perform(get("/themes/catalog.json")).andReturn().getResponse().getContentAsString();
        var descriptor = Json.MAPPER.readTree(catalog).path("themes").get(2);
        assertThat(descriptor.path("id").asText()).isEqualTo("shared-neon");
        assertThat(descriptor.path("builtIn").asBoolean()).isFalse();
        mvc.perform(get(descriptor.path("stylesheet").asText())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
        assertThat(export("shared-neon")).isNotEmpty();
        mvc.perform(post("/setup/appearance/shared-neon/remove")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/themes/catalog.json"))
                .andExpect(content().string(not(containsString("shared-neon"))));
    }

    @Test
    void aConfirmationCannotReplaceARevisionInstalledAfterTheReview() throws Exception {
        byte[] base = export("default");
        install(derivative(base, "revision-test", "1", "Revision"));
        MockHttpSession stale = new MockHttpSession();
        String token = token(preview(stale, derivative(base, "revision-test", "2", "Revision")));
        install(derivative(base, "revision-test", "3", "Revision"));
        mvc.perform(post("/setup/appearance/install").session(stale).param("token", token))
                .andExpect(status().isConflict());
        mvc.perform(get("/themes/catalog.json")).andExpect(jsonPath("$.themes[2].version").value("3"));
    }

    @Test
    void confirmationIsBoundToItsSessionAndConsumedOnce() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        String token = token(preview(owner, derivative(export("default"), "session-test", "1", "Session")));
        mvc.perform(post("/setup/appearance/install").session(new MockHttpSession()).param("token", token))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/setup/appearance/install").session(owner).param("token", token))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/setup/appearance/install").session(owner).param("token", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void abandonedReviewsCannotRetainUnboundedUploads() throws Exception {
        byte[] zip = derivative(export("default"), "bounded-reviews", "1", "Bounded");
        MockHttpSession abandoned = new MockHttpSession();
        String oldToken = token(preview(abandoned, zip));
        MockHttpSession latest = null;
        String latestToken = null;
        for (int i = 0; i < 8; i++) {
            latest = new MockHttpSession();
            latestToken = token(preview(latest, zip));
        }
        mvc.perform(post("/setup/appearance/install").session(abandoned).param("token", oldToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/setup/appearance/install").session(latest).param("token", latestToken))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void uploadsHaveFiniteDiskSpoolingAndRequestLimits() {
        MultipartConfigElement limits = app.getBean(MultipartConfigElement.class);
        assertThat(limits.getMaxFileSize()).isEqualTo(10L * 1024 * 1024);
        assertThat(limits.getMaxRequestSize()).isEqualTo(11L * 1024 * 1024);
        assertThat(limits.getFileSizeThreshold()).isZero();
    }

    @Test
    void oversizedUploadIsRejectedBeforePackageParsing() throws Exception {
        mvc.perform(multipart("/setup/appearance/preview")
                        .file(new MockMultipartFile("package", "theme.zip", "application/zip",
                                new byte[10 * 1024 * 1024 + 1])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().string(containsString("Theme ZIP must be no larger than 10 MiB.")));
    }

    @Test
    void recoveryPageExplicitlyBypassesTheSavedTheme() throws Exception {
        mvc.perform(get("/setup/appearance/recovery"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"theme-recovery\"")))
                .andExpect(content().string(containsString("data-theme-reset")));
        mvc.perform(get("/setup/appearance")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("name=\"theme-recovery\""))));
    }

    private byte[] export(String id) throws Exception {
        return mvc.perform(get("/setup/appearance/" + id + "/export")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private MvcResult preview(MockHttpSession session, byte[] zip) throws Exception {
        return mvc.perform(multipart("/setup/appearance/preview").session(session)
                        .file(new MockMultipartFile("package", "theme.zip", "application/zip", zip)))
                .andExpect(status().isOk()).andReturn();
    }

    private void install(byte[] zip) throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = token(preview(session, zip));
        mvc.perform(post("/setup/appearance/install").session(session).param("token", token))
                .andExpect(status().is3xxRedirection());
    }

    private static String token(MvcResult review) {
        return (String) review.getModelAndView().getModel().get("importToken");
    }

    private static byte[] derivative(byte[] source, String id, String version, String name) throws Exception {
        return derivative(source, id, version, name, false);
    }

    private static byte[] derivative(byte[] source, String id, String version, String name, boolean preview) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
                byte[] value = in.readAllBytes();
                if (entry.getName().equals("theme.json")) {
                    ObjectNode manifest = (ObjectNode) Json.MAPPER.readTree(value);
                    manifest.put("id", id).put("name", name).put("version", version);
                    value = Json.MAPPER.writeValueAsBytes(manifest);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(value);
                out.closeEntry();
            }
            if (preview) {
                out.putNextEntry(new ZipEntry("preview.png"));
                javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2,
                        java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
