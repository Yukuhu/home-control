package dev.andre.homecontrol.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IconController.class)
class IconControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void servesEveryIconSizeAsPng() throws Exception {
        assertIcon("icon-192", 192);
        assertIcon("icon-512", 512);
        assertIcon("maskable-512", 512);
        assertIcon("apple-touch-icon", 180);
    }

    private void assertIcon(String name, int expectedSize) throws Exception {
        byte[] bytes = mockMvc.perform(get("/icons/" + name + ".png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0x89);
        assertThat(bytes[1]).isEqualTo((byte) 'P');
        assertThat(bytes[2]).isEqualTo((byte) 'N');
        assertThat(bytes[3]).isEqualTo((byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image.getWidth()).isEqualTo(expectedSize);
        assertThat(image.getHeight()).isEqualTo(expectedSize);
    }

    @Test
    void anyIconsHaveTransparentCornersAndMaskableOnesAreFullBleed() {
        BufferedImage anyIcon = IconRenderer.render(512, IconRenderer.Shape.ROUNDED);
        assertThat((anyIcon.getRGB(1, 1) >>> 24) & 0xFF).isZero();

        BufferedImage maskable = IconRenderer.render(512, IconRenderer.Shape.MASKABLE);
        assertOpaqueBackground(maskable, 1, 1);

        BufferedImage appleTouchIcon = IconRenderer.render(180, IconRenderer.Shape.FULL_BLEED);
        assertOpaqueBackground(appleTouchIcon, 1, 1);
    }

    private void assertOpaqueBackground(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        assertThat((rgb >>> 24) & 0xFF).isEqualTo(255);
        assertThat(rgb & 0xFFFFFF).isEqualTo(0x101917);
    }

    @Test
    void thePlayTriangleIsAccentColoured() {
        int size = 512;
        BufferedImage image = IconRenderer.render(size, IconRenderer.Shape.ROUNDED);
        int rgb = image.getRGB((int) Math.round(0.50 * size), (int) Math.round(0.46 * size));
        int expected = 0xb7e6a0;
        assertThat(Math.abs(((rgb >> 16) & 0xFF) - ((expected >> 16) & 0xFF))).isLessThanOrEqualTo(8);
        assertThat(Math.abs(((rgb >> 8) & 0xFF) - ((expected >> 8) & 0xFF))).isLessThanOrEqualTo(8);
        assertThat(Math.abs((rgb & 0xFF) - (expected & 0xFF))).isLessThanOrEqualTo(8);
    }

    @Test
    void maskableContentStaysInTheSafeZone() {
        int size = 512;
        BufferedImage image = IconRenderer.render(size, IconRenderer.Shape.MASKABLE);
        double centre = size / 2.0;
        double radius = 0.4 * size;
        int background = 0xFF000000 | 0x101917;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (image.getRGB(x, y) == background) continue;
                double distance = Math.hypot(x - centre, y - centre);
                assertThat(distance).as("pixel (%d,%d)", x, y).isLessThanOrEqualTo(radius);
            }
        }
    }

    @Test
    void unknownIconIs404() throws Exception {
        mockMvc.perform(get("/icons/bogus.png")).andExpect(status().isNotFound());
    }

    @Test
    void rendersOncePerName() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IconController controller = new IconController((size, shape) -> {
            calls.incrementAndGet();
            return IconRenderer.png(size, shape);
        });
        MockMvc standalone = MockMvcBuilders.standaloneSetup(controller).build();

        standalone.perform(get("/icons/icon-192.png")).andExpect(status().isOk());
        standalone.perform(get("/icons/icon-192.png")).andExpect(status().isOk());

        assertThat(calls.get()).isEqualTo(1);
    }
}
