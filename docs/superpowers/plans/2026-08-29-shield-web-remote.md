# Shield Web Remote Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot web app on the home LAN that discovers an NVIDIA Shield via mDNS, pairs with it through the 6-digit code shown on the TV, and then drives it as a browser remote with an app launcher and live device state.

**Architecture:** A `protocol` package owns everything that touches TLS and protobuf (pairing on port 6467, commands on port 6466) and exposes only plain Java types. `DeviceSession` wraps one connection with reconnect/backoff and holds the current `DeviceState`. A thin Spring MVC layer renders Thymeleaf pages, posts key presses via htmx, and pushes state to the browser over SSE. Everything is tested against in-process fake devices (`FakePairingServer`, `FakeRemoteServer`); no hardware is needed until the final manual gate.

**Tech Stack:** Java 25 · Gradle 9.3 (Kotlin DSL) · Spring Boot 4.1.1 · protobuf-java 4.36.0 · JmDNS 3.6.3 · BouncyCastle 1.85 · Thymeleaf + htmx · Docker

**Spec:** `docs/superpowers/specs/2026-08-29-shield-remote-design.md` — read it before starting. The protocol details in spec §5 were verified against the `louis49/androidtv-remote` reference implementation; do not "improve" them from memory.

## Global Constraints

- Java toolchain **25**; Gradle wrapper **9.3**; Spring Boot **4.1.1**.
- Root package **`dev.andre.shield`**. Protocol-generated protobuf packages are `dev.andre.shield.protocol.pairing` and `dev.andre.shield.protocol.remote`.
- **No protobuf-generated type may appear in a signature outside `dev.andre.shield.protocol`.** This boundary is what makes the sidecar fallback cheap; a reviewer should reject any task that breaks it.
- Runtime state lives under a configurable data directory, `/data` in Docker: `keystore.p12`, `devices.json`, `apps.yaml`.
- **No authentication.** LAN-only by explicit decision. Do not add Spring Security.
- Every task is TDD: failing test first, minimal implementation, green, commit.
- Dependency versions are pinned exactly as written. Do not bump them mid-plan.
- Use `@MockitoBean`, not `@MockBean` (removed in Spring Boot 4).

---

### Task 1: Project skeleton that boots

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `src/main/java/dev/andre/shield/ShieldApplication.java`
- Create: `src/main/resources/application.yaml`
- Test: `src/test/java/dev/andre/shield/ShieldApplicationTest.java`
- Generated: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: a buildable Gradle project; `./gradlew test` is the command every later task runs.

- [ ] **Step 1: Generate the Gradle wrapper**

```bash
cd /var/home/andre/shield
gradle wrapper --gradle-version 9.3
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "shield-remote"
```

- [ ] **Step 3: Write `build.gradle.kts`**

Versions are pinned. The Spring Boot BOM is imported as a `platform()` so no separate dependency-management plugin is needed.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("com.google.protobuf") version "0.10.0"
}

group = "dev.andre"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("com.google.protobuf:protobuf-java:4.36.0")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.0" }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging { showExceptions = true }
}
```

- [ ] **Step 4: Write the failing test**

`src/test/java/dev/andre/shield/ShieldApplicationTest.java`:

```java
package dev.andre.shield;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ShieldApplicationTest {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5: Run it and watch it fail**

Run: `./gradlew test`
Expected: FAIL — no `@SpringBootConfiguration` found, because `ShieldApplication` does not exist yet.

- [ ] **Step 6: Write the application class and config**

`src/main/java/dev/andre/shield/ShieldApplication.java`:

```java
package dev.andre.shield;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShieldApplication.class, args);
    }
}
```

`src/main/resources/application.yaml`:

```yaml
server:
  port: 8080

shield:
  data-dir: ./data
  keystore-password: ${SHIELD_KEYSTORE_PASSWORD:shield}
  discovery-enabled: true
  # The device pings roughly every 5s; 10s without any inbound message means dead.
  stale-timeout-seconds: 10
  reconnect-initial-delay-seconds: 1
  reconnect-max-delay-seconds: 60
```

- [ ] **Step 7: Run it and watch it pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Spring Boot project skeleton"
```

---

### Task 2: Protobuf schemas and generated classes

The two schemas come from the verified reference implementation. `pairingmessage.proto` is small enough to write inline; `remotemessage.proto` is 12KB (305 keycodes) and is downloaded from a pinned URL, then checked.

**Critical gotcha:** both reference schemas declare `UNRECOGNIZED = -1;` inside their enums. That is a protobuf**js** convention. `protoc` generates its own `UNRECOGNIZED` constant for proto3 enums in Java, so leaving those lines in makes Java code generation fail. They carry no wire meaning and must be stripped.

**Files:**
- Create: `src/main/proto/pairingmessage.proto`
- Create: `src/main/proto/remotemessage.proto`
- Test: `src/test/java/dev/andre/shield/protocol/ProtoSchemaTest.java`

**Interfaces:**
- Consumes: the Gradle build from Task 1.
- Produces: generated Java classes `dev.andre.shield.protocol.pairing.*` (`PairingMessage`, `PairingRequest`, `PairingOption`, `PairingEncoding`, `PairingConfiguration`, `PairingSecret`, `RoleType`) and `dev.andre.shield.protocol.remote.*` (`RemoteMessage`, `RemoteKeyInject`, `RemoteKeyCode`, `RemoteDirection`, `RemoteConfigure`, `RemoteDeviceInfo`, `RemoteSetActive`, `RemotePingRequest`, `RemotePingResponse`, `RemoteStart`, `RemoteSetVolumeLevel`, `RemoteImeKeyInject`, `RemoteAppLinkLaunchRequest`, `RemoteError`).

- [ ] **Step 1: Write `src/main/proto/pairingmessage.proto`**

Verbatim from the reference, with `UNRECOGNIZED` removed and Java options added:

```proto
syntax = "proto3";
package pairing;

option java_package = "dev.andre.shield.protocol.pairing";
option java_outer_classname = "PairingProto";
option java_multiple_files = true;

enum RoleType {
  ROLE_TYPE_UNKNOWN = 0;
  ROLE_TYPE_INPUT = 1;
  ROLE_TYPE_OUTPUT = 2;
}

message PairingRequest {
  string service_name = 1;
  string client_name = 2;
}

message PairingRequestAck {
  string server_name = 1;
}

message PairingEncoding {
  enum EncodingType {
    ENCODING_TYPE_UNKNOWN = 0;
    ENCODING_TYPE_ALPHANUMERIC = 1;
    ENCODING_TYPE_NUMERIC = 2;
    ENCODING_TYPE_HEXADECIMAL = 3;
    ENCODING_TYPE_QRCODE = 4;
  }
  EncodingType type = 1;
  uint32 symbol_length = 2;
}

message PairingOption {
  repeated PairingEncoding input_encodings = 1;
  repeated PairingEncoding output_encodings = 2;
  RoleType preferred_role = 3;
}

message PairingConfiguration {
  PairingEncoding encoding = 1;
  RoleType client_role = 2;
}

message PairingConfigurationAck {
}

message PairingSecret {
  bytes secret = 1;
}

message PairingSecretAck {
  bytes secret = 1;
}

message PairingMessage {
  enum Status {
    UNKNOWN = 0;
    STATUS_OK = 200;
    STATUS_ERROR = 400;
    STATUS_BAD_CONFIGURATION = 401;
    STATUS_BAD_SECRET = 402;
  }
  int32 protocol_version = 1;
  Status status = 2;
  int32 request_case = 3;
  PairingRequest pairing_request = 10;
  PairingRequestAck pairing_request_ack = 11;
  PairingOption pairing_option = 20;
  PairingConfiguration pairing_configuration = 30;
  PairingConfigurationAck pairing_configuration_ack = 31;
  PairingSecret pairing_secret = 40;
  PairingSecretAck pairing_secret_ack = 41;
}
```

- [ ] **Step 2: Download and fix up `remotemessage.proto`**

```bash
mkdir -p src/main/proto
curl -sSfL -o src/main/proto/remotemessage.proto \
  https://raw.githubusercontent.com/louis49/androidtv-remote/main/src/remote/remotemessage.proto

# Strip the protobufjs-only UNRECOGNIZED constants (they break Java codegen)
sed -i '/UNRECOGNIZED = -1;/d' src/main/proto/remotemessage.proto

# Add the Java options directly after the package declaration
sed -i 's|^package remote;|package remote;\n\noption java_package = "dev.andre.shield.protocol.remote";\noption java_outer_classname = "RemoteProto";\noption java_multiple_files = true;|' \
  src/main/proto/remotemessage.proto
```

- [ ] **Step 3: Verify the download is the expected file**

These assertions pin the exact field numbers this project depends on. If any of them fails, the upstream file changed — stop and reconcile against spec §5 rather than adapting the code.

```bash
grep -q 'KEYCODE_DPAD_UP         = 19;'                     src/main/proto/remotemessage.proto
grep -q 'KEYCODE_DPAD_CENTER     = 23;'                     src/main/proto/remotemessage.proto
grep -q 'KEYCODE_POWER           = 26;'                     src/main/proto/remotemessage.proto
grep -q 'KEYCODE_MEDIA_PLAY_PAUSE= 85;'                     src/main/proto/remotemessage.proto
grep -q 'SHORT = 3;'                                        src/main/proto/remotemessage.proto
grep -q 'RemoteAppLinkLaunchRequest remote_app_link_launch_request = 90;' src/main/proto/remotemessage.proto
! grep -q 'UNRECOGNIZED' src/main/proto/remotemessage.proto && echo "schema OK"
```

Expected: `schema OK`, no grep failures.

- [ ] **Step 4: Write the failing test**

`src/test/java/dev/andre/shield/protocol/ProtoSchemaTest.java`:

```java
package dev.andre.shield.protocol;

import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingRequest;
import dev.andre.shield.protocol.remote.RemoteDirection;
import dev.andre.shield.protocol.remote.RemoteKeyCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ProtoSchemaTest {

    @Test
    void pairingMessageRoundTripsThroughDelimitedEncoding() throws Exception {
        PairingMessage sent = PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(PairingRequest.newBuilder()
                        .setServiceName("shield-remote")
                        .setClientName("shield-remote"))
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sent.writeDelimitedTo(out);
        byte[] wire = out.toByteArray();

        // Pairing messages are always < 128 bytes, so the varint prefix is one byte
        // holding the payload length -- byte-identical to the device's framing.
        assertThat(wire[0]).isEqualTo((byte) (wire.length - 1));

        PairingMessage parsed = PairingMessage.parseDelimitedFrom(new ByteArrayInputStream(wire));
        assertThat(parsed.getPairingRequest().getServiceName()).isEqualTo("shield-remote");
        assertThat(parsed.getStatus()).isEqualTo(PairingMessage.Status.STATUS_OK);
    }

    @Test
    void remoteEnumsHaveTheVerifiedWireValues() {
        assertThat(RemoteKeyCode.KEYCODE_DPAD_UP.getNumber()).isEqualTo(19);
        assertThat(RemoteKeyCode.KEYCODE_DPAD_CENTER.getNumber()).isEqualTo(23);
        assertThat(RemoteKeyCode.KEYCODE_POWER.getNumber()).isEqualTo(26);
        assertThat(RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE.getNumber()).isEqualTo(85);
        assertThat(RemoteKeyCode.KEYCODE_VOLUME_UP.getNumber()).isEqualTo(24);
        assertThat(RemoteDirection.SHORT.getNumber()).isEqualTo(3);
    }
}
```

- [ ] **Step 5: Run it**

Run: `./gradlew test --tests '*ProtoSchemaTest'`
Expected: PASS once codegen works. If it fails with a duplicate `UNRECOGNIZED` symbol, Step 2's `sed` did not run.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add verified Android TV Remote v2 protobuf schemas"
```

---

### Task 3: Length-delimited message framing

**Files:**
- Create: `src/main/java/dev/andre/shield/protocol/MessageStream.java`
- Test: `src/test/java/dev/andre/shield/protocol/MessageStreamTest.java`

**Interfaces:**
- Consumes: generated protobuf classes from Task 2.
- Produces:
  - `MessageStream(InputStream in, OutputStream out)`
  - `void write(com.google.protobuf.MessageLite message) throws IOException` — writes delimited and flushes
  - `<T extends MessageLite> T read(com.google.protobuf.Parser<T> parser) throws IOException` — returns `null` at clean end of stream

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/protocol/MessageStreamTest.java`:

```java
package dev.andre.shield.protocol;

import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MessageStreamTest {

    private static PairingMessage request(String name) {
        return PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK)
                .setPairingRequest(PairingRequest.newBuilder().setServiceName(name))
                .build();
    }

    @Test
    void writesAndReadsBackASingleMessage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MessageStream(InputStream.nullInputStream(), out).write(request("first"));

        MessageStream reader = new MessageStream(new ByteArrayInputStream(out.toByteArray()),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("first");
    }

    @Test
    void readsTwoMessagesDeliveredInOneChunk() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageStream writer = new MessageStream(InputStream.nullInputStream(), out);
        writer.write(request("first"));
        writer.write(request("second"));

        MessageStream reader = new MessageStream(new ByteArrayInputStream(out.toByteArray()),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("first");
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("second");
    }

    @Test
    void reassemblesAMessageSplitAcrossReads() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MessageStream(InputStream.nullInputStream(), out).write(request("split-across-tcp-segments"));
        byte[] wire = out.toByteArray();

        // A stream that hands over one byte at a time, as a slow TCP connection would.
        InputStream dribble = new ByteArrayInputStream(wire) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, 1);
            }
        };

        MessageStream reader = new MessageStream(dribble, OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser()).getPairingRequest().getServiceName())
                .isEqualTo("split-across-tcp-segments");
    }

    @Test
    void returnsNullAtEndOfStream() throws Exception {
        MessageStream reader = new MessageStream(InputStream.nullInputStream(),
                OutputStream.nullOutputStream());
        assertThat(reader.read(PairingMessage.parser())).isNull();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*MessageStreamTest'`
Expected: FAIL — `MessageStream` does not exist.

- [ ] **Step 3: Implement `MessageStream`**

`src/main/java/dev/andre/shield/protocol/MessageStream.java`:

```java
package dev.andre.shield.protocol;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-delimited protobuf framing, as spoken by the Android TV Remote v2 protocol.
 *
 * <p>The device prefixes every message with a single length byte. Because both pairing
 * and remote messages are always shorter than 128 bytes, that is byte-identical to
 * protobuf's varint delimiting, so the standard delimited APIs are wire-compatible.
 */
public final class MessageStream {

    private final InputStream in;
    private final OutputStream out;

    public MessageStream(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public synchronized void write(MessageLite message) throws IOException {
        message.writeDelimitedTo(out);
        out.flush();
    }

    /** Returns the next message, or {@code null} if the peer closed the stream cleanly. */
    public <T extends MessageLite> T read(Parser<T> parser) throws IOException {
        return parser.parseDelimitedFrom(in);
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*MessageStreamTest'`
Expected: PASS — all four tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: length-delimited protobuf framing"
```

---

### Task 4: Client certificate generation and storage

**Files:**
- Create: `src/main/java/dev/andre/shield/protocol/ClientCertificate.java`
- Create: `src/main/java/dev/andre/shield/protocol/CertificateStore.java`
- Test: `src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java`

**Interfaces:**
- Consumes: BouncyCastle from Task 1.
- Produces:
  - `record ClientCertificate(KeyPair keyPair, X509Certificate certificate)`
  - `static ClientCertificate ClientCertificate.generate(String commonName)` — 2048-bit RSA, self-signed, valid 20 years
  - `CertificateStore(Path keystoreFile, char[] password)`
  - `ClientCertificate CertificateStore.loadOrCreate(String alias)` — idempotent
  - `Optional<ClientCertificate> CertificateStore.load(String alias)`
  - `static String ClientCertificate.fingerprintOf(X509Certificate)` — uppercase hex SHA-256, used to pin the device certificate recorded at pairing time

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/protocol/CertificateStoreTest.java`:

```java
package dev.andre.shield.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;

class CertificateStoreTest {

    @TempDir
    Path dir;

    @Test
    void generatesA2048BitSelfSignedCertificate() {
        ClientCertificate cert = ClientCertificate.generate("shield-remote");

        assertThat(((RSAPublicKey) cert.certificate().getPublicKey()).getModulus().bitLength())
                .isEqualTo(2048);
        assertThat(cert.certificate().getSubjectX500Principal())
                .isEqualTo(cert.certificate().getIssuerX500Principal());
    }

    @Test
    void persistsAndReloadsTheSameKeyPair() throws Exception {
        Path file = dir.resolve("keystore.p12");
        CertificateStore store = new CertificateStore(file, "secret".toCharArray());

        ClientCertificate created = store.loadOrCreate("shield");
        assertThat(Files.exists(file)).isTrue();

        CertificateStore reopened = new CertificateStore(file, "secret".toCharArray());
        ClientCertificate reloaded = reopened.loadOrCreate("shield");

        assertThat(reloaded.certificate()).isEqualTo(created.certificate());
        assertThat(reloaded.keyPair().getPrivate()).isEqualTo(created.keyPair().getPrivate());
    }

    @Test
    void fingerprintsACertificateStably() {
        ClientCertificate cert = ClientCertificate.generate("shield-remote");
        ClientCertificate other = ClientCertificate.generate("shield-remote");

        assertThat(ClientCertificate.fingerprintOf(cert.certificate()))
                .hasSize(64)
                .isEqualTo(ClientCertificate.fingerprintOf(cert.certificate()))
                .isNotEqualTo(ClientCertificate.fingerprintOf(other.certificate()));
    }

    @Test
    void loadReturnsEmptyForAnUnknownAlias() {
        CertificateStore store = new CertificateStore(dir.resolve("keystore.p12"), "secret".toCharArray());
        assertThat(store.load("never-paired")).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*CertificateStoreTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Implement `ClientCertificate`**

`src/main/java/dev/andre/shield/protocol/ClientCertificate.java`:

```java
package dev.andre.shield.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/** A self-signed RSA identity. Once paired, this certificate IS the credential. */
public record ClientCertificate(KeyPair keyPair, X509Certificate certificate) {

    private static final Duration VALIDITY = Duration.ofDays(365 * 20);

    public static ClientCertificate generate(String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=" + commonName);
            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                    .build(keyPair.getPrivate());

            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    new JcaX509v3CertificateBuilder(
                            subject,
                            new BigInteger(64, new SecureRandom()),
                            Date.from(now),
                            Date.from(now.plus(VALIDITY)),
                            subject,
                            keyPair.getPublic()
                    ).build(signer));

            return new ClientCertificate(keyPair, certificate);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate client certificate", e);
        }
    }

    /** SHA-256 of the encoded certificate as uppercase hex; how a device is pinned. */
    public static String fingerprintOf(X509Certificate certificate) {
        try {
            return HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint the certificate", e);
        }
    }
}
```

- [ ] **Step 4: Implement `CertificateStore`**

`src/main/java/dev/andre/shield/protocol/CertificateStore.java`:

```java
package dev.andre.shield.protocol;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Optional;

