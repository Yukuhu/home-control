package dev.andre.homecontrol.discovery;

import dev.andre.homecontrol.discovery.ssdp.SsdpConfiguration;
import dev.andre.homecontrol.discovery.ssdp.SsdpDiscovery;
import dev.andre.homecontrol.discovery.ssdp.SsdpProperties;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * mDNS and SSDP start listening only once the application is ready, so no discovery event can reach the context
 * before its listeners exist, and nothing has to replay the ones that did.
 */
class DiscoveryStartTest {

    private static void startsWhenReady(Method start) {
        assertThat(start.isAnnotationPresent(PostConstruct.class)).as("%s is a @PostConstruct", start).isFalse();
        EventListener listener = start.getAnnotation(EventListener.class);
        assertThat(listener).as("%s listens for an event", start).isNotNull();
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    @Test
    void mdnsStartsOnlyOnceTheApplicationIsReady() throws Exception {
        startsWhenReady(MdnsBrowser.class.getMethod("start"));
    }

    @Test
    void ssdpStartsOnlyOnceTheApplicationIsReady() throws Exception {
        startsWhenReady(SsdpDiscovery.class.getMethod("start"));
        Bean bean = SsdpConfiguration.class.getMethod("ssdpDiscovery",
                SsdpProperties.class).getAnnotation(Bean.class);
        assertThat(bean.initMethod()).as("the bean's init method").isEmpty();
    }
}
