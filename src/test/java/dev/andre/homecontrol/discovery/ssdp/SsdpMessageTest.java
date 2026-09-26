package dev.andre.homecontrol.discovery.ssdp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;

class SsdpMessageTest {

    private static String fixture(String name, String host, int port) throws IOException {
        return Files.readString(Path.of("src/test/resources/fixtures/ssdp/" + name))
                .replace("{host}", host).replace("{port}", String.valueOf(port))
                .replace("\r\n", "\n").replace("\n", "\r\n");
    }

    @Test
    void parsesASearchResponseWithCaseInsensitiveHeaders() throws IOException {
        String raw = fixture("lg-search-response.txt", "192.168.1.60", 1780);
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);

        SsdpMessage message = SsdpMessage.parse(bytes, bytes.length).orElseThrow();

        assertThat(message.kind()).isEqualTo(SsdpMessage.Kind.SEARCH_RESPONSE);
        assertThat(message.header("location")).contains("http://192.168.1.60:1780/lg/description.xml");
        assertThat(message.header("LOCATION")).contains("http://192.168.1.60:1780/lg/description.xml");
        assertThat(message.type()).contains("urn:lge-com:service:webos-second-screen:1");
        assertThat(message.maxAge()).isEqualTo(Duration.ofSeconds(1800));
        assertThat(message.header("EXT")).isEmpty();
    }

    @Test
    void parsesNotifyAliveAndByeBye() {
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:a::urn:x:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: http://10.0.0.2/d.xml\r\n\r\n";
        SsdpMessage aliveMessage = SsdpMessage.parse(alive.getBytes(US_ASCII), alive.length()).orElseThrow();
        assertThat(aliveMessage.kind()).isEqualTo(SsdpMessage.Kind.NOTIFY);
        assertThat(aliveMessage.type()).contains("urn:x:1");
        assertThat(aliveMessage.isByeBye()).isFalse();
        assertThat(aliveMessage.maxAge()).isEqualTo(Duration.ofSeconds(120));

        String byebye = alive.replace("NTS: ssdp:alive", "NTS: ssdp:byebye");
        SsdpMessage byebyeMessage = SsdpMessage.parse(byebye.getBytes(US_ASCII), byebye.length()).orElseThrow();
        assertThat(byebyeMessage.isByeBye()).isTrue();
    }

    @Test
    void acceptsBareLineFeeds() {
        String alive = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:a::urn:x:1\r\nCACHE-CONTROL: max-age = 120\r\nLOCATION: http://10.0.0.2/d.xml\r\n\r\n";
        String lfOnly = alive.replace("\r\n", "\n");

        SsdpMessage viaCrLf = SsdpMessage.parse(alive.getBytes(US_ASCII), alive.length()).orElseThrow();
        SsdpMessage viaLf = SsdpMessage.parse(lfOnly.getBytes(US_ASCII), lfOnly.length()).orElseThrow();

        assertThat(viaLf.kind()).isEqualTo(viaCrLf.kind());
        assertThat(viaLf.type()).isEqualTo(viaCrLf.type());
        assertThat(viaLf.isByeBye()).isEqualTo(viaCrLf.isByeBye());
        assertThat(viaLf.maxAge()).isEqualTo(viaCrLf.maxAge());
    }

    @Test
    void aMissingMaxAgeDefaultsToThirtyMinutes() {
        String noMaxAge = "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:x:1\r\nNTS: ssdp:alive\r\n"
                + "USN: uuid:a::urn:x:1\r\nLOCATION: http://10.0.0.2/d.xml\r\n\r\n";

        SsdpMessage message = SsdpMessage.parse(noMaxAge.getBytes(US_ASCII), noMaxAge.length()).orElseThrow();

        assertThat(message.maxAge()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void rejectsDatagramsThatAreNotSsdp() {
        String getRequest = "GET / HTTP/1.1\r\n\r\n";
        assertThat(SsdpMessage.parse(getRequest.getBytes(US_ASCII), getRequest.length())).isEmpty();

        assertThat(SsdpMessage.parse("".getBytes(US_ASCII), 0)).isEmpty();

        byte[] random = new byte[64];
        new Random(42).nextBytes(random);
        assertThat(SsdpMessage.parse(random, random.length)).isEmpty();
    }

    @Test
    void buildsAnMSearchRequest() {
        byte[] request = SsdpMessage.search("urn:x:1", "239.255.255.250:1900", 2, "Linux/1 UPnP/1.1 HomeControl/1");

        assertThat(new String(request, US_ASCII)).isEqualTo("M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: urn:x:1\r\n"
                + "USER-AGENT: Linux/1 UPnP/1.1 HomeControl/1\r\n"
                + "\r\n");

        SsdpMessage parsed = SsdpMessage.parse(request, request.length).orElseThrow();
        assertThat(parsed.kind()).isEqualTo(SsdpMessage.Kind.SEARCH_REQUEST);
        assertThat(parsed.header("ST")).contains("urn:x:1");
    }
}