/** PKCS12-backed storage for pairing credentials, one entry per device alias. */
public class CertificateStore {

    private final Path file;
    private final char[] password;

    public CertificateStore(Path file, char[] password) {
        this.file = file;
        this.password = password;
    }

    public synchronized Optional<ClientCertificate> load(String alias) {
        try {
            KeyStore keyStore = openOrEmpty();
            if (!keyStore.containsAlias(alias)) {
                return Optional.empty();
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, password);
            Certificate certificate = keyStore.getCertificate(alias);
            KeyPair keyPair = new KeyPair(certificate.getPublicKey(), privateKey);
            return Optional.of(new ClientCertificate(keyPair, (X509Certificate) certificate));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read keystore " + file, e);
        }
    }

    public synchronized ClientCertificate loadOrCreate(String alias) {
        return load(alias).orElseGet(() -> {
            ClientCertificate created = ClientCertificate.generate("shield-remote");
            save(alias, created);
            return created;
        });
    }

    public synchronized void save(String alias, ClientCertificate credential) {
        try {
            KeyStore keyStore = openOrEmpty();
            keyStore.setKeyEntry(alias, credential.keyPair().getPrivate(), password,
                    new Certificate[]{credential.certificate()});
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                keyStore.store(out, password);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not write keystore " + file, e);
        }
    }

    private KeyStore openOrEmpty() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                keyStore.load(in, password);
            }
        } else {
            keyStore.load(null, password);
        }
        return keyStore;
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew test --tests '*CertificateStoreTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: self-signed client certificate generation and PKCS12 storage"
```

---

### Task 5: The pairing digest

This is the highest-risk computation in the project and the reason pairing fails despite a correctly typed code. The two vectors below were computed with `sha256sum` over the exact byte sequence the protocol specifies; they pin the field order, the sign-byte stripping, and the nonce slice.

> **Naming:** this class is `PairingDigest`, not `PairingSecret` — the generated
> protobuf message is already called `PairingSecret`, and both are imported together
> in Task 6.

**Files:**
- Create: `src/main/java/dev/andre/shield/protocol/PairingDigest.java`
- Test: `src/test/java/dev/andre/shield/protocol/PairingDigestTest.java`

**Interfaces:**
- Consumes: nothing beyond the JDK.
- Produces:
  - `static byte[] PairingDigest.unsignedBytes(BigInteger value)` — big-endian, sign byte stripped
  - `static byte[] PairingDigest.digest(byte[] clientModulus, byte[] clientExponent, byte[] serverModulus, byte[] serverExponent, String code)`
  - `static boolean PairingDigest.matchesCheckByte(byte[] digest, String code)`
  - `static byte[] PairingDigest.compute(RSAPublicKey clientKey, RSAPublicKey serverKey, String code)` — throws `WrongCodeException` when the check byte disagrees
  - `class WrongCodeException extends RuntimeException`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/protocol/PairingDigestTest.java`:

```java
package dev.andre.shield.protocol;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PairingDigestTest {

    private static final byte[] CLIENT_MODULUS = HexFormat.of().parseHex("A1A2A3");
    private static final byte[] SERVER_MODULUS = HexFormat.of().parseHex("B1B2B3");
    private static final byte[] EXPONENT = HexFormat.of().parseHex("010001");

    @Test
    void digestMatchesTheReferenceVector() {
        // SHA-256 over A1A2A3 | 010001 | B1B2B3 | 010001 | B2C3
        byte[] digest = PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3");

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("70d96b97cd3727547f93cdf73cf7d701291cf97af7f78632db7e0f96f301d4df");
    }

    @Test
    void digestMatchesASecondReferenceVector() {
        // SHA-256 over A1A2A3 | 010001 | B1B2B3 | 010001 | FFEE
        byte[] digest = PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "1EFFEE");

        assertThat(HexFormat.of().formatHex(digest))
                .isEqualTo("1ece4613ef7d5015baa717c8001a4c21cf7f3c08a250204d317dfe2e0a69c357");
    }

    @Test
    void onlyTheLastFourHexCharactersOfTheCodeEnterTheDigest() {
        // The first two characters are a check byte, not input.
        assertThat(PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3"))
                .isEqualTo(PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "FFB2C3"));
    }

    @Test
    void acceptsACodeWhoseCheckByteMatches() {
        byte[] digest = PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "70B2C3");
        assertThat(PairingDigest.matchesCheckByte(digest, "70B2C3")).isTrue();
    }

    @Test
    void rejectsACodeWhoseCheckByteDisagrees() {
        byte[] digest = PairingDigest.digest(CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "AAB2C3");
        assertThat(PairingDigest.matchesCheckByte(digest, "AAB2C3")).isFalse();
    }

    @Test
    void stripsTheSignByteFromAModulusWithAHighBitSet() {
        // A 2048-bit modulus with the top bit set: toByteArray() would return 257 bytes.
        BigInteger modulus = BigInteger.ONE.shiftLeft(2047).add(BigInteger.ONE);

        assertThat(modulus.toByteArray()).hasSize(257);
        assertThat(PairingDigest.unsignedBytes(modulus)).hasSize(256);
        assertThat(PairingDigest.unsignedBytes(modulus)[0]).isEqualTo((byte) 0x80);
    }

    @Test
    void leavesTheStandardExponentUntouched() {
        assertThat(PairingDigest.unsignedBytes(BigInteger.valueOf(65537)))
                .containsExactly((byte) 0x01, (byte) 0x00, (byte) 0x01);
    }

    @Test
    void rejectsAMalformedCode() {
        assertThatThrownBy(() -> PairingDigest.digest(
                CLIENT_MODULUS, EXPONENT, SERVER_MODULUS, EXPONENT, "12345"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*PairingDigestTest'`
Expected: FAIL — `PairingDigest` does not exist.

- [ ] **Step 3: Implement `PairingDigest`**

`src/main/java/dev/andre/shield/protocol/PairingDigest.java`:

```java
package dev.andre.shield.protocol;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * The pairing proof: SHA-256 over both public keys and the nonce embedded in the
 * code the TV displays.
 *
 * <p>The code is six hexadecimal characters. The first two are a check byte equal to
 * the first byte of the digest; only the last four are hashed. Verified against the
 * {@code louis49/androidtv-remote} reference implementation.
 */
public final class PairingDigest {

    private PairingDigest() {
    }

    public static byte[] compute(RSAPublicKey clientKey, RSAPublicKey serverKey, String code) {
        byte[] digest = digest(
                unsignedBytes(clientKey.getModulus()),
                unsignedBytes(clientKey.getPublicExponent()),
                unsignedBytes(serverKey.getModulus()),
                unsignedBytes(serverKey.getPublicExponent()),
                code);

        if (!matchesCheckByte(digest, code)) {
            throw new WrongCodeException("Code " + code + " does not match the device's certificate");
        }
        return digest;
    }

    public static byte[] digest(byte[] clientModulus, byte[] clientExponent,
                                byte[] serverModulus, byte[] serverExponent, String code) {
        byte[] nonce = nonce(code);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(clientModulus);
            sha256.update(clientExponent);
            sha256.update(serverModulus);
            sha256.update(serverExponent);
            sha256.update(nonce);
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean matchesCheckByte(byte[] digest, String code) {
        return digest[0] == checkByte(code);
    }

    /** Big-endian magnitude bytes, without the sign byte {@link BigInteger} prepends. */
    public static byte[] unsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static byte[] nonce(String code) {
        return HexFormat.of().parseHex(normalise(code).substring(2));
    }

    private static byte checkByte(String code) {
        return HexFormat.of().parseHex(normalise(code).substring(0, 2))[0];
    }

    private static String normalise(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.length() != 6) {
            throw new IllegalArgumentException("Pairing code must be 6 hexadecimal characters");
        }
        return trimmed.toUpperCase();
    }

    public static class WrongCodeException extends RuntimeException {
        public WrongCodeException(String message) {
            super(message);
        }
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*PairingDigestTest'`
Expected: PASS — all eight tests. If the vector tests fail, the byte order or the stripping is wrong; do not adjust the expected hex, fix the code.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: pairing digest with pinned reference vectors"
```

---

### Task 6: TLS transport, fake pairing device, and the pairing handshake

**Files:**
- Create: `src/main/java/dev/andre/shield/protocol/TlsSockets.java`
- Create: `src/main/java/dev/andre/shield/protocol/PairingResult.java`
- Create: `src/main/java/dev/andre/shield/protocol/PairingProtocolException.java`
- Create: `src/main/java/dev/andre/shield/protocol/PairingSession.java`
- Test: `src/test/java/dev/andre/shield/protocol/FakePairingServer.java`
- Test: `src/test/java/dev/andre/shield/protocol/PairingSessionTest.java`

The fake server lives in the same package as the production classes so it can reuse `TlsSockets`' package-private key-manager helper without widening the public API.

**Interfaces:**
- Consumes: `ClientCertificate`, `MessageStream`, `PairingDigest`, generated pairing classes.
- Produces:
  - `static SSLSocket TlsSockets.connect(String host, int port, ClientCertificate credential, int soTimeoutMillis)`
  - `sealed interface PairingResult` with `record Paired(X509Certificate serverCertificate)`, `record WrongCode()`, `record Failed(String reason)`
  - `PairingSession(String host, int port, ClientCertificate credential)`, `void start()`, `PairingResult submitCode(String code)`, `X509Certificate serverCertificate()`, `void close()`

- [ ] **Step 1: Write `TlsSockets`**

Written before the test because both the fake device and the session need it.

```java
package dev.andre.shield.protocol;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * TLS plumbing for both device ports.
 *
 * <p>The device presents a self-signed certificate, so there is no CA to validate
 * against: identity is established by the pairing exchange and, afterwards, by
 * pinning the certificate recorded at pairing time.
 */
public final class TlsSockets {

    private static final char[] KEYSTORE_PASSWORD = "shield".toCharArray();
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private TlsSockets() {
    }

    public static SSLSocket connect(String host, int port, ClientCertificate credential,
                                    int soTimeoutMillis) throws IOException {
        try {
            SSLContext context = context(credential);
            SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(soTimeoutMillis);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            return socket;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not open a TLS connection to " + host + ":" + port, e);
        }
    }

    static SSLContext context(ClientCertificate credential) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers(credential), new TrustManager[]{ACCEPT_ANY}, new SecureRandom());
        return context;
    }

    static KeyManager[] keyManagers(ClientCertificate credential) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, KEYSTORE_PASSWORD);
        keyStore.setKeyEntry("client", credential.keyPair().getPrivate(), KEYSTORE_PASSWORD,
                new Certificate[]{credential.certificate()});

        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, KEYSTORE_PASSWORD);
        return factory.getKeyManagers();
    }

    static final X509TrustManager ACCEPT_ANY = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
```

- [ ] **Step 2: Write the fake pairing device**

`src/test/java/dev/andre/shield/protocol/FakePairingServer.java`:

```java
package dev.andre.shield.protocol;

