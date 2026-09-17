package dev.andre.homecontrol.web;

import dev.andre.homecontrol.content.RailCache;
import dev.andre.homecontrol.content.RailSnapshot;
import dev.andre.homecontrol.content.RailStatus;
import dev.andre.homecontrol.core.DeviceState;
import dev.andre.homecontrol.core.DeviceStatus;
import dev.andre.homecontrol.core.content.RailDescriptor;
import dev.andre.homecontrol.core.playback.ContentItem;
import dev.andre.homecontrol.core.playback.ContentKind;
import dev.andre.homecontrol.device.DeviceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StateController.class)
class StateControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceManager devices;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @MockitoBean
    RailCache rails;

    @Test
    void aNewSubscriberGetsOneStateEventPerDevice() throws Exception {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        states.put("living", new DeviceState(DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.EPOCH));
        states.put("bedroom", DeviceState.unpaired());
        given(devices.states()).willReturn(states);
        given(broadcaster.subscribe(any())).willReturn(new SseEmitter(0L));
        given(rails.peek()).willReturn(List.of());

        MvcResult result = mockMvc.perform(get("/events").accept("text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:state");
        assertThat(body.indexOf("\"deviceId\":\"living\"")).isLessThan(body.indexOf("\"deviceId\":\"bedroom\""));
        assertThat(body).contains("\"currentApp\":\"com.netflix.ninja\"").contains("\"status\":\"UNPAIRED\"");
    }

    @Test
    void aNewSubscriberAlsoGetsOneRailSummaryPerCachedRail() throws Exception {
        Map<String, DeviceState> states = new LinkedHashMap<>();
        states.put("living", DeviceState.unpaired());
        given(devices.states()).willReturn(states);
        given(broadcaster.subscribe(any())).willReturn(new SseEmitter(0L));
        RailDescriptor descriptor = new RailDescriptor("jellyfin", "a", "Rail A");
        ContentItem item = new ContentItem("item-1", "jellyfin", ContentKind.MOVIE, "Big Buck Bunny", null, null, List.of());
        RailSnapshot snapshot = new RailSnapshot(descriptor, RailStatus.READY,
                List.of(item), Instant.EPOCH, null, false, 1);
        given(rails.peek()).willReturn(List.of(snapshot));

        MvcResult result = mockMvc.perform(get("/events").accept("text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body.indexOf("event:state")).isLessThan(body.indexOf("event:rail"));
        assertThat(body).contains("\"railId\":\"a\"").doesNotContain("Big Buck Bunny");
    }
}
