package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.adapters.net.InsecureTls;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestTlsTest {

    @Test
    void aDefaultClientRefusesTheCertificate() throws Exception {
        try (FakeHttpServer server = FakeHttpServer.start(TestTls.serverContext("untrusted.invalid"));
             HttpClient client = HttpClient.newHttpClient()) {
            server.respond("GET", "/", Response.json(200, "{}"));

            assertThat(server.url().getScheme()).isEqualTo("https");
            assertThatThrownBy(() -> client.send(HttpRequest.newBuilder(server.url("/")).build(),
                    HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void aTrustingClientIsAnsweredAndSeesTheCommonName() throws Exception {
        X509Certificate certificate = peerCertificate("unit.invalid");

        assertThat(certificate.getSubjectX500Principal().getName()).isEqualTo("CN=unit.invalid");
    }

    @Test
    void aCommonNameGetsOneCertificatePerRun() throws Exception {
        assertThat(peerCertificate("same.invalid")).isEqualTo(peerCertificate("same.invalid"));
        assertThat(peerCertificate("same.invalid")).isNotEqualTo(peerCertificate("other.invalid"));
    }

    private static X509Certificate peerCertificate(String commonName) throws Exception {
        try (FakeHttpServer server = FakeHttpServer.start(TestTls.serverContext(commonName));
             HttpClient client = InsecureTls.httpClient(Duration.ofSeconds(5))) {
            server.respond("GET", "/", Response.json(200, "{}"));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(server.url("/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return (X509Certificate) response.sslSession().orElseThrow().getPeerCertificates()[0];
        }
    }
}