import com.google.protobuf.ByteString;
import dev.andre.shield.protocol.pairing.PairingConfigurationAck;
import dev.andre.shield.protocol.pairing.PairingEncoding;
import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingOption;
import dev.andre.shield.protocol.pairing.PairingRequestAck;
import dev.andre.shield.protocol.pairing.PairingSecretAck;
import dev.andre.shield.protocol.pairing.RoleType;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** An in-process stand-in for the Shield's pairing port. */
public class FakePairingServer implements AutoCloseable {

    /** Fixed so the displayed code is reproducible across runs. */
    private static final String NONCE = "B2C3";

    private final ClientCertificate identity = ClientCertificate.generate("fake-shield");
    private final SSLServerSocket serverSocket;
    private final CountDownLatch codeDisplayed = new CountDownLatch(1);

    private volatile String displayedCode;
    private volatile byte[] expectedSecret;
    private volatile byte[] receivedSecret;

    public FakePairingServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(identity),
                new TrustManager[]{TlsSockets.ACCEPT_ANY}, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        serverSocket.setWantClientAuth(true);
        Thread.ofVirtual().name("fake-pairing-server").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public X509Certificate certificate() {
        return identity.certificate();
    }

    /** The six character code this device is "showing on screen". */
    public String awaitDisplayedCode() throws InterruptedException {
        if (!codeDisplayed.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("pairing never reached the code display step");
        }
        return displayedCode;
    }

    public byte[] receivedSecret() {
        return receivedSecret;
    }

    private void serve() {
        try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
            MessageStream stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
            X509Certificate clientCertificate =
                    (X509Certificate) socket.getSession().getPeerCertificates()[0];

            PairingMessage message;
            while ((message = stream.read(PairingMessage.parser())) != null) {
                if (message.hasPairingRequest()) {
                    stream.write(ok().setPairingRequestAck(
                            PairingRequestAck.newBuilder().setServerName("fake-shield")).build());
                } else if (message.hasPairingOption()) {
                    stream.write(ok().setPairingOption(PairingOption.newBuilder()
                            .addInputEncodings(hexadecimalSixDigits())
                            .setPreferredRole(RoleType.ROLE_TYPE_INPUT)).build());
                } else if (message.hasPairingConfiguration()) {
                    displayCode(clientCertificate);
                    stream.write(ok().setPairingConfigurationAck(
                            PairingConfigurationAck.getDefaultInstance()).build());
                } else if (message.hasPairingSecret()) {
                    receivedSecret = message.getPairingSecret().getSecret().toByteArray();
                    if (Arrays.equals(receivedSecret, expectedSecret)) {
                        stream.write(ok().setPairingSecretAck(PairingSecretAck.newBuilder()
                                .setSecret(ByteString.copyFrom(expectedSecret))).build());
                    } else {
                        stream.write(PairingMessage.newBuilder()
                                .setProtocolVersion(2)
                                .setStatus(PairingMessage.Status.STATUS_BAD_SECRET)
                                .build());
                    }
                    return;
                }
            }
        } catch (Exception e) {
            // The connection ended; tests assert on observed state, not on this thread.
        }
    }

    private void displayCode(X509Certificate clientCertificate) {
        RSAPublicKey clientKey = (RSAPublicKey) clientCertificate.getPublicKey();
        RSAPublicKey serverKey = (RSAPublicKey) identity.certificate().getPublicKey();

        expectedSecret = PairingDigest.digest(
                PairingDigest.unsignedBytes(clientKey.getModulus()),
                PairingDigest.unsignedBytes(clientKey.getPublicExponent()),
                PairingDigest.unsignedBytes(serverKey.getModulus()),
                PairingDigest.unsignedBytes(serverKey.getPublicExponent()),
                "00" + NONCE);

        displayedCode = HexFormat.of().withUpperCase()
                .formatHex(new byte[]{expectedSecret[0]}) + NONCE;
        codeDisplayed.countDown();
    }

    private static PairingEncoding hexadecimalSixDigits() {
        return PairingEncoding.newBuilder()
                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build();
    }

    private static PairingMessage.Builder ok() {
        return PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK);
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
    }
}
```

- [ ] **Step 3: Write the failing test**

`src/test/java/dev/andre/shield/protocol/PairingSessionTest.java`:

```java
package dev.andre.shield.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PairingSessionTest {

    private FakePairingServer device;
    private PairingSession session;
    private final ClientCertificate credential = ClientCertificate.generate("shield-remote");

    @BeforeEach
    void startDevice() throws Exception {
        device = new FakePairingServer();
        session = new PairingSession("127.0.0.1", device.port(), credential);
    }

    @AfterEach
    void stopDevice() throws Exception {
        session.close();
        device.close();
    }

    @Test
    void pairsWhenTheDisplayedCodeIsEntered() throws Exception {
        session.start();

        PairingResult result = session.submitCode(device.awaitDisplayedCode());

        assertThat(result).isInstanceOf(PairingResult.Paired.class);
        assertThat(device.receivedSecret()).isNotNull();
    }

    @Test
    void exposesTheDeviceCertificateForPinning() throws Exception {
        session.start();

        assertThat(session.serverCertificate()).isEqualTo(device.certificate());
    }

    @Test
    void rejectsAWrongCodeWithoutSendingASecretToTheDevice() throws Exception {
        session.start();
        String displayed = device.awaitDisplayedCode();

        // Same nonce, deliberately wrong check byte.
        int wrongCheckByte = (Integer.parseInt(displayed.substring(0, 2), 16) + 1) & 0xFF;
        String wrong = "%02X".formatted(wrongCheckByte) + displayed.substring(2);

        PairingResult result = session.submitCode(wrong);

        assertThat(result).isInstanceOf(PairingResult.WrongCode.class);
        assertThat(device.receivedSecret())
                .as("a locally detectable wrong code must never reach the device")
                .isNull();
    }

    @Test
    void rejectsAMalformedCode() throws Exception {
        session.start();
        device.awaitDisplayedCode();

        assertThat(session.submitCode("12345")).isInstanceOf(PairingResult.WrongCode.class);
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew test --tests '*PairingSessionTest'`
Expected: FAIL — `PairingSession`, `PairingResult` do not exist.

- [ ] **Step 5: Write `PairingResult` and `PairingProtocolException`**

`src/main/java/dev/andre/shield/protocol/PairingResult.java`:

```java
package dev.andre.shield.protocol;

import java.security.cert.X509Certificate;

public sealed interface PairingResult {

    /** The device accepted the secret; this certificate is now a credential. */
    record Paired(X509Certificate serverCertificate) implements PairingResult {
    }

    /** The code did not match. The device will show a new one, so restart the flow. */
    record WrongCode() implements PairingResult {
    }

    record Failed(String reason) implements PairingResult {
    }
}
```

`src/main/java/dev/andre/shield/protocol/PairingProtocolException.java`:

```java
package dev.andre.shield.protocol;

import java.io.IOException;

public class PairingProtocolException extends IOException {

    public PairingProtocolException(String message) {
        super(message);
    }
}
```

- [ ] **Step 6: Write `PairingSession`**

```java
package dev.andre.shield.protocol;

import com.google.protobuf.ByteString;
import dev.andre.shield.protocol.pairing.PairingConfiguration;
import dev.andre.shield.protocol.pairing.PairingEncoding;
import dev.andre.shield.protocol.pairing.PairingMessage;
import dev.andre.shield.protocol.pairing.PairingOption;
import dev.andre.shield.protocol.pairing.PairingRequest;
import dev.andre.shield.protocol.pairing.PairingSecret;
import dev.andre.shield.protocol.pairing.RoleType;

import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.function.Predicate;

/**
 * Drives the Android TV Remote v2 pairing handshake on port 6467.
 *
 * <p>{@link #start()} runs the handshake up to the point where the device puts a six
 * character code on screen; {@link #submitCode(String)} completes it. See spec §5.2 —
 * in particular, the device answers a {@code pairing_option} with its own
 * {@code pairing_option}; there is no acknowledgement message for that step.
 */
public class PairingSession implements AutoCloseable {

    private static final String SERVICE_NAME = "shield-remote";
    private static final int SO_TIMEOUT_MS = 15_000;

    private final String host;
    private final int port;
    private final ClientCertificate credential;

    private SSLSocket socket;
    private MessageStream stream;
    private X509Certificate serverCertificate;

    public PairingSession(String host, int port, ClientCertificate credential) {
        this.host = host;
        this.port = port;
        this.credential = credential;
    }

    public void start() throws IOException {
        socket = TlsSockets.connect(host, port, credential, SO_TIMEOUT_MS);
        stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
        serverCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];

        stream.write(ok().setPairingRequest(PairingRequest.newBuilder()
                .setServiceName(SERVICE_NAME)
                .setClientName(SERVICE_NAME)).build());
        expect(PairingMessage::hasPairingRequestAck, "the pairing request acknowledgement");

        stream.write(ok().setPairingOption(PairingOption.newBuilder()
                .addInputEncodings(hexadecimalSixDigits())
                .setPreferredRole(RoleType.ROLE_TYPE_INPUT)).build());
        expect(PairingMessage::hasPairingOption, "the device's pairing options");

        stream.write(ok().setPairingConfiguration(PairingConfiguration.newBuilder()
                .setEncoding(hexadecimalSixDigits())
                .setClientRole(RoleType.ROLE_TYPE_INPUT)).build());
        expect(PairingMessage::hasPairingConfigurationAck, "the pairing configuration acknowledgement");
        // The device is now displaying the code.
    }

    public PairingResult submitCode(String code) {
        byte[] secret;
        try {
            secret = PairingDigest.compute(
                    (RSAPublicKey) credential.certificate().getPublicKey(),
                    (RSAPublicKey) serverCertificate.getPublicKey(),
                    code);
        } catch (PairingDigest.WrongCodeException | IllegalArgumentException e) {
            return new PairingResult.WrongCode();
        }

        try {
            stream.write(ok().setPairingSecret(PairingSecret.newBuilder()
                    .setSecret(ByteString.copyFrom(secret))).build());

            PairingMessage reply = read();
            if (reply.getStatus() == PairingMessage.Status.STATUS_BAD_SECRET) {
                return new PairingResult.WrongCode();
            }
            if (reply.getStatus() != PairingMessage.Status.STATUS_OK || !reply.hasPairingSecretAck()) {
                return new PairingResult.Failed("the device rejected the pairing: " + reply.getStatus());
            }
            return new PairingResult.Paired(serverCertificate);
        } catch (IOException e) {
            return new PairingResult.Failed(
                    "the device closed the connection; it will show a new code on the next attempt");
        }
    }

    public X509Certificate serverCertificate() {
        return serverCertificate;
    }

    private PairingMessage read() throws IOException {
        PairingMessage message = stream.read(PairingMessage.parser());
        if (message == null) {
            throw new EOFException("the device closed the pairing connection");
        }
        return message;
    }

    private void expect(Predicate<PairingMessage> expected, String what) throws IOException {
        PairingMessage message = read();
        if (message.getStatus() != PairingMessage.Status.STATUS_OK) {
            throw new PairingProtocolException(
                    "the device replied " + message.getStatus() + " while waiting for " + what);
        }
        if (!expected.test(message)) {
            throw new PairingProtocolException("expected " + what + " but the device sent something else");
        }
    }

    private static PairingEncoding hexadecimalSixDigits() {
        return PairingEncoding.newBuilder()
                .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                .setSymbolLength(6)
                .build();
    }

    private static PairingMessage.Builder ok() {
        return PairingMessage.newBuilder()
                .setProtocolVersion(2)
                .setStatus(PairingMessage.Status.STATUS_OK);
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing a already-dead pairing socket is not interesting.
            }
        }
    }
}
```

- [ ] **Step 7: Run it and watch it pass**

Run: `./gradlew test --tests '*PairingSessionTest'`
Expected: PASS — all four tests.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: pairing handshake over TLS with an in-process fake device"
```

---

### Task 7: The command channel

**Files:**
- Create: `src/main/java/dev/andre/shield/protocol/RemoteKey.java`
- Create: `src/main/java/dev/andre/shield/protocol/RemoteListener.java`
- Create: `src/main/java/dev/andre/shield/protocol/DisconnectCause.java`
- Create: `src/main/java/dev/andre/shield/protocol/RemoteConnection.java`
- Test: `src/test/java/dev/andre/shield/protocol/FakeRemoteServer.java`
- Test: `src/test/java/dev/andre/shield/protocol/RemoteConnectionTest.java`

`RemoteKey` is a hand-written enum rather than the generated `RemoteKeyCode` precisely so that callers outside `dev.andre.shield.protocol` never touch a protobuf type.

**Interfaces:**
- Consumes: `TlsSockets`, `MessageStream`, `ClientCertificate`, generated remote classes.
- Produces:
  - `enum RemoteKey { DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, BACK, HOME, MENU, POWER, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS, MEDIA_STOP, REWIND, FAST_FORWARD, INFO, SETTINGS, GUIDE }` with `int code()`
  - `enum DisconnectCause { CLOSED, STALE, UNPAIRED, ERROR }`
  - `interface RemoteListener { void onPower(boolean); void onCurrentApp(String); void onVolume(int level, int max, boolean muted); void onDisconnected(DisconnectCause); }` — all methods `default` no-ops
  - `static RemoteConnection RemoteConnection.connect(String host, int port, ClientCertificate credential, int staleTimeoutMillis, RemoteListener listener)`
  - `void sendKey(RemoteKey key)`, `void launchAppLink(String uri)`, `void close()`
  - `X509Certificate serverCertificate()` — what the device presented, so the caller can check its pin
  - `class RemoteConnection.UnpairedException extends IOException`

- [ ] **Step 1: Write `RemoteKey`, `DisconnectCause`, and `RemoteListener`**

`src/main/java/dev/andre/shield/protocol/RemoteKey.java` — values are the verified wire numbers from spec §5.3:

```java
package dev.andre.shield.protocol;

/** The subset of Android key codes this remote exposes. */
public enum RemoteKey {

    DPAD_UP(19),
    DPAD_DOWN(20),
    DPAD_LEFT(21),
    DPAD_RIGHT(22),
    DPAD_CENTER(23),
    BACK(4),
    HOME(3),
    MENU(82),
    POWER(26),
    VOLUME_UP(24),
    VOLUME_DOWN(25),
    VOLUME_MUTE(164),
    PLAY_PAUSE(85),
    MEDIA_NEXT(87),
    MEDIA_PREVIOUS(88),
    MEDIA_STOP(86),
    REWIND(89),
    FAST_FORWARD(90),
    INFO(165),
    SETTINGS(176),
    GUIDE(172);

    private final int code;

    RemoteKey(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
```

`src/main/java/dev/andre/shield/protocol/DisconnectCause.java`:

```java
package dev.andre.shield.protocol;

public enum DisconnectCause {

    /** The device hung up cleanly, or we closed the connection ourselves. */
    CLOSED,

    /** Nothing arrived within the stale timeout, so the connection is presumed dead. */
    STALE,

    /** The device refused our certificate: the pairing is gone and retrying is pointless. */
    UNPAIRED,

    ERROR
}
```

