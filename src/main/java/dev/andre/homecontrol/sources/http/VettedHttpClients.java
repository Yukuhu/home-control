package dev.andre.homecontrol.sources.http;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * Apache HttpClient 5 clients that connect only to addresses an outbound policy has vetted. The policy's lookup is
 * the connection's lookup, so a host cannot pass the check with one address and be connected to at another (DNS
 * rebinding). Redirects, retries, cookies, authentication caching, compression and connection reuse are off:
 * callers follow redirects themselves and vet every hop.
 */
public final class VettedHttpClients {

    /** Looks a host up and returns only addresses the caller's policy allows, or throws. */
    @FunctionalInterface
    public interface AddressVetting {
        InetAddress[] addresses(String host) throws UnknownHostException;
    }

    private VettedHttpClients() {
    }

    public static CloseableHttpClient create(AddressVetting vetting, int maxConnections, Duration connectTimeout) {
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return vetting.addresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dns)
                .setMaxConnTotal(maxConnections)
                .setMaxConnPerRoute(maxConnections)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofMilliseconds(Math.max(1, connectTimeout.toMillis()))).build())
                .build();
        return HttpClients.custom().setConnectionManager(manager)
                .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                .disableAuthCaching().disableContentCompression()
                .setConnectionReuseStrategy((request, response, context) -> false)
                .build();
    }
}
