package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretStoreTest {

    @TempDir
    Path dir;

    private SecretStore store(String passphrase) {
        return new SecretStore(dir.resolve("secrets.json"),
                new SecretKeySource(passphrase, dir.resolve("secret.key"), new SecureRandom()), new SecureRandom());
    }

    private static Path fixture(String name) {
        return Path.of("src/test/resources/fixtures/secrets").resolve(name);
    }

    private void copyFixture(String name, String target) throws IOException {
        Files.copy(fixture(name), dir.resolve(target), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String nonceOf(String json) {
        return JsonMapper.builder().build().readTree(json).path("nonce").asString("");
    }

    @Test
    void anEmptyDataDirectoryHasNoSecretsAndCreatesNoFiles() throws Exception {
        SecretStore store = store(null);

        assertThat(store.hasSecrets()).isFalse();
        assertThat(store.login()).isEmpty();
        try (var files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void readsTheKeyFileFixture() throws Exception {
        copyFixture("secret.key", "secret.key");
        copyFixture("secrets-keyfile-v1.json", "secrets.json");

        SecretStore store = store(null);

        assertThat(store.secret("jellyfin.token")).contains("0123456789abcdef0123456789abcdef");
        assertThat(store.login()).get().extracting(LoginCredential::version).isEqualTo("fixture-1");
    }

    @Test
    void readsThePassphraseFixture() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");

        assertThat(store("correct horse battery staple").secret("jellyfin.token"))
                .contains("0123456789abcdef0123456789abcdef");
    }

    @Test
    void theFirstSecretsAreWrittenTogetherWithTheLoginAndEncrypted() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("jellyfin.token", "tok-123456"), new LoginCredential("$argon2id$hash", "v1"));

        String onDisk = Files.readString(dir.resolve("secrets.json"));
        assertThat(JsonMapper.builder().build().readTree(onDisk).path("format").asString("")).isEqualTo("home-control-secrets");
        assertThat(onDisk).doesNotContain("tok-123456").doesNotContain("argon2id$hash");
        assertThat(Files.getPosixFilePermissions(dir.resolve("secrets.json"))).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(dir.resolve("secret.key"))).containsExactlyInAnyOrder(OWNER_READ, OWNER_WRITE);
        assertThat(Base64.getDecoder().decode(Files.readString(dir.resolve("secret.key")).strip())).hasSize(32);
        SecretStore reopened = store(null);
        assertThat(reopened.secret("jellyfin.token")).contains("tok-123456");
        assertThat(reopened.login()).contains(new LoginCredential("$argon2id$hash", "v1"));
    }

    @Test
    void secretsAreNeverStoredWithoutALogin() {
        SecretStore store = store(null);

        assertThatThrownBy(() -> store.putSecrets(Map.of("jellyfin.token", "x")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.resolve("secrets.json"))).isFalse();
    }

    @Test
    void theFirstSecretsCannotReplaceAnExistingLogin() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));

        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("b", "2"), new LoginCredential("h2", "v2")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.login()).contains(new LoginCredential("h", "v1"));
    }

    @Test
    void removingTheLastSecretRemovesTheLogin() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1", "b", "2"), new LoginCredential("h", "v1"));

        store.removeSecrets(List.of("a"));
        assertThat(store.login()).isPresent();
        store.removeSecrets(List.of("b"));

        assertThat(store.hasSecrets()).isFalse();
        assertThat(store(null).login()).isEmpty();
    }

    @Test
    void eachWriteUsesAFreshNonce() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));
        String first = Files.readString(dir.resolve("secrets.json"));
        store.putSecrets(Map.of("a", "1"));

        assertThat(nonceOf(Files.readString(dir.resolve("secrets.json")))).isNotEqualTo(nonceOf(first));
    }

    @Test
    void aWrongPassphraseIsANamedStorageErrorNotAnEmptyStore() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");

        assertThatThrownBy(() -> store("wrong horse"))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("HOME_CONTROL_SECRET is not the value");
    }

    @Test
    void aMissingPassphraseOrKeyFileIsNamed() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");
        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("HOME_CONTROL_SECRET, which is not set");

        copyFixture("secrets-keyfile-v1.json", "secrets.json");
        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("secret.key").hasMessageContaining("missing");
    }

    @Test
    void aTamperedCiphertextIsRejected() throws Exception {
        copyFixture("secret.key", "secret.key");
        String json = Files.readString(fixture("secrets-keyfile-v1.json")).replace("\"brOo", "\"brOp");
        Files.writeString(dir.resolve("secrets.json"), json);

        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessageContaining("could not be decrypted");
    }

    @Test
    void aKeyFileStoreIsReEncryptedOnceAPassphraseIsConfigured() throws Exception {
        copyFixture("secret.key", "secret.key");
        copyFixture("secrets-keyfile-v1.json", "secrets.json");

        store("a brand new passphrase");

        assertThat(JsonMapper.builder().build().readTree(Files.readString(dir.resolve("secrets.json")))
                .path("key").path("source").asString("")).isEqualTo("HOME_CONTROL_SECRET");
        Files.delete(dir.resolve("secret.key"));
        assertThat(store("a brand new passphrase").secret("jellyfin.token")).contains("0123456789abcdef0123456789abcdef");
    }

    @Test
    void tamperedKdfCostsAreRefusedBeforeDeriving() throws Exception {
        String json = Files.readString(fixture("secrets-passphrase-v1.json")).replace("\"memoryKiB\" : 19456", "\"memoryKiB\" : 99999999");
        Files.writeString(dir.resolve("secrets.json"), json);

        assertThatThrownBy(() -> store("correct horse battery staple")).isInstanceOf(StorageException.class);
    }

    @Test
    void rejectsBadSecretNamesAndValues() {
        SecretStore store = store(null);
        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("Bad Name", "x"), new LoginCredential("h", "v")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putFirstSecrets(Map.of("ok", ""), new LoginCredential("h", "v")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginCredentialsNeverPrintTheirHash() {
        assertThat(new LoginCredential("$argon2id$secret-hash", "v9").toString()).doesNotContain("secret-hash").contains("v9");
    }

    @Test
    void storageErrorsNeverCarryTheSecretValues() throws Exception {
        copyFixture("secrets-passphrase-v1.json", "secrets.json");

        assertThatThrownBy(() -> store("wrong horse"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("wrong horse"));
        assertThatThrownBy(() -> store(null).putFirstSecrets(Map.of("ok", "x".repeat(16_385)), new LoginCredential("h", "v")))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("xxxx"));
    }

    @Test
    void writesLeaveNoTemporaryFilesBehind() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));
        store.putSecrets(Map.of("b", "2"));
        store.replaceLogin(new LoginCredential("h2", "v2"));

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("secrets.json", "secret.key");
        }
    }
}
