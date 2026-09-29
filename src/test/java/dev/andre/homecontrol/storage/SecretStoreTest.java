package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    void accountCredentialsAreNeverStoredWithoutALogin() {
        SecretStore store = store(null);

        var secrets = Map.of("jellyfin.token", "x");
        assertThatThrownBy(() -> store.putSecrets(secrets))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(dir.resolve("secrets.json"))).isFalse();
    }

    @Test
    void theFirstSecretsCannotReplaceAnExistingLogin() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));

        var moreSecrets = Map.of("b", "2");
        var secondCredential = new LoginCredential("h2", "v2");
        assertThatThrownBy(() -> store.putFirstSecrets(moreSecrets, secondCredential))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.login()).contains(new LoginCredential("h", "v1"));
    }

    @Test
    void theLoginSurvivesTheLastSecret() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1", "b", "2"), new LoginCredential("h", "v1"));

        store.removeSecrets(List.of("a"));
        store.removeSecrets(List.of("b"));

        assertThat(store.hasSecrets()).isFalse();
        assertThat(store(null).login()).contains(new LoginCredential("h", "v1"));
    }

    @Test
    void aDeviceSecretNeedsNoLogin() {
        SecretStore store = store(null);

        store.putDeviceSecret("device.webos.0123456789abcdef.client-key", "k");

        assertThat(store(null).deviceSecret("device.webos.0123456789abcdef.client-key")).contains("k");
        assertThat(store(null).login()).isEmpty();
        assertThat(store(null).hasAccountCredentials()).isFalse();
    }

    @Test
    void anAccountCredentialStillNeedsALoginBesideDeviceSecrets() {
        SecretStore store = store(null);
        store.putDeviceSecret("device.androidtv.keystore-password", "p");
        var credential = Map.of("jellyfin.token", "t");

        assertThatThrownBy(() -> store.putSecrets(credential)).isInstanceOf(IllegalStateException.class);
        store.putFirstSecrets(credential, new LoginCredential("h", "v1"));

        assertThat(store(null).accountCredentialNames()).containsExactly("jellyfin.token");
        assertThat(store(null).deviceSecret("device.androidtv.keystore-password")).contains("p");
    }

    @Test
    void deviceSecretMethodsRefuseOtherNames() {
        SecretStore store = store(null);
        List<String> names = List.of("jellyfin.token");

        assertThatThrownBy(() -> store.putDeviceSecret("jellyfin.token", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deviceSecret("jellyfin.token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeDeviceSecrets(names)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoginIsSetAndRemovedOnPurpose() {
        SecretStore store = store(null);
        var second = new LoginCredential("h2", "v2");

        store.setLogin(new LoginCredential("h", "v1"));
        assertThat(store(null).login()).isPresent();
        assertThatThrownBy(() -> store.setLogin(second)).isInstanceOf(IllegalStateException.class);

        store.putSecrets(Map.of("tmdb.credential", "c"));
        assertThatThrownBy(store::removeLogin).isInstanceOf(IllegalStateException.class);

        store.removeSecrets(List.of("tmdb.credential"));
        store.removeLogin();
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
        var badName = Map.of("Bad Name", "x");
        var credential = new LoginCredential("h", "v");
        assertThatThrownBy(() -> store.putFirstSecrets(badName, credential))
                .isInstanceOf(IllegalArgumentException.class);
        var emptyValue = Map.of("ok", "");
        assertThatThrownBy(() -> store.putFirstSecrets(emptyValue, credential))
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

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"format\" : \"home-control-secrets\"|\"format\" : \"something-else\"",
            "\"version\" : 1|\"version\" : 2",
            "\"cipher\" : \"AES-256-GCM\"|\"cipher\" : \"AES-128-CBC\""})
    void aFileFromAnotherFormatVersionOrCipherIsNamed(String original, String replacement) throws Exception {
        copyFixture("secret.key", "secret.key");
        String json = Files.readString(fixture("secrets-keyfile-v1.json"));
        assertThat(json).contains(original);
        Files.writeString(dir.resolve("secrets.json"), json.replace(original, replacement));

        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessage(dir.resolve("secrets.json") + " is not a version 1 Home Control secrets file");
    }

    @Test
    void aFileThatIsNotJsonOrHoldsInvalidBase64IsNamed() throws Exception {
        copyFixture("secret.key", "secret.key");
        String fixture = Files.readString(fixture("secrets-keyfile-v1.json"));
        for (String json : new String[]{"{ not json", "[]", fixture.replace("\"JCQkJCQkJCQkJCQk\"", "\"***\""),
                fixture.replace("\"brOo", "\"%%%")}) {
            Files.writeString(dir.resolve("secrets.json"), json);

            assertThatThrownBy(() -> store(null)).as(json).isInstanceOf(StorageException.class)
                    .hasMessageContaining(dir.resolve("secrets.json").toString())
                    .hasMessageContaining("Home Control secrets file");
        }
    }

    @Test
    void aNonceOfTheWrongLengthIsRefused() throws Exception {
        copyFixture("secret.key", "secret.key");
        String json = Files.readString(fixture("secrets-keyfile-v1.json")).replace("\"JCQkJCQkJCQkJCQk\"", "\"JCQk\"");
        Files.writeString(dir.resolve("secrets.json"), json);

        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessage(dir.resolve("secrets.json") + " has an invalid nonce");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"kdf\" : \"argon2id\"|\"kdf\" : \"argon2i\"",
            "\"memoryKiB\" : 19456|\"memoryKiB\" : 7",
            "\"memoryKiB\" : 19456, \"iterations\" : 2, \"parallelism\" : 1|\"memoryKiB\" : 16, \"iterations\" : 2, \"parallelism\" : 3",
            "\"iterations\" : 2|\"iterations\" : 0",
            "\"iterations\" : 2|\"iterations\" : 11",
            "\"parallelism\" : 1|\"parallelism\" : 0",
            "\"parallelism\" : 1|\"parallelism\" : 9",
            "\"salt\" : \"AAECAwQFBgcICQoLDA0ODw==\"|\"salt\" : \"AAECAwQFBgcICQoLDA0O\""})
    void everyKeyDerivationParameterIsCheckedBeforeDeriving(String original, String replacement) throws Exception {
        String json = Files.readString(fixture("secrets-passphrase-v1.json"));
        assertThat(json).contains(original);
        Files.writeString(dir.resolve("secrets.json"), json.replace(original, replacement));

        assertThatThrownBy(() -> store("correct horse battery staple")).isInstanceOf(StorageException.class)
                .hasMessage(dir.resolve("secrets.json") + " has invalid key derivation parameters");
    }

    @Test
    void decryptedContentThatIsNotJsonIsRefusedWithoutQuotingIt() throws Exception {
        copyFixture("secret.key", "secret.key");
        byte[] key = Base64.getDecoder().decode(Files.readString(dir.resolve("secret.key")).strip());
        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD("home-control/secrets/v1".getBytes(StandardCharsets.US_ASCII));
        byte[] ciphertext = cipher.doFinal("{\"secrets\": token-abc".getBytes(StandardCharsets.UTF_8));
        Files.writeString(dir.resolve("secrets.json"), """
                {"format": "home-control-secrets", "version": 1, "key": {"source": "secret.key"},
                 "cipher": "AES-256-GCM", "nonce": "%s", "ciphertext": "%s"}
                """.formatted(Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(ciphertext)));

        assertThatThrownBy(() -> store(null)).isInstanceOf(StorageException.class)
                .hasMessage(dir.resolve("secrets.json") + " was decrypted but its content is not valid")
                .hasNoCause();
    }

    @Test
    void anUnwritableSecretsFileKeepsTheStoreUnchanged() throws Exception {
        Files.writeString(dir.resolve("not-a-directory"), "x");
        SecretStore store = new SecretStore(dir.resolve("not-a-directory/secrets.json"),
                new SecretKeySource(null, dir.resolve("secret.key"), new SecureRandom()), new SecureRandom());
        var secrets = Map.of("jellyfin.token", "token-abc");
        var credential = new LoginCredential("h", "v1");

        assertThatThrownBy(() -> store.putFirstSecrets(secrets, credential)).isInstanceOf(StorageException.class)
                .hasMessageContaining("check that /data is bind-mounted and writable")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("token-abc"));
        assertThat(store.hasSecrets()).isFalse();
        assertThat(store.login()).isEmpty();
    }

    @Test
    void refusesMissingSecretsOrLoginAndAnImpossibleReplacement() {
        SecretStore store = store(null);
        var credential = new LoginCredential("h", "v1");
        var secrets = Map.of("a", "1");
        Map<String, String> none = Map.of();

        assertThatThrownBy(() -> store.putFirstSecrets(none, credential))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No secrets given");
        assertThatThrownBy(() -> store.putFirstSecrets(null, credential))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No secrets given");
        assertThatThrownBy(() -> store.putFirstSecrets(secrets, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A login is required");
        assertThatThrownBy(() -> store.replaceLogin(credential))
                .isInstanceOf(IllegalStateException.class).hasMessage("There is no login to replace");

        store.putFirstSecrets(secrets, credential);
        assertThatThrownBy(() -> store.replaceLogin(null))
                .isInstanceOf(IllegalStateException.class).hasMessage("There is no login to replace");
    }

    @Test
    void removingSecretsThatDoNotExistWritesNothing() throws Exception {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));
        String before = Files.readString(dir.resolve("secrets.json"));

        store.removeSecrets(List.of("b"));

        assertThat(Files.readString(dir.resolve("secrets.json"))).isEqualTo(before);
        assertThat(store.names()).containsExactly("a");
    }
}
