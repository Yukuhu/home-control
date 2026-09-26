package dev.andre.homecontrol.sources.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WorkflowUrlPolicyTest {
    private final WorkflowUrlPolicy policy = new WorkflowUrlPolicy(false, InetAddress::getAllByName);

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/token", "ftp://host/a", "https://secret@host/a", "https://host/a#token", "//host/a", "http://host:0/a", "http://host:65536/a", "http://[fe80::1%25eth0]/"})
    void rejectsUnsafeUriSyntax(String url) {
        assertThatThrownBy(() -> policy.parse(url)).isInstanceOf(WorkflowException.class).hasMessageNotContaining("token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "127.0.0.1", "127.23.0.1", "169.254.1.1", "224.0.0.1", "::", "::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:169.254.1.1"})
    void rejectsForbiddenAddressesIncludingMappedIpv4(String address) {
        assertThatThrownBy(() -> policy.addresses(address)).isInstanceOf(UnknownHostException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.1.2.3", "172.16.0.1", "192.168.2.3", "fd00::1", "8.8.8.8", "2606:4700:4700::1111"})
    void permitsLanAndPublicAddresses(String address) throws Exception {
        assertThat(policy.addresses(address)).containsExactly(InetAddress.ofLiteral(address));
    }

    @Test void loopbackOverrideDoesNotAllowLinkLocal() throws Exception {
        var local = new WorkflowUrlPolicy(true, InetAddress::getAllByName);
        assertThat(local.addresses("::1")).hasSize(1);
        assertThatThrownBy(() -> local.addresses("169.254.1.1")).isInstanceOf(UnknownHostException.class);
    }

    @Test void validatesEveryAnswerAndResolvesOnlyOnce() {
        var calls = new AtomicInteger();
        var mixed = new WorkflowUrlPolicy(false, host -> {
            calls.incrementAndGet();
            return new InetAddress[]{InetAddress.ofLiteral("192.168.1.2"), InetAddress.ofLiteral("127.0.0.1")};
        });
        assertThatThrownBy(() -> mixed.addresses("fixture.invalid")).isInstanceOf(UnknownHostException.class);
        assertThat(calls).hasValue(1);
    }

    @Test void numericLiteralCannotBeDisguisedByResolver() {
        var dishonest = new WorkflowUrlPolicy(false, host -> new InetAddress[]{InetAddress.ofLiteral("192.168.1.2")});
        assertThatThrownBy(() -> dishonest.addresses("127.0.0.1")).isInstanceOf(UnknownHostException.class);
    }

    @Test void originNormalizesSchemeHostAndDefaultPort() {
        assertThat(policy.sameOrigin(URI.create("HTTP://Example.COM/a"), URI.create("http://example.com:80/b"))).isTrue();
        assertThat(policy.sameOrigin(URI.create("https://host/a"), URI.create("https://host:443/b"))).isTrue();
        assertThat(policy.sameOrigin(URI.create("https://host/a"), URI.create("http://host:443/a"))).isFalse();
        assertThat(policy.sameOrigin(URI.create("http://host/a"), URI.create("http://host:81/a"))).isFalse();
    }

    @Test void propertiesRejectUnboundedOrNonPositiveLimits() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        var preparedArg66_0 = Duration.ofSeconds(Long.MAX_VALUE);
        assertThatThrownBy(() -> properties(preparedArg66_0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        var preparedArg67_0 = Duration.ofSeconds(1);
        assertThatThrownBy(() -> properties(preparedArg67_0, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        var preparedArg68_0 = Duration.ofSeconds(1);
        assertThatThrownBy(() -> properties(preparedArg68_0, 1, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        var preparedArg69_0 = Duration.ofSeconds(1);
        assertThatThrownBy(() -> properties(preparedArg69_0, 1, Integer.MAX_VALUE, 1)).isInstanceOf(IllegalArgumentException.class);
        var preparedArg70_0 = Duration.ofSeconds(1);
        assertThatThrownBy(() -> properties(preparedArg70_0, 1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void bindsSafeDefaultsAndAllowsExplicitLocalOverrides() {
        var source = new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(
                java.util.Map.of("home-control.workflows.enabled", "true"));
        var binder = new org.springframework.boot.context.properties.bind.Binder(source);
        var defaults = binder.bind("home-control.workflows", WorkflowProperties.class).get();
        assertThat(defaults).isEqualTo(new WorkflowProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(15), 4, 2_097_152, 3));
        source.put("home-control.workflows.allow-loopback", "true");
        source.put("home-control.workflows.request-timeout", "250ms");
        var configured = binder.bind("home-control.workflows", WorkflowProperties.class).get();
        assertThat(configured.allowLoopback()).isTrue();
        assertThat(configured.requestTimeout()).isEqualTo(Duration.ofMillis(250));
    }

    @Test void rejectsEmptyResolverAnswers() {
        var empty = new WorkflowUrlPolicy(false, host -> new InetAddress[0]);
        assertThatThrownBy(() -> empty.addresses("fixture.invalid")).isInstanceOf(UnknownHostException.class);
    }

    private WorkflowProperties properties(Duration timeout, int concurrent, int bytes, int redirects) {
        return new WorkflowProperties(true, false, timeout, timeout, concurrent, bytes, redirects);
    }
}
