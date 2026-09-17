package dev.andre.homecontrol.e2e;

import dev.andre.homecontrol.core.content.PinnedLinks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Adds the fake device adapter and content source the browser tests drive to the real application context. */
@TestConfiguration
public class E2eFakesConfiguration {

    @Bean
    public FakeDeviceAdapter fakeDeviceAdapter() {
        return new FakeDeviceAdapter();
    }

    @Bean
    public FakeContentSource fakeContentSource(ObjectProvider<PinnedLinks> pinnedLinks) {
        return new FakeContentSource(pinnedLinks);
    }
}