`src/main/java/dev/andre/shield/protocol/RemoteListener.java`:

```java
package dev.andre.shield.protocol;

/** Device-initiated events. Every method is a no-op by default. */
public interface RemoteListener {

    default void onPower(boolean on) {
    }

    default void onCurrentApp(String appPackage) {
    }

    default void onVolume(int level, int max, boolean muted) {
    }

    default void onDisconnected(DisconnectCause cause) {
    }
}
```

- [ ] **Step 2: Write the fake command-channel device**

`src/test/java/dev/andre/shield/protocol/FakeRemoteServer.java`:

```java
package dev.andre.shield.protocol;

import dev.andre.shield.protocol.remote.RemoteAppInfo;
import dev.andre.shield.protocol.remote.RemoteConfigure;
import dev.andre.shield.protocol.remote.RemoteImeKeyInject;
import dev.andre.shield.protocol.remote.RemoteMessage;
import dev.andre.shield.protocol.remote.RemotePingRequest;
import dev.andre.shield.protocol.remote.RemoteSetActive;
import dev.andre.shield.protocol.remote.RemoteSetVolumeLevel;
import dev.andre.shield.protocol.remote.RemoteStart;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

import java.security.SecureRandom;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for the Shield's command port. Note that the DEVICE drives
 * the handshake: it sends RemoteConfigure first and the client answers.
 */
public class FakeRemoteServer implements AutoCloseable {

    private final ClientCertificate identity = ClientCertificate.generate("fake-shield");
    private final SSLServerSocket serverSocket;
    private final CountDownLatch handshakeComplete = new CountDownLatch(1);

    private final BlockingQueue<Integer> keyPresses = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> appLinks = new LinkedBlockingQueue<>();
    private final BlockingQueue<Integer> pongs = new LinkedBlockingQueue<>();

    private final AtomicInteger connections = new AtomicInteger();

    private volatile SSLSocket socket;
    private volatile MessageStream stream;

    public FakeRemoteServer() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(TlsSockets.keyManagers(identity),
                new TrustManager[]{TlsSockets.ACCEPT_ANY}, new SecureRandom());
        serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
        serverSocket.setWantClientAuth(true);
        Thread.ofVirtual().name("fake-remote-server").start(this::serve);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void awaitHandshake() throws InterruptedException {
        if (!handshakeComplete.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the client never completed the handshake");
        }
    }

    public void pushPower(boolean on) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteStart(RemoteStart.newBuilder().setStarted(on)).build());
    }

    public void pushVolume(int level, int max, boolean muted) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteSetVolumeLevel(RemoteSetVolumeLevel.newBuilder()
                        .setVolumeLevel(level).setVolumeMax(max).setVolumeMuted(muted)).build());
    }

    public void pushCurrentApp(String appPackage) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteImeKeyInject(RemoteImeKeyInject.newBuilder()
                        .setAppInfo(RemoteAppInfo.newBuilder().setAppPackage(appPackage))).build());
    }

    public void pushPing(int value) throws Exception {
        stream.write(RemoteMessage.newBuilder()
                .setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(value)).build());
    }

    public void hangUp() throws Exception {
        socket.close();
    }

    /** How many times a client has connected; used to observe reconnects. */
    public int connections() {
        return connections.get();
    }

    public Integer nextKeyPress() throws InterruptedException {
        return keyPresses.poll(5, TimeUnit.SECONDS);
    }

    public String nextAppLink() throws InterruptedException {
        return appLinks.poll(5, TimeUnit.SECONDS);
    }

    public Integer nextPong() throws InterruptedException {
        return pongs.poll(5, TimeUnit.SECONDS);
    }

    /** Accepts connections in a loop so reconnect behaviour can be tested. */
    private void serve() {
        while (!serverSocket.isClosed()) {
            try {
                socket = (SSLSocket) serverSocket.accept();
                connections.incrementAndGet();
                handle(socket);
            } catch (Exception e) {
                // This connection ended; wait for the next one.
            }
        }
    }

    private void handle(SSLSocket connection) throws Exception {
        stream = new MessageStream(connection.getInputStream(), connection.getOutputStream());

        // The device opens the conversation.
        stream.write(RemoteMessage.newBuilder()
                .setRemoteConfigure(RemoteConfigure.newBuilder().setCode1(1)).build());

        RemoteMessage message;
        while ((message = stream.read(RemoteMessage.parser())) != null) {
            if (message.hasRemoteConfigure()) {
                stream.write(RemoteMessage.newBuilder()
                        .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622)).build());
            } else if (message.hasRemoteSetActive()) {
                handshakeComplete.countDown();
            } else if (message.hasRemoteKeyInject()) {
                if (message.getRemoteKeyInject().getDirectionValue() != 3) {
                    throw new IllegalStateException("expected SHORT direction");
                }
                keyPresses.add(message.getRemoteKeyInject().getKeyCodeValue());
            } else if (message.hasRemoteAppLinkLaunchRequest()) {
                appLinks.add(message.getRemoteAppLinkLaunchRequest().getAppLink());
            } else if (message.hasRemotePingResponse()) {
                pongs.add(message.getRemotePingResponse().getVal1());
            }
        }
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
        if (socket != null) {
            socket.close();
        }
    }
}
```

- [ ] **Step 3: Write the failing test**

`src/test/java/dev/andre/shield/protocol/RemoteConnectionTest.java`:

```java
package dev.andre.shield.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class RemoteConnectionTest {

    private FakeRemoteServer device;
    private RemoteConnection connection;

    private final AtomicReference<Boolean> power = new AtomicReference<>();
    private final AtomicReference<String> currentApp = new AtomicReference<>();
    private final AtomicInteger volume = new AtomicInteger(-1);
    private final AtomicBoolean muted = new AtomicBoolean();
    private final AtomicReference<DisconnectCause> disconnect = new AtomicReference<>();

    private final RemoteListener listener = new RemoteListener() {
        @Override
        public void onPower(boolean on) {
            power.set(on);
        }

        @Override
        public void onCurrentApp(String appPackage) {
            currentApp.set(appPackage);
        }

        @Override
        public void onVolume(int level, int max, boolean isMuted) {
            volume.set(level);
            muted.set(isMuted);
        }

        @Override
        public void onDisconnected(DisconnectCause cause) {
            disconnect.set(cause);
        }
    };

    @BeforeEach
    void connect() throws Exception {
        device = new FakeRemoteServer();
        connection = RemoteConnection.connect("127.0.0.1", device.port(),
                ClientCertificate.generate("shield-remote"), 10_000, listener);
        device.awaitHandshake();
    }

    @AfterEach
    void disconnect() throws Exception {
        connection.close();
        device.close();
    }

    @Test
    void answersTheHandshakeSoTheDeviceConsidersUsActive() {
        // awaitHandshake() in setUp already asserts this; make the intent explicit.
        assertThat(disconnect.get()).isNull();
    }

    @Test
    void reportsPowerState() throws Exception {
        device.pushPower(true);

        await().untilAtomic(power, org.hamcrest.Matchers.is(true));
    }

    @Test
    void reportsTheForegroundApp() throws Exception {
        device.pushCurrentApp("com.netflix.ninja");

        await().untilAtomic(currentApp, org.hamcrest.Matchers.is("com.netflix.ninja"));
    }

    @Test
    void reportsVolume() throws Exception {
        device.pushVolume(12, 100, true);

        await().until(() -> volume.get() == 12 && muted.get());
    }

    @Test
    void answersPingsSoTheDeviceDoesNotHangUp() throws Exception {
        device.pushPing(7);

        assertThat(device.nextPong()).isEqualTo(7);
    }

    @Test
    void sendsKeyPressesWithTheVerifiedKeyCode() throws Exception {
        connection.sendKey(RemoteKey.DPAD_UP);

        assertThat(device.nextKeyPress()).isEqualTo(19);
    }

    @Test
    void launchesAppLinks() throws Exception {
        connection.launchAppLink("market://launch?id=com.netflix.ninja");

        assertThat(device.nextAppLink()).isEqualTo("market://launch?id=com.netflix.ninja");
    }

    @Test
    void reportsWhenTheDeviceHangsUp() throws Exception {
        device.hangUp();

        await().untilAtomic(disconnect, org.hamcrest.Matchers.notNullValue());
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew test --tests '*RemoteConnectionTest'`
Expected: FAIL — `RemoteConnection` does not exist.

- [ ] **Step 5: Write `RemoteConnection`**

```java
package dev.andre.shield.protocol;

import dev.andre.shield.protocol.remote.RemoteAppLinkLaunchRequest;
import dev.andre.shield.protocol.remote.RemoteConfigure;
import dev.andre.shield.protocol.remote.RemoteDeviceInfo;
import dev.andre.shield.protocol.remote.RemoteDirection;
import dev.andre.shield.protocol.remote.RemoteKeyCode;
import dev.andre.shield.protocol.remote.RemoteKeyInject;
import dev.andre.shield.protocol.remote.RemoteMessage;
import dev.andre.shield.protocol.remote.RemotePingResponse;
import dev.andre.shield.protocol.remote.RemoteSetActive;
import dev.andre.shield.protocol.remote.RemoteSetVolumeLevel;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;

/**
 * The long-lived command channel on port 6466.
 *
 * <p>The device drives the handshake and then pushes state unprompted; a reader thread
 * answers its pings and forwards everything else to the {@link RemoteListener}.
 */
public class RemoteConnection implements AutoCloseable {

    /** The magic number the reference implementation sends for configure and set-active. */
    private static final int ACTIVE_CODE = 622;

    private final SSLSocket socket;
    private final MessageStream stream;
    private final RemoteListener listener;
    private final X509Certificate serverCertificate;

    private volatile boolean configured;
    private volatile boolean closed;

    public static RemoteConnection connect(String host, int port, ClientCertificate credential,
                                           int staleTimeoutMillis, RemoteListener listener)
            throws IOException {
        SSLSocket socket;
        try {
            socket = TlsSockets.connect(host, port, credential, staleTimeoutMillis);
        } catch (SSLException e) {
            throw new UnpairedException("the device refused our certificate", e);
        }
        return new RemoteConnection(socket, listener);
    }

    private RemoteConnection(SSLSocket socket, RemoteListener listener) throws IOException {
        this.socket = socket;
        this.listener = listener;
        this.stream = new MessageStream(socket.getInputStream(), socket.getOutputStream());
        this.serverCertificate = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        Thread.ofVirtual().name("shield-remote-reader").start(this::readLoop);
    }

    /** The certificate the device presented, for the caller to compare against its pin. */
    public X509Certificate serverCertificate() {
        return serverCertificate;
    }

    public void sendKey(RemoteKey key) throws IOException {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteKeyInject(RemoteKeyInject.newBuilder()
                        .setKeyCode(RemoteKeyCode.forNumber(key.code()))
                        .setDirection(RemoteDirection.SHORT))
                .build());
    }

    public void launchAppLink(String uri) throws IOException {
        stream.write(RemoteMessage.newBuilder()
                .setRemoteAppLinkLaunchRequest(RemoteAppLinkLaunchRequest.newBuilder().setAppLink(uri))
                .build());
    }

    private void readLoop() {
        try {
            RemoteMessage message;
            while (!closed && (message = stream.read(RemoteMessage.parser())) != null) {
                dispatch(message);
            }
            finish(DisconnectCause.CLOSED);
        } catch (SocketTimeoutException e) {
            finish(DisconnectCause.STALE);
        } catch (SSLException e) {
            finish(DisconnectCause.UNPAIRED);
        } catch (SocketException e) {
            // A reset before the device ever configured us means it rejected the certificate.
            finish(configured ? DisconnectCause.ERROR : DisconnectCause.UNPAIRED);
        } catch (IOException e) {
            finish(DisconnectCause.ERROR);
        }
    }

    private void dispatch(RemoteMessage message) throws IOException {
        if (message.hasRemoteConfigure()) {
            stream.write(RemoteMessage.newBuilder()
                    .setRemoteConfigure(RemoteConfigure.newBuilder()
                            .setCode1(ACTIVE_CODE)
                            .setDeviceInfo(RemoteDeviceInfo.newBuilder()
                                    .setModel("shield-remote")
                                    .setVendor("dev.andre")
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName("dev.andre.shield")
                                    .setAppVersion("0.1.0")))
                    .build());
            configured = true;
        } else if (message.hasRemoteSetActive()) {
            stream.write(RemoteMessage.newBuilder()
                    .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(ACTIVE_CODE))
                    .build());
        } else if (message.hasRemotePingRequest()) {
            stream.write(RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemotePingResponse.newBuilder()
                            .setVal1(message.getRemotePingRequest().getVal1()))
                    .build());
        } else if (message.hasRemoteStart()) {
            listener.onPower(message.getRemoteStart().getStarted());
        } else if (message.hasRemoteImeKeyInject()) {
            listener.onCurrentApp(message.getRemoteImeKeyInject().getAppInfo().getAppPackage());
        } else if (message.hasRemoteSetVolumeLevel()) {
            RemoteSetVolumeLevel volume = message.getRemoteSetVolumeLevel();
            listener.onVolume(volume.getVolumeLevel(), volume.getVolumeMax(), volume.getVolumeMuted());
        }
        // IME editing, voice, preferred-audio-device and RemoteError traffic is ignored.
    }

    private synchronized void finish(DisconnectCause cause) {
        if (closed) {
            return;
        }
        closed = true;
        closeSocket();
        listener.onDisconnected(cause);
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeSocket();
    }

    public static class UnpairedException extends IOException {
        public UnpairedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 6: Run it and watch it pass**

Run: `./gradlew test --tests '*RemoteConnectionTest'`
Expected: PASS — all eight tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Android TV remote command channel"
```

---

### Task 8: Device registry

**Files:**
- Create: `src/main/java/dev/andre/shield/device/Device.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceRegistry.java`
- Create: `src/main/java/dev/andre/shield/device/JsonFileDeviceRegistry.java`
- Test: `src/test/java/dev/andre/shield/device/JsonFileDeviceRegistryTest.java`

**Interfaces:**
- Consumes: Jackson (bundled with the web starter).
- Produces:
  - `record Device(String id, String name, String host, int port, String certificateFingerprint, Instant lastSeen)`
  - `interface DeviceRegistry { List<Device> findAll(); Optional<Device> findById(String id); Optional<Device> first(); void save(Device device); void delete(String id); }`
  - `JsonFileDeviceRegistry(Path file)`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/device/JsonFileDeviceRegistryTest.java`:

```java
package dev.andre.shield.device;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class JsonFileDeviceRegistryTest {

    @TempDir
    Path dir;

    private Device shield() {
        return new Device("shield-1", "Living Room Shield", "192.168.1.50", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T18:00:00Z"));
    }

    @Test
    void savesAndReadsBackADevice() {
        Path file = dir.resolve("devices.json");
        new JsonFileDeviceRegistry(file).save(shield());

        DeviceRegistry reopened = new JsonFileDeviceRegistry(file);

        assertThat(reopened.findById("shield-1")).contains(shield());
    }

    @Test
    void replacesADeviceWithTheSameId() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(shield());
        registry.save(new Device("shield-1", "Renamed", "192.168.1.51", 6466,
                "AA:BB:CC", Instant.parse("2026-08-29T19:00:00Z")));

        assertThat(registry.findAll()).hasSize(1);
        assertThat(registry.findById("shield-1")).get()
                .extracting(Device::host).isEqualTo("192.168.1.51");
    }

    @Test
    void deletesADevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(shield());
        registry.delete("shield-1");

        assertThat(registry.findAll()).isEmpty();
        assertThat(registry.first()).isEmpty();
    }

    @Test
    void startsEmptyWhenTheFileDoesNotExist() {
        assertThat(new JsonFileDeviceRegistry(dir.resolve("missing.json")).findAll()).isEmpty();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*JsonFileDeviceRegistryTest'`
Expected: FAIL — classes do not exist.

- [ ] **Step 3: Write `Device` and `DeviceRegistry`**

`src/main/java/dev/andre/shield/device/Device.java`:

```java
package dev.andre.shield.device;

import java.time.Instant;

/**
 * A paired device. {@code certificateFingerprint} is the device certificate recorded
 * during pairing; the command channel refuses anything else (spec §6).
 */
public record Device(String id, String name, String host, int port,
                     String certificateFingerprint, Instant lastSeen) {

    public String certificateAlias() {
        return id;
    }
}
```

`src/main/java/dev/andre/shield/device/DeviceRegistry.java`:

```java
package dev.andre.shield.device;

import java.util.List;
import java.util.Optional;

public interface DeviceRegistry {

    List<Device> findAll();

    Optional<Device> findById(String id);

    /** The single device the v1 UI controls. */
    Optional<Device> first();

    void save(Device device);

    void delete(String id);
}
```

- [ ] **Step 4: Write `JsonFileDeviceRegistry`**

```java
package dev.andre.shield.device;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registry backed by a small JSON file, written atomically via a temp file and rename. */
public class JsonFileDeviceRegistry implements DeviceRegistry {

    // Spring Boot 4.1 ships Jackson 3 (tools.jackson), where Java 8 date/time
    // support is built into databind -- there is no JavaTimeModule to register.
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Path file;

    public JsonFileDeviceRegistry(Path file) {
        this.file = file;
    }

    @Override
    public synchronized List<Device> findAll() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return mapper.readValue(Files.readAllBytes(file), new TypeReference<List<Device>>() {
            });
        } catch (IOException | JacksonException e) {
            // Jackson 3 parse failures are unchecked, so they need naming explicitly.
            throw new IllegalStateException("Could not read " + file, e);
        }
    }

    @Override
    public Optional<Device> findById(String id) {
        return findAll().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Device> first() {
        return findAll().stream().findFirst();
    }

    @Override
    public synchronized void save(Device device) {
        List<Device> devices = new ArrayList<>(findAll());
        devices.removeIf(existing -> existing.id().equals(device.id()));
        devices.add(device);
        writeAll(devices);
    }

    @Override
    public synchronized void delete(String id) {
        List<Device> devices = new ArrayList<>(findAll());
        devices.removeIf(existing -> existing.id().equals(id));
        writeAll(devices);
    }

    private void writeAll(List<Device> devices) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "devices", ".json");
            Files.write(temp, mapper.writeValueAsBytes(devices));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew test --tests '*JsonFileDeviceRegistryTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: JSON-backed device registry"
```

---

### Task 9: Live device session with reconnect

**Files:**
- Create: `src/main/java/dev/andre/shield/ShieldProperties.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceStatus.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceState.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceOfflineException.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceSession.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceStateChangedEvent.java`
- Create: `src/main/java/dev/andre/shield/device/DeviceSessionManager.java`
- Modify: `src/main/java/dev/andre/shield/ShieldApplication.java` — add `@ConfigurationPropertiesScan`
- Test: `src/test/java/dev/andre/shield/device/DeviceSessionTest.java`

This task also implements the certificate pinning spec §6 requires: the fingerprint
recorded at pairing time is compared against what the device presents, and a mismatch
is treated as `UNPAIRED` rather than silently trusted.

**Deviation from the spec, on purpose:** spec §6 gives `DeviceState` a `connected` boolean. This task uses a `DeviceStatus` enum (`DISCONNECTED`, `CONNECTING`, `CONNECTED`, `UNPAIRED`) instead, because spec §8 requires "certificate rejected" to be distinguishable from "network down" — a boolean cannot express it. `DeviceState.connected()` is kept as a derived accessor for templates.

**Interfaces:**
- Consumes: `RemoteConnection`, `RemoteListener`, `RemoteKey`, `DisconnectCause`, `CertificateStore`, `DeviceRegistry`.
- Produces:
  - `record ShieldProperties(Path dataDir, String keystorePassword, boolean discoveryEnabled, int staleTimeoutSeconds, int reconnectInitialDelaySeconds, int reconnectMaxDelaySeconds)` with `keystoreFile()`, `devicesFile()`, `appsFile()`
  - `record DeviceState(DeviceStatus status, boolean powerOn, String currentApp, int volumeLevel, int volumeMax, boolean muted, Instant updatedAt)` with `connected()` and `with*` copies
  - `DeviceSession(Device, ClientCertificate, ShieldProperties, Consumer<DeviceState>)` with `start()`, `state()`, `device()`, `sendKey(RemoteKey)`, `launchAppLink(String)`, `close()`
  - `DeviceSessionManager` with `Optional<DeviceSession> active()`, `DeviceState state()`, `void adopt(Device)`, `void forget(String id)`
  - `record DeviceStateChangedEvent(DeviceState state)`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/device/DeviceSessionTest.java`:

```java
package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.FakeRemoteServer;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class DeviceSessionTest {

    private FakeRemoteServer fakeDevice;
    private DeviceSession session;

    private static final ShieldProperties PROPERTIES = new ShieldProperties(
            Path.of("./build/test-data"), "shield", false, 10, 1, 4);

    @BeforeEach
    void startSession() throws Exception {
        fakeDevice = new FakeRemoteServer();
        // A null fingerprint means "not pinned yet"; pinning has its own test below.
        Device device = new Device("shield-1", "Test Shield", "127.0.0.1", fakeDevice.port(),
                null, Instant.now());
        session = new DeviceSession(device, ClientCertificate.generate("shield-remote"),
                PROPERTIES, state -> {
        });
    }

    @AfterEach
    void stopSession() throws Exception {
        session.close();
        fakeDevice.close();
    }

    @Test
    void reachesConnectedOnceTheHandshakeCompletes() {
        session.start();

        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);
    }

    @Test
    void reflectsStateThatTheDevicePushes() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fakeDevice.pushVolume(12, 100, true);
        fakeDevice.pushCurrentApp("com.netflix.ninja");
        fakeDevice.pushPower(true);

        await().until(() -> session.state().volumeLevel() == 12
                && session.state().muted()
                && "com.netflix.ninja".equals(session.state().currentApp())
                && session.state().powerOn());
    }

    @Test
    void refusesCommandsWhileDisconnected() {
        assertThatThrownBy(() -> session.sendKey(RemoteKey.DPAD_UP))
                .isInstanceOf(DeviceOfflineException.class);
    }

    @Test
    void deliversKeyPressesWhileConnected() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        session.sendKey(RemoteKey.DPAD_UP);

        assertThat(fakeDevice.nextKeyPress()).isEqualTo(19);
    }

    @Test
    void refusesADeviceWhoseCertificateDoesNotMatchThePin() {
        Device impostor = new Device("shield-2", "Impostor", "127.0.0.1", fakeDevice.port(),
                "0000000000000000000000000000000000000000000000000000000000000000", Instant.now());

        try (DeviceSession pinned = new DeviceSession(impostor,
                ClientCertificate.generate("shield-remote"), PROPERTIES, state -> {
        })) {
            pinned.start();

            await().until(() -> pinned.state().status() == DeviceStatus.UNPAIRED);
        }
    }

    @Test
    void reconnectsAfterTheDeviceHangsUp() throws Exception {
        session.start();
        await().until(() -> session.state().status() == DeviceStatus.CONNECTED);

        fakeDevice.hangUp();

        await().until(() -> fakeDevice.connections() >= 2
                && session.state().status() == DeviceStatus.CONNECTED);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*DeviceSessionTest'`
Expected: FAIL — `ShieldProperties`, `DeviceSession` and friends do not exist.

- [ ] **Step 3: Write `ShieldProperties` and wire property scanning**

`src/main/java/dev/andre/shield/ShieldProperties.java`:

```java
package dev.andre.shield;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties("shield")
public record ShieldProperties(Path dataDir,
                               String keystorePassword,
                               boolean discoveryEnabled,
                               int staleTimeoutSeconds,
                               int reconnectInitialDelaySeconds,
                               int reconnectMaxDelaySeconds) {

    public Path keystoreFile() {
        return dataDir.resolve("keystore.p12");
    }

    public Path devicesFile() {
        return dataDir.resolve("devices.json");
    }

    public Path appsFile() {
        return dataDir.resolve("apps.yaml");
    }
}
```

In `ShieldApplication.java`, add the annotation and its import:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class ShieldApplication {
```

- [ ] **Step 4: Write `DeviceStatus`, `DeviceState`, `DeviceOfflineException`, `DeviceStateChangedEvent`**

```java
package dev.andre.shield.device;

public enum DeviceStatus {
    DISCONNECTED, CONNECTING, CONNECTED,
    /** The device rejected our certificate; reconnecting cannot fix this, re-pairing can. */
    UNPAIRED
}
```

```java
package dev.andre.shield.device;

import java.time.Instant;

public record DeviceState(DeviceStatus status, boolean powerOn, String currentApp,
                          int volumeLevel, int volumeMax, boolean muted, Instant updatedAt) {

    public static DeviceState initial() {
        return new DeviceState(DeviceStatus.DISCONNECTED, false, null, 0, 0, false, Instant.now());
    }

    public boolean connected() {
        return status == DeviceStatus.CONNECTED;
    }

    public DeviceState withStatus(DeviceStatus newStatus) {
        return new DeviceState(newStatus, powerOn, currentApp, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withPower(boolean on) {
        return new DeviceState(status, on, currentApp, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withCurrentApp(String appPackage) {
        return new DeviceState(status, powerOn, appPackage, volumeLevel, volumeMax, muted, Instant.now());
    }

    public DeviceState withVolume(int level, int max, boolean isMuted) {
        return new DeviceState(status, powerOn, currentApp, level, max, isMuted, Instant.now());
    }
}
```

```java
package dev.andre.shield.device;

public class DeviceOfflineException extends RuntimeException {
    public DeviceOfflineException(String message) {
        super(message);
    }
}
```

```java
package dev.andre.shield.device;

/** Published whenever a session's state changes, so the SSE layer can forward it. */
public record DeviceStateChangedEvent(DeviceState state) {
}
```

- [ ] **Step 5: Write `DeviceSession`**

```java
package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.DisconnectCause;
import dev.andre.shield.protocol.RemoteConnection;
import dev.andre.shield.protocol.RemoteKey;
import dev.andre.shield.protocol.RemoteListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One device's live connection: connects, keeps the last known {@link DeviceState},
 * and reconnects with exponential backoff — except when the device has rejected the
 * pairing, where retrying is pointless (spec §8).
 */
public class DeviceSession implements RemoteListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceSession.class);

    private final Device device;
    private final ClientCertificate credential;
    private final ShieldProperties properties;
    private final Consumer<DeviceState> onChange;
    private final ScheduledExecutorService scheduler;

    private volatile RemoteConnection connection;
    private volatile DeviceState state = DeviceState.initial();
    private volatile Duration backoff;
    private volatile boolean closed;

    public DeviceSession(Device device, ClientCertificate credential,
                         ShieldProperties properties, Consumer<DeviceState> onChange) {
        this.device = device;
        this.credential = credential;
        this.properties = properties;
        this.onChange = onChange;
        this.backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shield-session-" + device.id());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        scheduler.execute(this::connect);
    }

    public Device device() {
        return device;
    }

    public DeviceState state() {
        return state;
    }

    public void sendKey(RemoteKey key) {
        RemoteConnection current = requireConnected();
        try {
            current.sendKey(key);
        } catch (IOException e) {
            throw new DeviceOfflineException("The device dropped the connection while sending " + key);
        }
    }

    public void launchAppLink(String uri) {
        RemoteConnection current = requireConnected();
        try {
            current.launchAppLink(uri);
        } catch (IOException e) {
            throw new DeviceOfflineException("The device dropped the connection while launching " + uri);
        }
    }

    private RemoteConnection requireConnected() {
        RemoteConnection current = connection;
        if (current == null || state.status() != DeviceStatus.CONNECTED) {
            throw new DeviceOfflineException("The device is not connected");
        }
        return current;
    }

    private void connect() {
        if (closed) {
            return;
        }
        update(state.withStatus(DeviceStatus.CONNECTING));
        try {
            RemoteConnection opened = RemoteConnection.connect(device.host(), device.port(),
                    credential, properties.staleTimeoutSeconds() * 1000, this);

            if (!presentsThePinnedCertificate(opened)) {
                log.warn("Device {} presented an unexpected certificate; refusing it", device.id());
                opened.close();
                update(state.withStatus(DeviceStatus.UNPAIRED));
                return;
            }

            connection = opened;
            backoff = Duration.ofSeconds(properties.reconnectInitialDelaySeconds());
            update(state.withStatus(DeviceStatus.CONNECTED));
        } catch (RemoteConnection.UnpairedException e) {
            log.warn("Device {} rejected our certificate; it must be paired again", device.id());
            update(state.withStatus(DeviceStatus.UNPAIRED));
        } catch (IOException e) {
            log.debug("Could not reach {}: {}", device.host(), e.getMessage());
            update(state.withStatus(DeviceStatus.DISCONNECTED));
            scheduleReconnect();
        }
    }

    /** A device recorded without a fingerprint (paired before pinning) is accepted once. */
    private boolean presentsThePinnedCertificate(RemoteConnection opened) {
        String pinned = device.certificateFingerprint();
        return pinned == null
                || pinned.equals(ClientCertificate.fingerprintOf(opened.serverCertificate()));
    }

    private void scheduleReconnect() {
        if (closed) {
            return;
        }
        Duration delay = backoff;
        backoff = Duration.ofSeconds(Math.min(
                backoff.toSeconds() * 2, properties.reconnectMaxDelaySeconds()));
        scheduler.schedule(this::connect, delay.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void onPower(boolean on) {
        update(state.withPower(on));
    }

    @Override
    public void onCurrentApp(String appPackage) {
        update(state.withCurrentApp(appPackage));
    }

    @Override
    public void onVolume(int level, int max, boolean isMuted) {
        update(state.withVolume(level, max, isMuted));
    }

    @Override
    public void onDisconnected(DisconnectCause cause) {
        if (closed) {
            return;
        }
        connection = null;
        if (cause == DisconnectCause.UNPAIRED) {
            log.warn("Device {} rejected our certificate; not retrying", device.id());
            update(state.withStatus(DeviceStatus.UNPAIRED));
            return;
        }
        log.info("Lost the connection to {} ({}); reconnecting", device.id(), cause);
        update(state.withStatus(DeviceStatus.DISCONNECTED));
        scheduleReconnect();
    }

    private void update(DeviceState updated) {
        state = updated;
        onChange.accept(updated);
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        RemoteConnection current = connection;
        if (current != null) {
            current.close();
        }
    }
}
```

- [ ] **Step 6: Write `DeviceSessionManager`**

```java
package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.CertificateStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one {@link DeviceSession} per registered device. The v1 UI drives whichever
 * device is first in the registry; the map is what makes "multiple devices later"
 * a UI change rather than a rewrite.
 */
@Service
public class DeviceSessionManager implements AutoCloseable {

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final CertificateStore certificates;
    private final ShieldProperties properties;
    private final ApplicationEventPublisher events;

    public DeviceSessionManager(DeviceRegistry registry, CertificateStore certificates,
                                ShieldProperties properties, ApplicationEventPublisher events) {
        this.registry = registry;
        this.certificates = certificates;
        this.properties = properties;
        this.events = events;
    }

    @PostConstruct
    public void startRegisteredDevices() {
        registry.findAll().forEach(this::startSession);
    }

    public Optional<DeviceSession> active() {
        return registry.first().map(device -> sessions.get(device.id()));
    }

    public DeviceState state() {
        return active().map(DeviceSession::state).orElseGet(DeviceState::initial);
    }

    public Optional<Device> activeDevice() {
        return registry.first();
    }

    /** Registers a freshly paired device and brings its session up. */
    public void adopt(Device device) {
        registry.save(device);
        startSession(device);
    }

    public void forget(String id) {
        DeviceSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
        registry.delete(id);
    }

    private void startSession(Device device) {
        DeviceSession existing = sessions.remove(device.id());
        if (existing != null) {
            existing.close();
        }
        DeviceSession session = new DeviceSession(device,
                certificates.loadOrCreate(device.certificateAlias()),
                properties,
                state -> events.publishEvent(new DeviceStateChangedEvent(state)));
        sessions.put(device.id(), session);
        session.start();
    }

    @Override
    @PreDestroy
    public void close() {
        sessions.values().forEach(DeviceSession::close);
        sessions.clear();
    }
}
```

- [ ] **Step 7: Provide the registry and certificate store as beans**

Create `src/main/java/dev/andre/shield/ShieldConfiguration.java`:

```java
package dev.andre.shield;

import dev.andre.shield.device.DeviceRegistry;
import dev.andre.shield.device.JsonFileDeviceRegistry;
import dev.andre.shield.protocol.CertificateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ShieldConfiguration {

    @Bean
    public DeviceRegistry deviceRegistry(ShieldProperties properties) {
        return new JsonFileDeviceRegistry(properties.devicesFile());
    }

    @Bean
    public CertificateStore certificateStore(ShieldProperties properties) {
        return new CertificateStore(properties.keystoreFile(),
                properties.keystorePassword().toCharArray());
    }
}
```

- [ ] **Step 8: Run the tests and watch them pass**

Run: `./gradlew test`
Expected: PASS — including `ShieldApplicationTest`, which now also proves the beans wire up.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: device session with reconnect and state tracking"
```

---

### Task 10: mDNS discovery

**Files:**
- Create: `src/main/java/dev/andre/shield/discovery/DiscoveredDevice.java`
- Create: `src/main/java/dev/andre/shield/discovery/MdnsDiscovery.java`
- Test: `src/test/java/dev/andre/shield/discovery/MdnsDiscoveryTest.java`

The mapping from an mDNS record to a `DiscoveredDevice` is a pure function so it can be tested without multicast. A live end-to-end discovery test is included but only runs when explicitly enabled, because container and CI networks routinely block multicast.

**Interfaces:**
- Consumes: JmDNS, `ShieldProperties`.
- Produces:
  - `record DiscoveredDevice(String name, String host, int port)`
  - `static Optional<DiscoveredDevice> MdnsDiscovery.toDevice(String name, InetAddress[] addresses, int port)`
  - `List<DiscoveredDevice> MdnsDiscovery.devices()`
  - `MdnsDiscovery.SERVICE_TYPE` = `"_androidtvremote2._tcp.local."`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/discovery/MdnsDiscoveryTest.java`:

```java
package dev.andre.shield.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MdnsDiscoveryTest {

    @Test
    void mapsAResolvedServiceToADevice() throws Exception {
        InetAddress address = InetAddress.getByName("192.168.1.50");

        assertThat(MdnsDiscovery.toDevice("Living Room Shield", new InetAddress[]{address}, 6466))
                .contains(new DiscoveredDevice("Living Room Shield", "192.168.1.50", 6466));
    }

    @Test
    void ignoresAServiceWithNoAddress() {
        assertThat(MdnsDiscovery.toDevice("Ghost", new InetAddress[0], 6466)).isEmpty();
    }

    @Test
    void ignoresAServiceWithNoPort() throws Exception {
        InetAddress address = InetAddress.getByName("192.168.1.50");

        assertThat(MdnsDiscovery.toDevice("Portless", new InetAddress[]{address}, 0)).isEmpty();
    }

    @Test
    void usesTheAndroidTvRemoteServiceType() {
        assertThat(MdnsDiscovery.SERVICE_TYPE).isEqualTo("_androidtvremote2._tcp.local.");
    }

    /**
     * Real multicast round trip. Disabled by default: Docker bridge networks and most CI
     * runners drop mDNS. Run with: ./gradlew test -Dmdns.tests=true
     */
    @Test
    @EnabledIfSystemProperty(named = "mdns.tests", matches = "true")
    void findsAServiceAdvertisedOnTheLocalNetwork() throws Exception {
        try (JmDNS advertiser = JmDNS.create(InetAddress.getLocalHost());
             MdnsDiscovery discovery = new MdnsDiscovery(true)) {

            advertiser.registerService(ServiceInfo.create(
                    MdnsDiscovery.SERVICE_TYPE, "Fake Shield", 6466, "test"));
            discovery.start();

            await().until(() -> discovery.devices().stream()
                    .anyMatch(device -> device.name().contains("Fake Shield")));
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*MdnsDiscoveryTest'`
Expected: FAIL — `MdnsDiscovery` does not exist.

- [ ] **Step 3: Write `DiscoveredDevice`**

```java
package dev.andre.shield.discovery;

/** A device seen on the network but not necessarily paired. */
public record DiscoveredDevice(String name, String host, int port) {
}
```

- [ ] **Step 4: Write `MdnsDiscovery`**

```java
package dev.andre.shield.discovery;

import dev.andre.shield.ShieldProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds Android TV devices advertising the remote service.
 *
 * <p>Multicast does not cross a Docker bridge network, so the UI always offers manual
 * host entry alongside whatever this finds (spec §7).
 */
@Service
public class MdnsDiscovery implements AutoCloseable {

    public static final String SERVICE_TYPE = "_androidtvremote2._tcp.local.";

    private static final Logger log = LoggerFactory.getLogger(MdnsDiscovery.class);

    private final Map<String, DiscoveredDevice> found = new ConcurrentHashMap<>();
    private final boolean enabled;

    private JmDNS jmdns;

    /** Two constructors, so Spring needs to be told which one to use. */
    @Autowired
    public MdnsDiscovery(ShieldProperties properties) {
        this(properties.discoveryEnabled());
    }

    public MdnsDiscovery(boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("mDNS discovery is disabled; add devices by host name or address");
            return;
        }
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            jmdns.addServiceListener(SERVICE_TYPE, new Listener());
            log.info("Listening for {}", SERVICE_TYPE);
        } catch (IOException e) {
            log.warn("Could not start mDNS discovery ({}); use manual host entry", e.getMessage());
        }
    }

    public List<DiscoveredDevice> devices() {
        return List.copyOf(found.values());
    }

    /** Pure mapping so it can be tested without multicast. */
    static Optional<DiscoveredDevice> toDevice(String name, InetAddress[] addresses, int port) {
        if (addresses == null || addresses.length == 0 || port <= 0) {
            return Optional.empty();
        }
        return Optional.of(new DiscoveredDevice(name, addresses[0].getHostAddress(), port));
    }

    private class Listener implements ServiceListener {

        @Override
        public void serviceAdded(ServiceEvent event) {
            // Resolution arrives via serviceResolved; ask for it explicitly.
            event.getDNS().requestServiceInfo(event.getType(), event.getName(), 1000);
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            found.remove(event.getName());
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            toDevice(info.getName(), info.getInetAddresses(), info.getPort())
                    .ifPresent(device -> {
                        found.put(event.getName(), device);
                        log.info("Discovered {} at {}:{}", device.name(), device.host(), device.port());
                    });
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (jmdns != null) {
            try {
                jmdns.close();
            } catch (IOException ignored) {
                // Shutting down anyway.
            }
        }
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew test --tests '*MdnsDiscoveryTest'`
Expected: PASS — four tests run, the live one skipped.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: mDNS discovery of Android TV devices"
```

---

### Task 11: App catalog

**Files:**
- Create: `src/main/java/dev/andre/shield/apps/AppEntry.java`
- Create: `src/main/java/dev/andre/shield/apps/AppCatalog.java`
- Create: `src/main/resources/default-apps.yaml`
- Test: `src/test/java/dev/andre/shield/apps/AppCatalogTest.java`

The protocol reports the current foreground app but cannot enumerate installed apps, so the launcher is driven by this editable catalog (spec §7). SnakeYAML ships with the Spring Boot starter — no new dependency.

**Interfaces:**
- Consumes: `ShieldProperties`.
- Produces:
  - `record AppEntry(String id, String name, String appPackage, String deepLink)` with `String launchUri()`
  - `AppCatalog(Path file)` — copies the bundled default catalog on first run
  - `List<AppEntry> entries()`, `Optional<AppEntry> byId(String)`, `Optional<AppEntry> byPackage(String)`

- [ ] **Step 1: Write the failing test**

`src/test/java/dev/andre/shield/apps/AppCatalogTest.java`:

```java
package dev.andre.shield.apps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AppCatalogTest {

    @TempDir
    Path dir;

    @Test
    void fallsBackToTheGenericLaunchUriWhenNoDeepLinkIsConfigured() {
        AppEntry entry = new AppEntry("netflix", "Netflix", "com.netflix.ninja", null);

        assertThat(entry.launchUri()).isEqualTo("market://launch?id=com.netflix.ninja");
    }

    @Test
    void prefersAConfiguredDeepLink() {
        AppEntry entry = new AppEntry("plex", "Plex", "com.plexapp.android", "plex://");

        assertThat(entry.launchUri()).isEqualTo("plex://");
    }

    @Test
    void writesTheBundledCatalogOnFirstRun() {
        Path file = dir.resolve("apps.yaml");

        AppCatalog catalog = new AppCatalog(file);

        assertThat(Files.exists(file)).isTrue();
        assertThat(catalog.entries()).isNotEmpty();
        assertThat(catalog.byId("netflix")).isPresent();
    }

    @Test
    void readsAUserEditedCatalog() throws Exception {
        Path file = dir.resolve("apps.yaml");
        Files.writeString(file, """
                apps:
                  - id: kodi
                    name: Kodi
                    package: org.xbmc.kodi
                  - id: plex
                    name: Plex
                    package: com.plexapp.android
                    deepLink: "plex://"
                """);

        AppCatalog catalog = new AppCatalog(file);

        assertThat(catalog.entries()).hasSize(2);
        assertThat(catalog.byId("kodi")).get().extracting(AppEntry::name).isEqualTo("Kodi");
        assertThat(catalog.byId("plex")).get().extracting(AppEntry::launchUri).isEqualTo("plex://");
    }

    @Test
    void findsAnEntryByItsPackageSoTheUiCanNameTheForegroundApp() {
        AppCatalog catalog = new AppCatalog(dir.resolve("apps.yaml"));

        assertThat(catalog.byPackage("com.netflix.ninja")).get()
                .extracting(AppEntry::name).isEqualTo("Netflix");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*AppCatalogTest'`
Expected: FAIL — `AppEntry` and `AppCatalog` do not exist.

- [ ] **Step 3: Write the bundled catalog**

`src/main/resources/default-apps.yaml`:

```yaml
# Edit the copy of this file in your data directory (apps.yaml) to change the launcher.
# "package" is enough: without a deepLink the app is opened with market://launch?id=<package>.
apps:
  - id: netflix
    name: Netflix
    package: com.netflix.ninja
  - id: youtube
    name: YouTube
    package: com.google.android.youtube.tv
  - id: prime-video
    name: Prime Video
    package: com.amazon.amazonvideo.livingroom
  - id: disney-plus
    name: Disney+
    package: com.disney.disneyplus
  - id: plex
    name: Plex
    package: com.plexapp.android
  - id: kodi
    name: Kodi
    package: org.xbmc.kodi
  - id: spotify
    name: Spotify
    package: com.spotify.tv.android
  - id: twitch
    name: Twitch
    package: tv.twitch.android.app
  - id: ard-mediathek
    name: ARD Mediathek
    package: de.swr.ard.avp.mobile.android
  - id: zdf-mediathek
    name: ZDF Mediathek
    package: com.zdf.android.mediathek
```

- [ ] **Step 4: Write `AppEntry` and `AppCatalog`**

```java
package dev.andre.shield.apps;

/** A launchable app. {@code deepLink} is optional. */
public record AppEntry(String id, String name, String appPackage, String deepLink) {

    /**
     * The URI sent as an app-link launch request. Without a configured deep link this
     * uses the generic Android TV "open this app" form.
     */
    public String launchUri() {
        if (deepLink != null && !deepLink.isBlank()) {
            return deepLink;
        }
        return "market://launch?id=" + appPackage;
    }
}
```

```java
package dev.andre.shield.apps;

import dev.andre.shield.ShieldProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The launcher's list of apps, read from a user-editable YAML file. */
@Component
public class AppCatalog {

    private static final String BUNDLED = "/default-apps.yaml";

    private final List<AppEntry> entries;

    /** Two constructors, so Spring needs to be told which one to use. */
    @Autowired
    public AppCatalog(ShieldProperties properties) {
        this(properties.appsFile());
    }

    public AppCatalog(Path file) {
        this.entries = load(file);
    }

    public List<AppEntry> entries() {
        return entries;
    }

    public Optional<AppEntry> byId(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    public Optional<AppEntry> byPackage(String appPackage) {
        return entries.stream().filter(entry -> entry.appPackage().equals(appPackage)).findFirst();
    }

    private static List<AppEntry> load(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try (InputStream bundled = AppCatalog.class.getResourceAsStream(BUNDLED)) {
                    Files.write(file, bundled.readAllBytes());
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                return parse(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the app catalog " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AppEntry> parse(InputStream in) {
        Map<String, Object> document = new Yaml().load(in);
        List<Map<String, String>> apps =
                (List<Map<String, String>>) document.getOrDefault("apps", List.of());

        List<AppEntry> entries = new ArrayList<>();
        for (Map<String, String> app : apps) {
            entries.add(new AppEntry(
                    app.get("id"), app.get("name"), app.get("package"), app.get("deepLink")));
        }
        return List.copyOf(entries);
    }
}
```

- [ ] **Step 5: Run it and watch it pass**

Run: `./gradlew test --tests '*AppCatalogTest'`
Expected: PASS — five tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: editable app catalog for the launcher"
```

---

### Task 12: Pairing service, controllers, and the SSE state stream

**Files:**
- Create: `src/main/java/dev/andre/shield/device/PairingService.java`
- Create: `src/main/java/dev/andre/shield/web/DeviceStateBroadcaster.java`
- Create: `src/main/java/dev/andre/shield/web/StateController.java`
- Create: `src/main/java/dev/andre/shield/web/RemoteController.java`
- Create: `src/main/java/dev/andre/shield/web/SetupController.java`
- Test: `src/test/java/dev/andre/shield/device/PairingServiceTest.java`
- Test: `src/test/java/dev/andre/shield/web/RemoteControllerTest.java`

**Interfaces:**
- Consumes: `PairingSession`, `PairingResult`, `CertificateStore`, `ClientCertificate.fingerprintOf`, `DeviceSessionManager`, `AppCatalog`, `MdnsDiscovery`.
- Produces:
  - `PairingService` with `void begin(String host, String name)`, `PairingResult submit(String code)`, `boolean inProgress()`, `void cancel()`
  - `DeviceStateBroadcaster` with `SseEmitter subscribe()`
  - Routes: `POST /key/{key}`, `POST /apps/{id}/launch`, `GET /events`, `GET /setup`, `POST /setup/pair`, `POST /setup/code`, `POST /setup/forget`

- [ ] **Step 1: Write the failing pairing-service test**

`src/test/java/dev/andre/shield/device/PairingServiceTest.java`:

```java
package dev.andre.shield.device;

import dev.andre.shield.ShieldProperties;
import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.FakePairingServer;
import dev.andre.shield.protocol.PairingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PairingServiceTest {

    @TempDir
    Path dir;

    private FakePairingServer fakeDevice;
    private PairingService service;
    private DeviceSessionManager sessions;

    @BeforeEach
    void setUp() throws Exception {
        fakeDevice = new FakePairingServer();
        ShieldProperties properties = new ShieldProperties(dir, "shield", false, 10, 1, 4);
        sessions = mock(DeviceSessionManager.class);
        service = new PairingService(
                new CertificateStore(properties.keystoreFile(), "shield".toCharArray()),
                sessions);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.cancel();
        fakeDevice.close();
    }

    @Test
    void pairsAndHandsTheDeviceToTheSessionManager() throws Exception {
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");

        PairingResult result = service.submit(fakeDevice.awaitDisplayedCode());

        assertThat(result).isInstanceOf(PairingResult.Paired.class);
        verify(sessions).adopt(org.mockito.ArgumentMatchers.argThat(device ->
                device.name().equals("Living Room Shield")
                        && device.host().equals("127.0.0.1")
                        && device.port() == 6466
                        && device.certificateFingerprint() != null));
    }

    @Test
    void reportsAWrongCodeAndEndsTheSession() throws Exception {
        service.begin("127.0.0.1", fakeDevice.port(), "Living Room Shield");
        String displayed = fakeDevice.awaitDisplayedCode();
        int wrongCheckByte = (Integer.parseInt(displayed.substring(0, 2), 16) + 1) & 0xFF;

        PairingResult result = service.submit("%02X".formatted(wrongCheckByte) + displayed.substring(2));

        assertThat(result).isInstanceOf(PairingResult.WrongCode.class);
        assertThat(service.inProgress())
                .as("the device shows a new code, so the flow must restart")
                .isFalse();
    }

    @Test
    void reportsWhenNoPairingIsInFlight() {
        assertThat(service.submit("70B2C3")).isInstanceOf(PairingResult.Failed.class);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests '*PairingServiceTest'`
Expected: FAIL — `PairingService` does not exist.

- [ ] **Step 3: Write `PairingService`**

```java
package dev.andre.shield.device;

import dev.andre.shield.protocol.CertificateStore;
import dev.andre.shield.protocol.ClientCertificate;
import dev.andre.shield.protocol.PairingResult;
import dev.andre.shield.protocol.PairingSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * Holds the one in-flight pairing attempt.
 *
 * <p>A failed attempt always ends the session: the device shows a brand new code next
 * time, so there is nothing to retry into (spec §5.2).
 */
@Service
public class PairingService {

    /** The pairing port. The command channel is 6466. */
    public static final int PAIRING_PORT = 6467;
    public static final int REMOTE_PORT = 6466;

    private static final Logger log = LoggerFactory.getLogger(PairingService.class);

    private final CertificateStore certificates;
    private final DeviceSessionManager sessions;

    private volatile PairingSession session;
    private volatile ClientCertificate credential;
    private volatile String host;
    private volatile String name;

    public PairingService(CertificateStore certificates, DeviceSessionManager sessions) {
        this.certificates = certificates;
        this.sessions = sessions;
    }

    public void begin(String host, String name) throws IOException {
        begin(host, PAIRING_PORT, name);
    }

    /** Port is a parameter only so tests can point at an in-process fake device. */
    public void begin(String host, int port, String name) throws IOException {
        cancel();
        this.host = host;
        this.name = (name == null || name.isBlank()) ? host : name;
        this.credential = certificates.loadOrCreate(deviceId());

        PairingSession starting = new PairingSession(host, port, credential);
        starting.start();
        this.session = starting;
    }

    public boolean inProgress() {
        return session != null;
    }

    public PairingResult submit(String code) {
        PairingSession current = session;
        if (current == null) {
            return new PairingResult.Failed("No pairing is in progress; start again from the device list");
        }

        PairingResult result = current.submitCode(code);
        if (result instanceof PairingResult.Paired paired) {
            certificates.save(deviceId(), credential);
            sessions.adopt(new Device(
                    deviceId(),
                    name,
                    host,
                    REMOTE_PORT,
                    ClientCertificate.fingerprintOf(paired.serverCertificate()),
                    Instant.now()));
            log.info("Paired with {} at {}", name, host);
        }
        cancel();
        return result;
    }

    public void cancel() {
        PairingSession current = session;
        session = null;
        if (current != null) {
            current.close();
        }
    }

    /** Stable across re-pairings so the same certificate alias and registry entry are reused. */
    private String deviceId() {
        String base = (name == null || name.isBlank()) ? host : name;
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `./gradlew test --tests '*PairingServiceTest'`
Expected: PASS — three tests.

- [ ] **Step 5: Write the failing controller test**

`src/test/java/dev/andre/shield/web/RemoteControllerTest.java`:

```java
package dev.andre.shield.web;

import dev.andre.shield.apps.AppCatalog;
import dev.andre.shield.apps.AppEntry;
import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.protocol.RemoteKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RemoteController.class)
class RemoteControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceSessionManager sessions;

    @MockitoBean
    AppCatalog apps;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    DeviceSession session;

    @BeforeEach
    void setUp() {
        session = org.mockito.Mockito.mock(DeviceSession.class);
        given(sessions.active()).willReturn(Optional.of(session));
    }

    @Test
    void sendsAKeyPress() throws Exception {
        mockMvc.perform(post("/key/DPAD_UP")).andExpect(status().isNoContent());

        verify(session).sendKey(RemoteKey.DPAD_UP);
    }

    @Test
    void rejectsAnUnknownKey() throws Exception {
        mockMvc.perform(post("/key/EJECT_TAPE")).andExpect(status().isBadRequest());
    }

    @Test
    void reportsConflictWhenTheDeviceIsOffline() throws Exception {
        willThrow(new DeviceOfflineException("offline")).given(session).sendKey(any());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void reportsConflictWhenNoDeviceIsPaired() throws Exception {
        given(sessions.active()).willReturn(Optional.empty());

        mockMvc.perform(post("/key/HOME")).andExpect(status().isConflict());
    }

    @Test
    void launchesAnAppByItsCatalogId() throws Exception {
        given(apps.byId("netflix")).willReturn(
                Optional.of(new AppEntry("netflix", "Netflix", "com.netflix.ninja", null)));

        mockMvc.perform(post("/apps/netflix/launch")).andExpect(status().isNoContent());

        verify(session).launchAppLink("market://launch?id=com.netflix.ninja");
    }

    @Test
    void returnsNotFoundForAnUnknownApp() throws Exception {
        given(apps.byId("betamax")).willReturn(Optional.empty());

        mockMvc.perform(post("/apps/betamax/launch")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 6: Run it and watch it fail**

Run: `./gradlew test --tests '*RemoteControllerTest'`
Expected: FAIL — `RemoteController` and `DeviceStateBroadcaster` do not exist.

- [ ] **Step 7: Write `DeviceStateBroadcaster` and `StateController`**

```java
package dev.andre.shield.web;

import dev.andre.shield.device.DeviceStateChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Fans device state changes out to every open browser tab. */
@Component
public class DeviceStateBroadcaster {

    private static final long NO_TIMEOUT = 0L;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(error -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    @EventListener
    public void onStateChanged(DeviceStateChangedEvent event) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("state").data(event.state()));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }
}
```

```java
package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
public class StateController {

    private final DeviceStateBroadcaster broadcaster;
    private final DeviceSessionManager sessions;

    public StateController(DeviceStateBroadcaster broadcaster, DeviceSessionManager sessions) {
        this.broadcaster = broadcaster;
        this.sessions = sessions;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        SseEmitter emitter = broadcaster.subscribe();
        // Send the current state immediately so a new tab is not blank until something changes.
        emitter.send(SseEmitter.event().name("state").data(sessions.state()));
        return emitter;
    }
}
```

- [ ] **Step 8: Write `RemoteController`**

```java
package dev.andre.shield.web;

import dev.andre.shield.apps.AppCatalog;
import dev.andre.shield.apps.AppEntry;
import dev.andre.shield.device.DeviceOfflineException;
import dev.andre.shield.device.DeviceSession;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.protocol.RemoteKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.stereotype.Controller;

import java.util.Locale;
import java.util.Optional;

@Controller
public class RemoteController {

    private final DeviceSessionManager sessions;
    private final AppCatalog apps;

    public RemoteController(DeviceSessionManager sessions, AppCatalog apps) {
        this.sessions = sessions;
        this.apps = apps;
    }

    @GetMapping("/")
    public String remote(Model model) {
        model.addAttribute("state", sessions.state());
        model.addAttribute("device", sessions.activeDevice().orElse(null));
        model.addAttribute("apps", apps.entries());
        return "remote";
    }

    @PostMapping("/key/{key}")
    public ResponseEntity<Void> key(@PathVariable String key) {
        RemoteKey remoteKey;
        try {
            remoteKey = RemoteKey.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        session().sendKey(remoteKey);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apps/{id}/launch")
    public ResponseEntity<Void> launch(@PathVariable String id) {
        Optional<AppEntry> entry = apps.byId(id);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        session().launchAppLink(entry.get().launchUri());
        return ResponseEntity.noContent().build();
    }

    private DeviceSession session() {
        return sessions.active()
                .orElseThrow(() -> new DeviceOfflineException("No device is paired"));
    }

    /** The browser shows a toast; there is nothing to render. */
    @ExceptionHandler(DeviceOfflineException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public void offline() {
    }
}
```

- [ ] **Step 9: Write `SetupController`**

```java
package dev.andre.shield.web;

import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import dev.andre.shield.protocol.PairingResult;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;

@Controller
public class SetupController {

    private final MdnsDiscovery discovery;
    private final PairingService pairing;
    private final DeviceSessionManager sessions;

    public SetupController(MdnsDiscovery discovery, PairingService pairing,
                           DeviceSessionManager sessions) {
        this.discovery = discovery;
        this.pairing = pairing;
        this.sessions = sessions;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        model.addAttribute("discovered", discovery.devices());
        model.addAttribute("paired", sessions.activeDevice().orElse(null));
        model.addAttribute("awaitingCode", pairing.inProgress());
        return "setup";
    }

    @PostMapping("/setup/pair")
    public String pair(@RequestParam String host, @RequestParam(required = false) String name,
                       Model model) {
        try {
            pairing.begin(host, name);
            model.addAttribute("awaitingCode", true);
        } catch (IOException e) {
            model.addAttribute("error",
                    "Could not reach " + host + ": " + e.getMessage());
            model.addAttribute("awaitingCode", false);
        }
        model.addAttribute("discovered", discovery.devices());
        model.addAttribute("paired", sessions.activeDevice().orElse(null));
        return "setup";
    }

    @PostMapping("/setup/code")
    public String code(@RequestParam String code, Model model) {
        PairingResult result = pairing.submit(code);

        switch (result) {
            case PairingResult.Paired ignored -> {
                return "redirect:/";
            }
            case PairingResult.WrongCode ignored -> model.addAttribute("error",
                    "That code was not accepted. The device will show a new one — start again.");
            case PairingResult.Failed failed -> model.addAttribute("error", failed.reason());
        }

        model.addAttribute("awaitingCode", false);
        model.addAttribute("discovered", discovery.devices());
        model.addAttribute("paired", sessions.activeDevice().orElse(null));
        return "setup";
    }

    @PostMapping("/setup/forget")
    public String forget(@RequestParam String id) {
        sessions.forget(id);
        return "redirect:/setup";
    }
}
```

- [ ] **Step 10: Run the tests and watch them pass**

Run: `./gradlew test`
Expected: PASS. `RemoteControllerTest` needs `remote.html` to exist only for the `GET /` case, which this test does not exercise; the template arrives in Task 13.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: pairing service, remote controllers and SSE state stream"
```

---

### Task 13: The browser UI

**Files:**
- Create: `src/main/resources/templates/remote.html`
- Create: `src/main/resources/templates/setup.html`
- Create: `src/main/resources/static/app.css`
- Create: `src/main/resources/static/app.js`
- Create: `src/main/resources/static/vendor/htmx.min.js` (downloaded)
- Test: `src/test/java/dev/andre/shield/web/RemotePageTest.java`

**Interfaces:**
- Consumes: `RemoteController` and `SetupController` model attributes (`state`, `device`, `apps`, `discovered`, `paired`, `awaitingCode`, `error`).
- Produces: no Java API.

- [ ] **Step 1: Vendor htmx**

Downloaded rather than hot-linked so the UI works without internet access.

```bash
mkdir -p src/main/resources/static/vendor
curl -sSfL -o src/main/resources/static/vendor/htmx.min.js \
  https://unpkg.com/htmx.org@2.0.10/dist/htmx.min.js
test -s src/main/resources/static/vendor/htmx.min.js && echo "htmx vendored"
```

- [ ] **Step 2: Write the failing test**

`src/test/java/dev/andre/shield/web/RemotePageTest.java`:

```java
package dev.andre.shield.web;

import dev.andre.shield.apps.AppCatalog;
import dev.andre.shield.apps.AppEntry;
import dev.andre.shield.device.DeviceSessionManager;
import dev.andre.shield.device.DeviceState;
import dev.andre.shield.device.DeviceStatus;
import dev.andre.shield.device.PairingService;
import dev.andre.shield.discovery.MdnsDiscovery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RemoteController.class, SetupController.class})
class RemotePageTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeviceSessionManager sessions;

    @MockitoBean
    AppCatalog apps;

    @MockitoBean
    PairingService pairing;

    @MockitoBean
    MdnsDiscovery discovery;

    @MockitoBean
    DeviceStateBroadcaster broadcaster;

    @Test
    void rendersTheRemoteWithItsDpadAndAppGrid() throws Exception {
        given(sessions.state()).willReturn(new DeviceState(
                DeviceStatus.CONNECTED, true, "com.netflix.ninja", 12, 100, false, Instant.now()));
        given(sessions.activeDevice()).willReturn(Optional.empty());
        given(apps.entries()).willReturn(
                List.of(new AppEntry("netflix", "Netflix", "com.netflix.ninja", null)));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/key/DPAD_UP")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/apps/netflix/launch")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Netflix")));
    }

    @Test
    void rendersTheSetupPageWithDiscoveredDevicesAndManualEntry() throws Exception {
        given(discovery.devices()).willReturn(List.of(
                new dev.andre.shield.discovery.DiscoveredDevice("Living Room Shield", "192.168.1.50", 6466)));
        given(sessions.activeDevice()).willReturn(Optional.empty());
        given(pairing.inProgress()).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Living Room Shield")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"host\"")));
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew test --tests '*RemotePageTest'`
Expected: FAIL — the templates do not exist.

- [ ] **Step 4: Write `templates/remote.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
    <title>Shield Remote</title>
    <link rel="stylesheet" th:href="@{/app.css}">
    <script th:src="@{/vendor/htmx.min.js}" defer></script>
    <script th:src="@{/app.js}" defer></script>
</head>
<body>
<header>
    <span id="status" class="badge" th:classappend="${state.connected()} ? 'ok' : 'off'"
          th:text="${state.status()}">DISCONNECTED</span>
    <span id="current-app" th:text="${state.currentApp()} ?: 'Nothing playing'">Nothing playing</span>
    <span id="volume" th:text="${state.muted()} ? 'muted' : ('vol ' + ${state.volumeLevel()})">vol</span>
    <a th:href="@{/setup}">Setup</a>
</header>

<main hx-swap="none">
    <section class="dpad">
        <button hx-post="/key/DPAD_UP" class="up">▲</button>
        <button hx-post="/key/DPAD_LEFT" class="left">◀</button>
        <button hx-post="/key/DPAD_CENTER" class="ok">OK</button>
        <button hx-post="/key/DPAD_RIGHT" class="right">▶</button>
        <button hx-post="/key/DPAD_DOWN" class="down">▼</button>
    </section>

    <section class="row">
        <button hx-post="/key/BACK">Back</button>
        <button hx-post="/key/HOME">Home</button>
        <button hx-post="/key/MENU">Menu</button>
        <button hx-post="/key/POWER">Power</button>
    </section>

    <section class="row">
        <button hx-post="/key/MEDIA_PREVIOUS">⏮</button>
        <button hx-post="/key/REWIND">⏪</button>
        <button hx-post="/key/PLAY_PAUSE">⏯</button>
        <button hx-post="/key/FAST_FORWARD">⏩</button>
        <button hx-post="/key/MEDIA_NEXT">⏭</button>
    </section>

    <section class="row">
        <button hx-post="/key/VOLUME_DOWN">Vol −</button>
        <button hx-post="/key/VOLUME_MUTE">Mute</button>
        <button hx-post="/key/VOLUME_UP">Vol +</button>
    </section>

    <section class="apps">
        <button th:each="app : ${apps}"
                th:hx-post="@{/apps/{id}/launch(id=${app.id()})}"
                th:text="${app.name()}">App
        </button>
    </section>
</main>

<div id="toast" hidden></div>
</body>
</html>
```

- [ ] **Step 5: Write `templates/setup.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Shield Remote · Setup</title>
    <link rel="stylesheet" th:href="@{/app.css}">
</head>
<body>
<header>
    <a th:href="@{/}">← Remote</a>
</header>

<main class="setup">
    <p class="error" th:if="${error}" th:text="${error}">Something went wrong</p>

    <section th:if="${awaitingCode}">
        <h2>Enter the code shown on your TV</h2>
        <form method="post" th:action="@{/setup/code}">
            <input name="code" maxlength="6" minlength="6" autocomplete="off"
                   autofocus placeholder="A1B2C3" required>
            <button type="submit">Pair</button>
        </form>
    </section>

    <section th:unless="${awaitingCode}">
        <h2>Devices on this network</h2>
        <p th:if="${#lists.isEmpty(discovered)}">
            Nothing found. If this app runs in Docker, mDNS needs
            <code>network_mode: host</code> — otherwise enter the address below.
        </p>
        <ul>
            <li th:each="device : ${discovered}">
                <form method="post" th:action="@{/setup/pair}">
                    <input type="hidden" name="host" th:value="${device.host()}">
                    <input type="hidden" name="name" th:value="${device.name()}">
                    <span th:text="${device.name()} + ' (' + ${device.host()} + ')'">Shield</span>
                    <button type="submit">Pair</button>
                </form>
            </li>
        </ul>

        <h2>Or enter an address</h2>
        <form method="post" th:action="@{/setup/pair}">
            <input name="host" placeholder="192.168.1.50" required>
            <input name="name" placeholder="Living Room Shield">
            <button type="submit">Pair</button>
        </form>
    </section>

    <section th:if="${paired}">
        <h2>Paired device</h2>
        <p th:text="${paired.name()} + ' at ' + ${paired.host()}">Shield</p>
        <form method="post" th:action="@{/setup/forget}">
            <input type="hidden" name="id" th:value="${paired.id()}">
            <button type="submit">Forget this device</button>
        </form>
    </section>
</main>
</body>
</html>
```

- [ ] **Step 6: Write `static/app.css`**

```css
:root { color-scheme: dark; --bg: #14161a; --fg: #e8e8ea; --btn: #23262d; --accent: #3d7dff; }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg);
       font: 16px/1.4 system-ui, -apple-system, sans-serif; }
header { display: flex; gap: 1rem; align-items: center; padding: .75rem 1rem;
         border-bottom: 1px solid #2a2d34; }
header a { color: var(--accent); margin-left: auto; }
.badge { padding: .15rem .5rem; border-radius: 999px; font-size: .75rem; background: #3a2020; }
.badge.ok { background: #1f3a24; }
main { max-width: 30rem; margin: 0 auto; padding: 1rem; }
button { background: var(--btn); color: var(--fg); border: 1px solid #313640;
         border-radius: .6rem; padding: .9rem; font-size: 1rem; cursor: pointer;
         touch-action: manipulation; }
button:active { background: var(--accent); }
.dpad { display: grid; grid-template-columns: repeat(3, 1fr); gap: .5rem; margin-bottom: 1rem; }
.dpad .up { grid-area: 1 / 2; } .dpad .left { grid-area: 2 / 1; }
.dpad .ok { grid-area: 2 / 2; } .dpad .right { grid-area: 2 / 3; }
.dpad .down { grid-area: 3 / 2; }
.row { display: flex; gap: .5rem; margin-bottom: .75rem; }
.row button { flex: 1; }
.apps { display: grid; grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr)); gap: .5rem; }
.setup form { display: flex; gap: .5rem; align-items: center; margin: .5rem 0; }
.setup input { flex: 1; padding: .7rem; border-radius: .5rem; border: 1px solid #313640;
               background: #1b1e24; color: var(--fg); }
.error { background: #3a2020; padding: .75rem; border-radius: .5rem; }
#toast { position: fixed; bottom: 1rem; left: 50%; transform: translateX(-50%);
         background: #3a2020; padding: .75rem 1rem; border-radius: .5rem; }
```

- [ ] **Step 7: Write `static/app.js`**

```javascript
// Live device state. The server pushes on every change; a new tab gets the current
// state immediately on connect.
const source = new EventSource("/events");

source.addEventListener("state", (event) => {
    const state = JSON.parse(event.data);

    const status = document.getElementById("status");
    status.textContent = state.status;
    status.classList.toggle("ok", state.status === "CONNECTED");
    status.classList.toggle("off", state.status !== "CONNECTED");

    document.getElementById("current-app").textContent = state.currentApp || "Nothing playing";
    document.getElementById("volume").textContent =
        state.muted ? "muted" : "vol " + state.volumeLevel;
});

// A rejected command means the device is offline; htmx fires this on any non-2xx.
document.body.addEventListener("htmx:responseError", () => {
    const toast = document.getElementById("toast");
    toast.textContent = "The device is not connected";
    toast.hidden = false;
    setTimeout(() => (toast.hidden = true), 2000);
});

// Keyboard control for desktop use.
const KEYS = {
    ArrowUp: "DPAD_UP", ArrowDown: "DPAD_DOWN", ArrowLeft: "DPAD_LEFT",
    ArrowRight: "DPAD_RIGHT", Enter: "DPAD_CENTER", Backspace: "BACK",
    " ": "PLAY_PAUSE", h: "HOME", m: "VOLUME_MUTE",
};

document.addEventListener("keydown", (event) => {
    if (event.target.tagName === "INPUT") return;
    const key = KEYS[event.key];
    if (!key) return;
    event.preventDefault();
    fetch("/key/" + key, { method: "POST" });
});
```

- [ ] **Step 8: Run the tests and watch them pass**

Run: `./gradlew test --tests '*RemotePageTest'`
Expected: PASS — both tests.

- [ ] **Step 9: Verify it by eye**

```bash
./gradlew bootRun
```

Open `http://localhost:8080/setup`. With no device paired, the page must show the manual address form and the Docker/mDNS hint. `http://localhost:8080/` must render the D-pad; buttons will toast "The device is not connected" until pairing happens.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: browser remote and setup pages"
```

---

### Task 14: Docker packaging and documentation

**Files:**
- Create: `Dockerfile`
- Create: `compose.yaml`
- Create: `.dockerignore`
- Create: `README.md`
- Modify: `.gitignore` — add `data/`

**Interfaces:**
- Consumes: the built application.
- Produces: a runnable container image.

- [ ] **Step 1: Write the `Dockerfile`**

```dockerfile
FROM gradle:jdk25 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon bootJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
VOLUME /data
ENV SHIELD_DATA_DIR=/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--shield.data-dir=/data"]
```

- [ ] **Step 2: Write `.dockerignore`**

```
.git
.gradle
build
data
docs
```

- [ ] **Step 3: Write `compose.yaml`**

```yaml
services:
  shield-remote:
    build: .
    # Host networking is REQUIRED for mDNS discovery: multicast does not cross a
    # bridge network. On bridge networking the app still works, but the device must
    # be added by address on the setup page.
    network_mode: host
    volumes:
      - ./data:/data
    environment:
      SHIELD_KEYSTORE_PASSWORD: change-me
    restart: unless-stopped

# Bridge-mode alternative (no discovery, manual address entry only):
#
#  shield-remote:
#    build: .
#    ports:
#      - "8080:8080"
#    volumes:
#      - ./data:/data
```

- [ ] **Step 4: Add `data/` to `.gitignore`**

```bash
printf 'data/\n' >> .gitignore
```

- [ ] **Step 5: Write `README.md`**

````markdown
# Shield Web Remote

A small Spring Boot web app that controls an NVIDIA Shield (or any Android TV device)
on the same network: a browser remote, an app launcher, and live device state.

## Running

```bash
docker compose up --build
```

Then open `http://<host>:8080`.

## Pairing

1. Open `/setup`.
2. Pick your Shield from the discovered list, or type its IP address.
3. The TV displays a six character code. Type it in and submit.

The app stores a client certificate in `data/keystore.p12`. **That certificate is the
pairing credential** — delete it and you must pair again. `data/devices.json` holds the
device list and `data/apps.yaml` the launcher entries, which you can edit freely.

## Discovery does not work

mDNS is multicast and does not cross a Docker bridge network. Either run with
`network_mode: host` as the bundled compose file does, or add the device by address.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `shield.data-dir` | `/data` in Docker | Where the keystore, registry and app catalog live |
| `SHIELD_KEYSTORE_PASSWORD` | `shield` | Keystore password |
| `shield.discovery-enabled` | `true` | Turn mDNS off entirely |
| `shield.stale-timeout-seconds` | `10` | No inbound message for this long means the connection is dead |
| `shield.reconnect-max-delay-seconds` | `60` | Upper bound on reconnect backoff |

## Security

There is no authentication: anyone who can reach the port can control the TV. This is
deliberate for a LAN-only tool. Do not expose it to the internet without putting an
authenticating reverse proxy in front of it.
````

- [ ] **Step 6: Verify the image builds and runs**

```bash
docker compose build
docker compose up -d
curl -sf http://localhost:8080/setup > /dev/null && echo "setup page served"
docker compose down
```

Expected: `setup page served`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Docker packaging and documentation"
```

---

## Manual verification against the real Shield

Everything above passes without hardware. These steps are the real gate, and the point
where a protocol mistake would surface. Run them in order.

- [ ] **1. Discovery.** Start the app on the same LAN as the Shield with host networking. The Shield appears on `/setup` within a few seconds. If not, confirm with `avahi-browse -rt _androidtvremote2._tcp` that the device advertises at all, then fall back to manual entry — a discovery failure must not block the rest.
- [ ] **2. Pairing.** Click Pair. **The TV must display a six character code within a second or two.** If the code never appears, the handshake is failing before the configuration step: log the `PairingMessage` exchange and compare against spec §5.2.
- [ ] **3. The code.** Enter it. Success means `PairingSecretAck` and a redirect to the remote. If the code is rejected despite being typed correctly, the digest inputs are wrong — check modulus sign-byte stripping first (spec §5.2), and confirm `PairingDigestTest` still passes.
- [ ] **4. Command channel.** The status badge turns CONNECTED on its own. The current app appears when you switch apps on the TV.
- [ ] **5. Keys.** D-pad, OK, Back, Home each move the TV UI. Latency should be imperceptible on a LAN.
- [ ] **6. Volume.** Volume up/down changes the TV volume and the header updates without a page reload — that proves the SSE path end to end.
- [ ] **7. Apps.** Launch Netflix from the grid. If it does nothing, the package name in `apps.yaml` is wrong for your device; check with the current-app display by opening the app on the TV.
- [ ] **8. Power.** Press Power; the Shield sleeps. Press it again; it wakes. The badge should stay CONNECTED through standby.
- [ ] **9. Reconnect.** Pull the Shield's network for 30 seconds. The badge goes DISCONNECTED and returns to CONNECTED on its own once the device is back.
- [ ] **10. Restart.** `docker compose restart`. The app reconnects using the stored certificate without asking to pair again — this proves the volume mount and keystore work.

## What is deliberately not built

Multi-device switching in the UI, Wake-on-LAN, text input to the TV, and any form of
authentication. See spec §12.
