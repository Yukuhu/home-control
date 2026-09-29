# Phase 2B: Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put every file under `/data` on one durable writer and one versioned, cached JSON layer. Type the source
settings, encrypt the TV pairing keys, generate the keystore password, and let the household set and remove the login
explicitly.

**Architecture:**

- `storage.AtomicFiles` is the one durable writer.
- `storage.VersionedJsonFile<T>` gives each JSON store its version, migrations, cached snapshot and single write path.
  Each store holds one.
- `core.DeviceSecrets`, implemented by `SecretStore`, is how adapters keep credentials without the login.
- `DeviceAdapter` gains a `validate` and a `migrate` hook, which `DeviceManager.start()` calls.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), JUnit 5, AssertJ, Mockito, Thymeleaf.

**Spec:** `docs/superpowers/specs/2026-09-29-phase-2b-storage-design.md`

## Global Constraints

- **Build and test.**
  - `scripts/gradle.sh` runs Gradle in Docker. It is quiet, so read `build/test-results/test/*.xml` for results.
  - The workspace's `gradle-summary.sh` prints a pass/fail summary of those files.
  - Done means `scripts/gradle.sh build` is green.
- **Existing installs upgrade in place.**
  - Every `/data` format change migrates on read and has a test that loads the previous format.
  - The original is kept once as `<name>.v<n>.json`.
- **Secrets.** They are encrypted at rest and never reach the browser. No message or log line carries a secret, a key
  or a password.
- **Names that never change:** the CasaOS app id `dev.andre.shield-remote`, the client package `dev.andre.shield`,
  `shield.*` and `SHIELD_KEYSTORE_PASSWORD`.
- **ArchUnit.**
  - Frozen violations only fall. When one is fixed, commit the smaller store in `src/test/archunit-store`.
  - Never refreeze.
- **Commits.**
  - Conventional Commits.
  - Stage only your own paths (`git add <paths>`).
  - End every message with:

    ```
    Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
    Claude-Session: https://claude.ai/code/session_01Q6dsbK16wqsU1DrkTAV32K
    ```
- **Tests.** They sit in the package of the code they test. Shared helpers go in
  `src/test/java/dev/andre/homecontrol/testsupport/`.
- **Messages in this spec, verbatim.**
  - unreadable: `Could not read <description> in <file>; fix or delete it`
  - newer: `<description> in <file> was written by a newer Home Control (version N); upgrade Home Control or restore a
    backup`
  - keystore: `keystore.p12 does not open with the stored password or an old default; set
    home-control.androidtv.keystore-password to the password it was created with, or delete keystore.p12 and pair the
    Android TV devices again`

## Review Focus

1. **Crashes between two writes.**
   - Keystore: the password is stored, but the keystore is still under `shield`.
   - webOS: the secret is stored, but the registry still holds the key.

   The next start must recover in both cases, with no lost pairing. Tests: Task 11
   (`aStoredPasswordWhoseKeystoreIsStillUnderTheOldDefaultIsReprotected`) and Task 10
   (`migrationIsIdempotent`).
2. **A cached snapshot handed out and then mutated by a caller.** Every `T` a `VersionedJsonFile` holds must be
   immutable, because a caller's change must not reach the cache without a write. Test: Task 2
   (`theSnapshotIsTheValueLastWritten`), plus immutable copies in every reader.
3. **`removePassword` racing a newly stored account credential.** Nothing must end up protecting an account credential
   without a login. Test: Task 8 (`removingThePasswordIsRefusedOnceAnAccountIsConnectedMeanwhile`).
4. **A v1 `sources.json` whose flat section is incomplete** (a Jellyfin section without `userId`). It must read as "not
   connected", not fail startup. Test: Task 7 (`anIncompleteVersionOneSectionReadsAsNotConnected`).
5. **A file of an older version with no migration step,** such as a hand-written `pinned.json` with `version: 0`. It
   must give the named "fix or delete it" error, not a stack trace. Test: Task 2 (`aVersionWithoutAStepIsUnreadable`).

---

### Task 1: `AtomicFiles`, the one durable writer

**Files:**
- Rename: `src/main/java/dev/andre/homecontrol/storage/OwnerOnlyFiles.java` → `storage/AtomicFiles.java` (public)
- Modify: `storage/SecretStore.java` (its one `OwnerOnlyFiles.write` call), `storage/SecretKeySource.java` (its one
  call)
- Create: `src/test/java/dev/andre/homecontrol/storage/AtomicFilesTest.java`

**Interfaces:**
- Produces: `public final class AtomicFiles` with
  - `public static void write(Path target, byte[] bytes, boolean replace) throws IOException`, whose temp file is
    named `"." + target.getFileName() + "-"`…`".tmp"` in the target's directory;
  - `public static Path createTemp(Path directory, String prefix) throws IOException`.

- [ ] **Step 1: Write the failing test**

```java
package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class AtomicFilesTest {

    @TempDir
    Path dir;

    @Test
    void writesAFileOnlyItsOwnerCanRead() throws Exception {
        assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        Path target = dir.resolve("devices.json");

        AtomicFiles.write(target, "[]".getBytes(), true);

        assertThat(target).hasContent("[]");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-------");
    }

    @Test
    void replacesAnExistingFileWhenAsked() throws Exception {
        Path target = dir.resolve("pinned.json");
        Files.writeString(target, "old");

        AtomicFiles.write(target, "new".getBytes(), true);

        assertThat(target).hasContent("new");
    }

    @Test
    void neverReplacesAnExistingFileOtherwise() throws Exception {
        Path target = dir.resolve("secret.key");
        Files.writeString(target, "first");

        assertThatThrownBy(() -> AtomicFiles.write(target, "second".getBytes(), false))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(target).hasContent("first");
    }

    @Test
    void leavesNoTempFileBehind() throws Exception {
        AtomicFiles.write(dir.resolve("sources.json"), "{}".getBytes(), true);
        try {
            AtomicFiles.write(dir.resolve("sources.json"), "{}".getBytes(), false);
        } catch (FileAlreadyExistsException _) {
            // expected: the target exists
        }

        try (var files = Files.list(dir)) {
            assertThat(files).extracting(path -> path.getFileName().toString()).containsExactly("sources.json");
        }
    }

    @Test
    void createsTheTargetsDirectory() throws Exception {
        Path target = dir.resolve("nested/devices.json");

        AtomicFiles.write(target, "[]".getBytes(), true);

        assertThat(target).hasContent("[]");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.AtomicFilesTest'`
Expected: compilation FAILS: `cannot find symbol: class AtomicFiles`.

- [ ] **Step 3: Rename and publish the class**

```bash
git mv src/main/java/dev/andre/homecontrol/storage/OwnerOnlyFiles.java src/main/java/dev/andre/homecontrol/storage/AtomicFiles.java
```

In `AtomicFiles.java`:
- change the class line to `public final class AtomicFiles {` and the constructor name to `AtomicFiles`;
- make the Javadoc `/** Durable, atomic, owner-only writes: every file under /data is written through here. */`;
- make both static methods `public`;
- give `write` the signature `write(Path target, byte[] bytes, boolean replace)`, creating its temp file with
  `createTemp(directory, "." + target.getFileName() + "-")`.

Then fix the two callers:
- `SecretStore.write`: `AtomicFiles.write(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root), true);`
- `SecretKeySource.createKeyFile`:
  `AtomicFiles.write(keyFile, (Base64.getEncoder().encodeToString(key) + "\n").getBytes(StandardCharsets.US_ASCII), false);`

Check that nothing else used `OwnerOnlyFiles`:
`grep -rn OwnerOnlyFiles src/` must print nothing.

- [ ] **Step 4: Run the storage tests**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.*'`
Expected: PASS, including the five new tests and the existing `SecretStoreTest` and `SecretKeySourceTest`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/storage/AtomicFiles.java src/main/java/dev/andre/homecontrol/storage/OwnerOnlyFiles.java \
  src/main/java/dev/andre/homecontrol/storage/SecretStore.java src/main/java/dev/andre/homecontrol/storage/SecretKeySource.java \
  src/test/java/dev/andre/homecontrol/storage/AtomicFilesTest.java
git commit -m "refactor: make the owner-only atomic writer the one writer for /data"
```

---

### Task 2: `VersionedJsonFile<T>`

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java`
- Create: `src/test/java/dev/andre/homecontrol/storage/VersionedJsonFileTest.java`
- Modify: `storage/StorageException.java` (add a message-only constructor)

**Interfaces:**
- Consumes: `AtomicFiles.write(Path, byte[], boolean)` (Task 1).
- Produces: `public final class VersionedJsonFile<T>` with
  - `VersionedJsonFile(Path file, String description, int version, Supplier<T> empty, Function<JsonNode, T> reader,
    Function<T, ObjectNode> writer)`;
  - `VersionedJsonFile<T> migrate(int from, UnaryOperator<JsonNode> step)`;
  - `VersionedJsonFile<T> versionOf(ToIntFunction<JsonNode> versionOf)`;
  - `static int versionField(JsonNode root)`: the `version` field, or `-1`;
  - `Path file()`, `synchronized T read()`, `synchronized T update(UnaryOperator<T> change)`,
    `synchronized void write(T value)` and `synchronized void delete()`.
- Produces: `StorageException(String message)`.

- [ ] **Step 1: Write the failing test**

```java
package dev.andre.homecontrol.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionedJsonFileTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    record Note(String text) {
    }

    @TempDir
    Path dir;

    private Path path() {
        return dir.resolve("notes.json");
    }

    private VersionedJsonFile<Note> notes() {
        return new VersionedJsonFile<>(path(), "the notes", 3, () -> new Note(""),
                root -> new Note(root.path("text").asString()),
                note -> MAPPER.createObjectNode().put("text", note.text()))
                .migrate(1, v1 -> MAPPER.createObjectNode().put("version", 2).put("body", v1.path("note").asString()))
                .migrate(2, v2 -> MAPPER.createObjectNode().put("version", 3).put("text", v2.path("body").asString()));
    }

    @Test
    void aMissingFileIsEmptyAndNotCreated() {
        assertThat(notes().read()).isEqualTo(new Note(""));
        assertThat(path()).doesNotExist();
    }

    @Test
    void anUpdateWritesTheVersionFirstAndIsReadBack() throws Exception {
        VersionedJsonFile<Note> notes = notes();

        notes.update(note -> new Note("milk"));

        JsonNode root = MAPPER.readTree(Files.readAllBytes(path()));
        assertThat(root.propertyNames()).containsExactly("version", "text");
        assertThat(root.path("version").asInt()).isEqualTo(3);
        assertThat(notes().read()).isEqualTo(new Note("milk"));
    }

    @Test
    void theSnapshotIsTheValueLastWritten() throws Exception {
        VersionedJsonFile<Note> notes = notes();
        notes.write(new Note("milk"));

        Files.writeString(path(), "{\"version\":3,\"text\":\"changed behind its back\"}");

        assertThat(notes.read()).isEqualTo(new Note("milk"));
    }

    @Test
    void anOlderVersionMigratesStepByStepAndKeepsItsOriginalOnce() throws Exception {
        Files.writeString(path(), "{\"version\":1,\"note\":\"bread\"}");

        assertThat(notes().read()).isEqualTo(new Note("bread"));

        assertThat(dir.resolve("notes.v1.json")).hasContent("{\"version\":1,\"note\":\"bread\"}");
        assertThat(MAPPER.readTree(Files.readAllBytes(path())).path("version").asInt()).isEqualTo(3);

        Files.writeString(path(), "{\"version\":1,\"note\":\"later\"}");
        notes().read();
        assertThat(dir.resolve("notes.v1.json")).hasContent("{\"version\":1,\"note\":\"bread\"}");
    }

    @Test
    void aNewerVersionIsRefusedByName() throws Exception {
        Files.writeString(path(), "{\"version\":4}");

        assertThatThrownBy(() -> notes().read())
                .isInstanceOf(StorageException.class)
                .hasMessage("the notes in " + path() + " was written by a newer Home Control (version 4);"
                        + " upgrade Home Control or restore a backup");
    }

    @Test
    void unreadableJsonNamesTheFile() throws Exception {
        Files.writeString(path(), "not json");

        assertThatThrownBy(() -> notes().read())
                .isInstanceOf(StorageException.class)
                .hasMessage("Could not read the notes in " + path() + "; fix or delete it");
    }

    @Test
    void aVersionWithoutAStepIsUnreadable() throws Exception {
        Files.writeString(path(), "{\"version\":0}");

        assertThatThrownBy(() -> notes().read())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("fix or delete it");
    }

    @Test
    void aReaderThatRejectsTheDocumentIsUnreadable() throws Exception {
        Files.writeString(path(), "{\"version\":3}");
        VersionedJsonFile<Note> strict = new VersionedJsonFile<>(path(), "the notes", 3, () -> new Note(""),
                root -> {
                    throw new IllegalArgumentException("text is required");
                }, note -> MAPPER.createObjectNode());

        assertThatThrownBy(strict::read).isInstanceOf(StorageException.class).hasMessageContaining("fix or delete it");
    }

    @Test
    void aCustomVersionLookupReadsFilesThatPredateTheField() throws Exception {
        Files.writeString(path(), "[\"eggs\"]");
        VersionedJsonFile<Note> notes = new VersionedJsonFile<>(path(), "the notes", 2, () -> new Note(""),
                root -> new Note(root.path("text").asString()),
                note -> MAPPER.createObjectNode().put("text", note.text()))
                .versionOf(root -> root.isArray() ? 1 : VersionedJsonFile.versionField(root))
                .migrate(1, array -> MAPPER.createObjectNode().put("text", array.path(0).asString()));

        assertThat(notes.read()).isEqualTo(new Note("eggs"));
        assertThat(dir.resolve("notes.v1.json")).hasContent("[\"eggs\"]");
    }

    @Test
    void deleteRemovesTheFileAndForgetsTheSnapshot() {
        VersionedJsonFile<Note> notes = notes();
        notes.write(new Note("milk"));

        notes.delete();

        assertThat(path()).doesNotExist();
        assertThat(notes.read()).isEqualTo(new Note(""));
    }

    @Test
    void writesAreOwnerOnly() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        notes().write(new Note("milk"));

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(path())))
                .isEqualTo("rw-------");
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.VersionedJsonFileTest'`
Expected: compilation FAILS: `cannot find symbol: class VersionedJsonFile`.

- [ ] **Step 3: Implement**

`StorageException` gains:

```java
    public StorageException(String message) {
        super(message);
    }
```

`VersionedJsonFile.java`:

```java
package dev.andre.homecontrol.storage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

/**
 * One JSON document under /data with a schema version. It is read once and cached, and migrated forward step by step
 * on that first read, keeping the original once as {@code <name>.v<n>.json}. Its store, the single writer, writes it
 * whole through {@link AtomicFiles}. A file from a newer Home Control is refused, never guessed at. {@code T} must be
 * immutable: the snapshot is handed out as it is.
 */
public final class VersionedJsonFile<T> {

    private static final String VERSION = "version";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path file;
    private final String description;
    private final int version;
    private final Supplier<T> empty;
    private final Function<JsonNode, T> reader;
    private final Function<T, ObjectNode> writer;
    private final Map<Integer, UnaryOperator<JsonNode>> steps = new HashMap<>();
    private ToIntFunction<JsonNode> versionOf = VersionedJsonFile::versionField;
    private T snapshot;

    public VersionedJsonFile(Path file, String description, int version, Supplier<T> empty,
                             Function<JsonNode, T> reader, Function<T, ObjectNode> writer) {
        this.file = file;
        this.description = description;
        this.version = version;
        this.empty = empty;
        this.reader = reader;
        this.writer = writer;
    }

    /** Registers the step from version {@code from} to {@code from + 1}, as a JSON tree. */
    public VersionedJsonFile<T> migrate(int from, UnaryOperator<JsonNode> step) {
        steps.put(from, step);
        return this;
    }

    /** For files that predate the version field: how to tell their version from their content. */
    public VersionedJsonFile<T> versionOf(ToIntFunction<JsonNode> lookup) {
        this.versionOf = lookup;
        return this;
    }

    /** The {@code version} field of an object, or -1 when it has none. */
    public static int versionField(JsonNode root) {
        JsonNode field = root.path(VERSION);
        return root.isObject() && field.isIntegralNumber() ? field.asInt() : -1;
    }

    public Path file() {
        return file;
    }

    public synchronized T read() {
        if (snapshot == null) {
            snapshot = load();
        }
        return snapshot;
    }

    public synchronized T update(UnaryOperator<T> change) {
        T next = change.apply(read());
        write(next);
        return next;
    }

    public synchronized void write(T value) {
        ObjectNode document = MAPPER.createObjectNode();
        document.put(VERSION, version);
        writer.apply(value).properties().forEach(field -> {
            if (!VERSION.equals(field.getKey())) {
                document.set(field.getKey(), field.getValue());
            }
        });
        try {
            AtomicFiles.write(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(document), true);
        } catch (IOException | JacksonException e) {
            throw new StorageException("Could not write " + description + " to " + file
                    + "; check that /data is bind-mounted and writable", e);
        }
        snapshot = value;
    }

    /** Removes the file and forgets the snapshot. Exists for the shared test context. */
    public synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StorageException("Could not delete " + file, e);
        }
        snapshot = null;
    }

    private T load() {
        if (!Files.exists(file)) {
            return empty.get();
        }
        byte[] original;
        JsonNode root;
        try {
            original = Files.readAllBytes(file);
            root = MAPPER.readTree(original);
        } catch (IOException | JacksonException e) {
            throw unreadable(e);
        }
        int found = root == null ? -1 : versionOf.applyAsInt(root);
        if (found > version) {
            throw new StorageException(description + " in " + file + " was written by a newer Home Control (version "
                    + found + "); upgrade Home Control or restore a backup");
        }
        T value;
        try {
            JsonNode migrated = root;
            for (int from = found; from < version; from++) {
                UnaryOperator<JsonNode> step = steps.get(from);
                if (step == null) {
                    throw new IllegalArgumentException("no migration from version " + from);
                }
                migrated = step.apply(migrated);
            }
            value = reader.apply(migrated);
        } catch (StorageException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
        if (found < version) {
            backUp(original, found);
            write(value);
        }
        return value;
    }

    /** One-way migrations: the original is kept once for a rollback. An existing backup is the older, so it stays. */
    private void backUp(byte[] original, int from) {
        String name = file.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
        Path backup = file.resolveSibling(base + ".v" + from + ".json");
        if (Files.exists(backup)) {
            return;
        }
        try {
            AtomicFiles.write(backup, original, false);
        } catch (IOException e) {
            throw new StorageException("Could not keep " + file + " as " + backup
                    + " before migrating it; check that /data is bind-mounted and writable", e);
        }
    }

    private StorageException unreadable(Exception cause) {
        return new StorageException("Could not read " + description + " in " + file + "; fix or delete it", cause);
    }
}
```

- [ ] **Step 4: Run it and watch it pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.VersionedJsonFileTest'`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/storage/VersionedJsonFile.java src/main/java/dev/andre/homecontrol/storage/StorageException.java \
  src/test/java/dev/andre/homecontrol/storage/VersionedJsonFileTest.java
git commit -m "feat: add a versioned, cached JSON file for the stores under /data"
```

---

### Task 3: Pins and sports on `VersionedJsonFile`

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStore.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/sports/JsonFileSportsStore.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStoreTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/sports/JsonFileSportsStoreTest.java`

**Interfaces:**
- Consumes: `VersionedJsonFile` (Task 2).
- Produces: unchanged public APIs. `JsonFilePinStore.load()`, `save(List<Pin>)` and `JsonFileSportsStore.load()`,
  `save(SportsSettings)` keep their signatures. `load()` now returns the cached, immutable snapshot.

- [ ] **Step 1: Write the failing tests**

Add to `JsonFilePinStoreTest`:

```java
    @Test
    void theFileIsReadOnceAndThenServedFromMemory() throws IOException {
        JsonFilePinStore store = new JsonFilePinStore(file);
        store.save(List.of());
        store.load();

        Files.writeString(file, "not json any more");

        assertThat(store.load()).isEmpty();
    }

    @Test
    void anOlderVersionWithoutAMigrationIsUnreadable() throws IOException {
        Files.writeString(file, "{\"version\":0,\"pins\":[]}");

        assertThatThrownBy(() -> new JsonFilePinStore(file).load())
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("fix or delete it");
    }
```

Add to `JsonFileSportsStoreTest`:

```java
    @Test
    void theFileIsReadOnceAndThenServedFromMemory() throws IOException {
        JsonFileSportsStore store = new JsonFileSportsStore(file());
        store.save(SportsSettings.empty());
        store.load();

        Files.writeString(file(), "not json any more");

        assertThat(store.load()).isEqualTo(SportsSettings.empty());
    }
```

Use the local names each test class already has for its file (`file` or `file()`), and its imports.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.pinned.JsonFilePinStoreTest' --tests 'dev.andre.homecontrol.sources.sports.JsonFileSportsStoreTest'`
Expected: FAIL. `theFileIsReadOnceAndThenServedFromMemory` throws a `StorageException` in both classes, because each
store re-reads its file.

- [ ] **Step 3: Implement**

`JsonFilePinStore`:
- Replace the `mapper` and `file` fields with:

  ```java
  private static final JsonMapper MAPPER = JsonMapper.builder().build();
  private final VersionedJsonFile<List<Pin>> file;

  public JsonFilePinStore(Path path) {
      this.file = new VersionedJsonFile<>(path, "pinned shortcuts", VERSION, List::of, this::readPins, JsonFilePinStore::writePins);
  }
  ```
- `load()` becomes `return file.read();`, and `save(pins)` becomes `file.write(List.copyOf(pins));`. Both stay
  `synchronized`.
- `readPins(JsonNode root)`:
  - it is today's `load()` from `List<Pin> pins = new ArrayList<>()` onwards, looping over `root.path("pins")`;
  - it returns `List.copyOf(pins)`;
  - it starts with `if (!root.isObject()) throw new IllegalArgumentException("document must be a JSON object");`.
- `writePins(List<Pin> pins)` is today's node building in `save`, without the `version` line and the temp-file code.
  It returns `root`.
- Remove the now unused imports (`StandardCopyOption`, `JacksonException`, `IOException`, `Files`) and
  `VERSION_KEY`.

`JsonFileSportsStore`: the same shape.
- The file is `new VersionedJsonFile<>(path, "sports settings", VERSION, SportsSettings::empty, this::readSettings,
  JsonFileSportsStore::writeSettings)`.
- `readSettings` is today's `load()` body from `String timeZone = …` onwards, after the same object check. It returns
  the `SportsSettings`, whose constructor already copies its lists.
- `writeSettings` is today's `save` body without `version` and `writeAtomically`.
- Delete `writeAtomically` and `deleteQuietly`.

Every existing test in both classes must still pass. Their message checks (`fix or delete it`, `newer Home Control`)
match `VersionedJsonFile`'s messages. If `malformedFilesAreNamedErrors` has a case whose message now differs, check
that the new message is the spec's unreadable message, and update only the expected text.

- [ ] **Step 4: Run them and watch them pass**

Run: the Step 2 command.
Expected: PASS, every test in both classes.

- [ ] **Step 5: Run the modules that use them**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.pinned.*' --tests 'dev.andre.homecontrol.sources.sports.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStore.java src/main/java/dev/andre/homecontrol/sources/sports/JsonFileSportsStore.java \
  src/test/java/dev/andre/homecontrol/sources/pinned/JsonFilePinStoreTest.java src/test/java/dev/andre/homecontrol/sources/sports/JsonFileSportsStoreTest.java
git commit -m "refactor: keep pins and sports settings in versioned, cached files"
```

---

### Task 4: The YouTube quota on `VersionedJsonFile`

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java`
- Modify: `src/test/java/dev/andre/homecontrol/sources/youtube/QuotaLedgerTest.java`

**Interfaces:**
- Consumes: `VersionedJsonFile` (Task 2).
- Produces: unchanged public API. `reset()` now calls `VersionedJsonFile.delete()`.

- [ ] **Step 1: Write the failing test**

Add to `QuotaLedgerTest` (reuse its clock and file helpers):

```java
    @Test
    void theLedgerIsWrittenOwnerOnly() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(file.getFileSystem().supportedFileAttributeViews()).contains("posix");
        QuotaLedger ledger = new QuotaLedger(file, clock, 10_000, 5);

        ledger.charge(QuotaLedger.Call.VIDEOS_LIST);

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }
```

Name the file and clock after the test class's own fields.

- [ ] **Step 2: Run it and watch it fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.youtube.QuotaLedgerTest'`
Expected: FAIL: the permissions are the temp file default (`rw-r--r--` or similar), not `rw-------`.

- [ ] **Step 3: Implement**

In `QuotaLedger`:

```java
    /** What youtube-quota.json holds: one Pacific-time day's usage. {@code day} is null when nothing was stored. */
    record Stored(LocalDate day, int units, int searches, Map<String, Integer> calls) {
        Stored {
            calls = Collections.unmodifiableMap(new LinkedHashMap<>(calls));
        }
    }

    private final VersionedJsonFile<Stored> file;
```

- The constructor builds
  `new VersionedJsonFile<>(path, "the YouTube quota", 1, () -> new Stored(null, 0, 0, Map.of()), QuotaLedger::readStored, QuotaLedger::writeStored)`
  before calling `load()`.
- `readStored(JsonNode root)`:
  - throws `IllegalArgumentException("day is required")` when `root.path("day").asString("")` is blank;
  - otherwise returns `new Stored(LocalDate.parse(day), units, searches, calls)`, from the same fields `load()` reads
    today.
- `writeStored(Stored)` builds the node `write()` builds today, without `version`.
- `load()`:
  - `Stored stored = file.read();` inside the existing `try`;
  - if `stored.day() != null && stored.day().equals(day)`, it copies units, searches and calls;
  - the `catch` becomes `catch (StorageException e)`, keeping its move-aside code, with `file.file()` in place of the
    old `file` field.
- `write()` becomes `file.write(new Stored(day, units, searches, calls));`.
- `reset()` sets the fields as today, then `file.delete();`.

Remove the now unused `MAPPER`, `StandardCopyOption` and `UncheckedIOException` imports if nothing else uses them.

If a test asserted `UncheckedIOException` for a failed write, it now gets `StorageException`. Update only the expected
type.

- [ ] **Step 4: Run it and watch it pass**

Run: the Step 2 command.
Expected: PASS, including the existing corrupt-file, earlier-day and reset tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/sources/youtube/QuotaLedger.java src/test/java/dev/andre/homecontrol/sources/youtube/QuotaLedgerTest.java
git commit -m "refactor: keep the YouTube quota in a versioned, owner-only file"
```

---

### Task 5: `CertificateStore` leaves the protocol package and writes through `AtomicFiles`

**Files:**
- Move: `src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/CertificateStore.java` →
  `adapters/androidtv/CertificateStore.java`
- Move: `src/test/java/dev/andre/homecontrol/adapters/androidtv/protocol/CertificateStoreTest.java` →
  `adapters/androidtv/CertificateStoreTest.java`
- Modify: every importer (`grep -rln "androidtv.protocol.CertificateStore" src/`), about 14 files.
- Modify: `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d`, which loses its four `CertificateStore`
  lines.

**Interfaces:**
- Produces: `dev.andre.homecontrol.adapters.androidtv.CertificateStore`, whose constructor is unchanged at this step.
  `ClientCertificate` stays in `protocol`.

- [ ] **Step 1: Write the failing test**

In the moved `CertificateStoreTest` (package `dev.andre.homecontrol.adapters.androidtv`), add:

```java
    @Test
    void theKeystoreIsWrittenOwnerOnly() throws Exception {
        org.assertj.core.api.Assumptions.assumeThat(dir.getFileSystem().supportedFileAttributeViews()).contains("posix");
        CertificateStore store = new CertificateStore(dir.resolve("keystore.p12"), "pw".toCharArray());

        store.save("living", TestCredentials.clientCertificate());

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                java.nio.file.Files.getPosixFilePermissions(dir.resolve("keystore.p12")))).isEqualTo("rw-------");
    }
```

Use the test's own `@TempDir` name and credential helper.

- [ ] **Step 2: Move and run it to watch it fail**

```bash
git mv src/main/java/dev/andre/homecontrol/adapters/androidtv/protocol/CertificateStore.java src/main/java/dev/andre/homecontrol/adapters/androidtv/CertificateStore.java
git mv src/test/java/dev/andre/homecontrol/adapters/androidtv/protocol/CertificateStoreTest.java src/test/java/dev/andre/homecontrol/adapters/androidtv/CertificateStoreTest.java
```

- Fix the package line of both files.
- In `CertificateStore`, import `dev.andre.homecontrol.adapters.androidtv.protocol.ClientCertificate`.
- Replace `import dev.andre.homecontrol.adapters.androidtv.protocol.CertificateStore;` everywhere with
  `import dev.andre.homecontrol.adapters.androidtv.CertificateStore;`, and remove the import in files of the
  `adapters.androidtv` package itself.
- In `TlsSockets.java`'s comment, the word `CertificateStore` stays as prose.

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.CertificateStoreTest'`
Expected: FAIL: `theKeystoreIsWrittenOwnerOnly`, because `Files.newOutputStream` creates the file with default
permissions.

- [ ] **Step 3: Implement**

Replace `CertificateStore.write`:

```java
    private void write(KeyStore keyStore) throws GeneralSecurityException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        keyStore.store(out, password);
        AtomicFiles.write(file, out.toByteArray(), true);
    }
```

Import `java.io.ByteArrayOutputStream` and `dev.andre.homecontrol.storage.AtomicFiles`. Drop `java.io.OutputStream`.

- [ ] **Step 4: Run the adapter's tests and the architecture test**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*' --tests 'dev.andre.homecontrol.ArchitectureTest'`
Expected: PASS.
- ArchUnit removes the four `CertificateStore … StorageException` lines from
  `src/test/archunit-store/3fa162ba-7523-467d-adbd-20ce146a962d`.
- `git diff --stat src/test/archunit-store` shows only deletions.

- [ ] **Step 5: Compile everything**

Run: `scripts/gradle.sh compileTestJava`
Expected: BUILD SUCCESSFUL. No file still imports the old location.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/androidtv src/test/java/dev/andre/homecontrol src/test/archunit-store
git commit -m "refactor: move the Android TV keystore out of the protocol package and write it atomically"
```

(`git add` of those directories stages only files this task changed. Check with `git status --short` first, and name
the files one by one if anything else shows.)

---

### Task 6: `devices.json` version 3, cached, with the adapters validating their own settings

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/device/JsonFileDeviceRegistry.java`
- Modify: `src/main/java/dev/andre/homecontrol/core/DeviceAdapter.java` (add `validate` and `migrate`)
- Modify: `src/main/java/dev/andre/homecontrol/device/DeviceManager.java` (`start()`)
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapter.java`,
  `src/main/java/dev/andre/homecontrol/adapters/cast/CastAdapter.java` (override `validate`)
- Modify: `src/test/java/dev/andre/homecontrol/device/JsonFileDeviceRegistryTest.java`,
  `src/test/java/dev/andre/homecontrol/device/DeviceManagerTest.java`, `AndroidTvAdapterTest`, `CastAdapterTest`
- Create: `src/test/resources/fixtures/devices/devices-v2.json`
- Modify: `src/test/archunit-store/34477544-f6df-4e51-a96d-cc0f65ebc691`, which loses the three cycles through
  `device → adapters`.

**Interfaces:**
- Consumes: `VersionedJsonFile` (Task 2).
- Produces, on `DeviceAdapter`:
  - `default void validate(Device device) {}`, which throws `IllegalArgumentException` with a reason;
  - `default Device migrate(Device device) { return device; }`, used by Task 10.
- Produces, on `DeviceManager.start()`: it validates every entry of a running adapter first, then migrates and
  connects each device. It saves a device whose migration changed it.

- [ ] **Step 1: Write the failing tests**

`src/test/resources/fixtures/devices/devices-v2.json` (a bare array: what v0.4 to v0.9 wrote):

```json
[{"id":"shield-1","name":"Living Room Shield","kind":"ANDROID_TV","host":"192.168.1.20","adapters":{"androidtv":{"port":"6466"},"cast":{"host":"192.168.1.20","port":"8009","castId":"abc"}},"lastSeen":"2026-09-20T10:00:00Z"}]
```

Check the cast keys against `CastSettings.of` and correct them before relying on the fixture. The test below only needs
the file to read back.

Add to `JsonFileDeviceRegistryTest`:

```java
    @Test
    void aVersionTwoFileIsWrappedIntoVersionThreeAndKeptAsABackup() throws Exception {
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2.json"), file);
        String original = Files.readString(file);

        List<Device> devices = new JsonFileDeviceRegistry(file).findAll();

        assertThat(devices).extracting(Device::id).containsExactly("shield-1");
        JsonNode root = JsonMapper.builder().build().readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(3);
        assertThat(root.path("devices").isArray()).isTrue();
        assertThat(file.resolveSibling("devices.v2.json")).hasContent(original);
    }

    @Test
    void theRegistryIsReadOnceAndThenServedFromMemory() throws Exception {
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);
        registry.save(device("a"));

        Files.writeString(file, "not json any more");

        assertThat(registry.findAll()).extracting(Device::id).containsExactly("a");
    }
```

- `device("a")` stands for whatever builder the test class already uses (for example
  `AndroidTvSettings.device("a", "A", "10.0.0.1", 6466, null, Instant.now())`).
- Name the file after the class's own field.
- The existing v1 tests (`migrates…`, `keepsTheVersionOneFileAsABackup…`, `neverOverwrites…`) stay unchanged. The v1
  backup is still `devices.v1.json`.
- `aVersionTwoFileIsNotBackedUp` changes meaning: a v2 file is now backed up as `devices.v2.json`. Rename it to
  `aVersionThreeFileIsNotBackedUp`. It saves a device, reopens the registry, reads it, and asserts that no
  `devices.v*.json` exists.

Move the two adapter-port tests out of the registry test.
- Delete `aVersionTwoRecordWithAnInvalidAndroidTvPortIsAPathBearingStorageFailure` and
  `aVersionTwoRecordWithAnInvalidCastPortIsAPathBearingStorageFailure` from `JsonFileDeviceRegistryTest`.
- In `AndroidTvAdapterTest`, add:

  ```java
    @Test
    void validateRejectsAPortOutOfRange() {
        Device device = new Device("x", "X", DeviceKind.ANDROID_TV, "10.0.0.9",
                Map.of("androidtv", Map.of("port", "70000")), Instant.now());

        assertThatThrownBy(() -> adapter.validate(device))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("androidtv port must be an integer between 1 and 65535");
    }
  ```

- In `CastAdapterTest`, add the same test with `DeviceKind.CAST`, `Map.of("cast", Map.of("host", "10.0.0.9", "port",
  "0"))` and the message `cast port must be an integer between 1 and 65535`.
- Use each test class's existing adapter instance. If it has none, build one the way its other tests do.

In `DeviceManagerTest`, add:

```java
    @Test
    void anEntryItsAdapterRejectsStopsStartupNamingTheDevice() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("bad-port", "Bad", DeviceKind.ANDROID_TV, "10.0.0.9",
                Map.of("androidtv", Map.of("port", "70000")), Instant.now()));

        try (DeviceManager manager = manager(registry, certificates())) {
            assertThatThrownBy(manager::start)
                    .isInstanceOf(StorageException.class)
                    .hasMessage("Invalid device record bad-port in devices.json: androidtv port must be an integer"
                            + " between 1 and 65535; fix or delete it");
        }
    }

    @Test
    void anEntryOfASwitchedOffAdapterIsNotValidated() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("bad-port", "Bad", DeviceKind.ANDROID_TV, "10.0.0.9",
                Map.of("androidtv", Map.of("port", "70000")), Instant.now()));

        try (DeviceManager manager = new DeviceManager(registry, List.of(), publisher)) {
            manager.start();

            assertThat(manager.devices()).extracting(Device::id).containsExactly("bad-port");
        }
    }

    @Test
    void aDeviceAnAdapterMigratesIsSavedBeforeItConnects() {
        DeviceRegistry registry = new JsonFileDeviceRegistry(dir.resolve("devices.json"));
        registry.save(new Device("old", "Old", DeviceKind.WEBOS, "10.0.0.9", Map.of("probe", Map.of("legacy", "1")),
                Instant.now()));
        DeviceAdapter migrating = new ProbeAdapter() {
            @Override
            public Device migrate(Device device) {
                return device.withAdapter("probe", Map.of("current", "1"));
            }
        };

        try (DeviceManager manager = new DeviceManager(registry, List.of(migrating), publisher)) {
            manager.start();
        }

        assertThat(new JsonFileDeviceRegistry(dir.resolve("devices.json")).findById("old")).get()
                .extracting(device -> device.adapterSettings("probe")).isEqualTo(Map.of("current", "1"));
    }
```

`ProbeAdapter` is a small static nested class in `DeviceManagerTest`:
- its `id()` is `"probe"` and its `kind()` is `DeviceKind.WEBOS`;
- it has empty `capabilities` and `discovered()`;
- `connect` returns a no-op `DeviceHandle`. Reuse an existing fake handle from the test package if there is one
  (`grep -rn "implements DeviceHandle" src/test/java/dev/andre/homecontrol/device`).

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapterTest' --tests 'dev.andre.homecontrol.adapters.cast.CastAdapterTest'`
Expected: compilation FAILS: `validate` and `migrate` are not defined on `DeviceAdapter`.

- [ ] **Step 3: Implement the hooks and the start sequence**

In `DeviceAdapter`, after `forget`:

```java
    /**
     * Checks this adapter's settings of a registered device at startup. Throws {@link IllegalArgumentException} with
     * the reason when they cannot work. Default: nothing to check.
     */
    default void validate(Device device) {
    }

    /**
     * Brings this adapter's settings of a registered device up to date at startup, for example by moving a credential
     * out of the registry. The device manager saves a changed result. Must be idempotent. Default: unchanged.
     */
    default Device migrate(Device device) {
        return device;
    }
```

In `AndroidTvAdapter`:

```java
    @Override
    public void validate(Device device) {
        try {
            AndroidTvSettings.of(device);
        } catch (IllegalArgumentException _) {
            throw new IllegalArgumentException("androidtv port must be an integer between 1 and 65535");
        }
    }
```

In `CastAdapter`, the same with `CastSettings.of(device)` and `cast port must be an integer between 1 and 65535`.

In `DeviceManager`, replace `start()`:

```java
    /**
     * Checks every entry of a running adapter before anything connects, so a bad one stops startup. Then brings each
     * device's settings up to date and connects it. An entry of a switched-off module is left as it is until its
     * module is on.
     */
    @PostConstruct
    public void start() {
        List<Device> registered = registry.findAll();
        registered.forEach(this::validate);
        registered.forEach(device -> connect(migrate(device)));
    }

    private void validate(Device device) {
        for (String adapterId : device.adapters().keySet()) {
            DeviceAdapter adapter = adapters.get(adapterId);
            if (adapter == null) {
                continue;
            }
            try {
                adapter.validate(device);
            } catch (IllegalArgumentException e) {
                throw new StorageException("Invalid device record " + device.id() + " in " + DataDirectory.DEVICES
                        + ": " + e.getMessage() + "; fix or delete it", e);
            }
        }
    }

    private Device migrate(Device device) {
        synchronized (lock) {
            Device migrated = device;
            for (String adapterId : device.adapters().keySet()) {
                DeviceAdapter adapter = adapters.get(adapterId);
                if (adapter != null) {
                    migrated = adapter.migrate(migrated);
                }
            }
            if (!migrated.equals(device)) {
                registry.save(migrated);
            }
            return migrated;
        }
    }
```

It imports `dev.andre.homecontrol.storage.DataDirectory` and `dev.andre.homecontrol.storage.StorageException`.

- [ ] **Step 4: Implement the registry**

Replace the body of `JsonFileDeviceRegistry`, keeping `migrateVersionOne` (now a static JSON step),
`requireValidPort`, `validateDevices`, `validateDevice` without the two adapter blocks, and `invalidDevice`:

```java
/**
 * Registry backed by devices.json, version 3: {@code {"version": 3, "devices": [...]}}. It is read once and written
 * through. Versions 1 (v0.3's single Android TV) and 2 were bare arrays. Each adapter checks its own settings when
 * the device manager starts.
 */
public class JsonFileDeviceRegistry implements DeviceRegistry {

    private static final int VERSION = 3;
    private static final String DEVICES = "devices";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final VersionedJsonFile<List<Device>> file;

    public JsonFileDeviceRegistry(Path path) {
        this.file = new VersionedJsonFile<>(path, "the device registry", VERSION, List::of,
                JsonFileDeviceRegistry::readDevices, JsonFileDeviceRegistry::writeDevices)
                .versionOf(JsonFileDeviceRegistry::versionOf)
                .migrate(1, JsonFileDeviceRegistry::migrateVersionOne)
                .migrate(2, JsonFileDeviceRegistry::wrap);
    }

    /** A bare array is version 1 when an element has no kind (v0.3), else version 2. */
    private static int versionOf(JsonNode root) {
        if (!root.isArray()) {
            return VersionedJsonFile.versionField(root);
        }
        for (JsonNode element : root) {
            if (element.isObject() && !element.has("kind")) {
                return 1;
            }
        }
        return 2;
    }

    private static JsonNode migrateVersionOne(JsonNode array) {
        ArrayNode migrated = MAPPER.createArrayNode();
        for (JsonNode element : array) {
            migrated.add(element.isObject() && !element.has("kind") ? migrateDevice((ObjectNode) element) : element);
        }
        return migrated;
    }

    private static JsonNode wrap(JsonNode array) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set(DEVICES, array);
        return root;
    }

    private static List<Device> readDevices(JsonNode root) {
        JsonNode array = root.path(DEVICES);
        if (!array.isArray()) {
            throw new IllegalArgumentException("devices must be a JSON array");
        }
        List<Device> devices = new ArrayList<>();
        for (JsonNode node : array) {
            devices.add(MAPPER.treeToValue(node, Device.class));
        }
        validateDevices(devices);
        return List.copyOf(devices);
    }

    private static ObjectNode writeDevices(List<Device> devices) {
        ObjectNode root = MAPPER.createObjectNode();
        root.set(DEVICES, MAPPER.valueToTree(devices));
        return root;
    }

    @Override
    public List<Device> findAll() {
        return file.read();
    }

    @Override
    public Optional<Device> findById(String id) {
        return findAll().stream().filter(device -> device.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Device> first() {
        return findAll().stream().max(Comparator.comparing(Device::lastSeen));
    }

    @Override
    public void save(Device device) {
        file.update(devices -> {
            List<Device> next = new ArrayList<>(devices);
            next.removeIf(existing -> existing.id().equals(device.id()));
            next.add(device);
            return List.copyOf(next);
        });
    }

    @Override
    public void delete(String id) {
        file.update(devices -> devices.stream().filter(existing -> !existing.id().equals(id)).toList());
    }
}
```

- The old per-element method becomes `private static ObjectNode migrateDevice(ObjectNode v1)`, with today's body and
  `MAPPER` in place of `mapper`.
- Keep the Javadocs on `first()` and on the migration.
- `validateDevice` runs on a list that may contain `null` from a JSON `null` element, before `List.copyOf`, so the
  `record is null` check still fires.
- Remove the imports of `AndroidTvSettings` and `CastSettings`.

The registry tests that expected `permissions` or `integrity` in the message
(`malformedRegistryIsAStorageFailureNotAnEmptyRegistry`, `nullRegistryDocumentIsAPathBearingStorageFailure`,
`incompleteDeviceRecordIsAPathBearingStorageFailure`, `aVersionOneRecordWithAnUnusablePortIsAPathBearingStorageFailure`)
now get the spec's unreadable message. Change only their `hasMessageContaining` from `"permissions"`/`"integrity"` to
`"fix or delete it"`.

- [ ] **Step 5: Run and watch them pass**

Run: the Step 2 command, plus `--tests 'dev.andre.homecontrol.ArchitectureTest'`.
Expected: PASS.
- `src/test/archunit-store/34477544-f6df-4e51-a96d-cc0f65ebc691` loses its three cycles through `Slice device ->
  Slice adapters`. Only the `security -> web -> security` cycle remains.
- `git diff --stat src/test/archunit-store` shows only deletions.

- [ ] **Step 6: Run everything that builds a registry**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*' --tests 'dev.andre.homecontrol.web.SetupControllerTest' --tests 'dev.andre.homecontrol.CastDisabledSmokeTest'`
Expected: PASS.

If a test writes `devices.json` by hand as a bare array, it still works: the array is read as version 2 and migrated.
If a test asserts the file's shape afterwards, update it to `root.path("devices")`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/device src/main/java/dev/andre/homecontrol/core/DeviceAdapter.java \
  src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapter.java src/main/java/dev/andre/homecontrol/adapters/cast/CastAdapter.java \
  src/test/java/dev/andre/homecontrol/device src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapterTest.java \
  src/test/java/dev/andre/homecontrol/adapters/cast/CastAdapterTest.java src/test/resources/fixtures/devices src/test/archunit-store
git commit -m "feat: version devices.json, cache the registry, and let adapters validate their own settings"
```

---

### Task 7: `sources.json` version 2 with typed sections

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java`
- Modify: `src/main/java/dev/andre/homecontrol/sources/jellyfin/JellyfinSettings.java` (`from` → `fromVersionOne`,
  drop `toMap`)
- Modify: `src/main/java/dev/andre/homecontrol/sources/youtube/YouTubeSettings.java` (the same, plus `read`)
- Modify: `src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbSettings.java` (the same)
- Modify the callers: `JellyfinSetupService`, `YouTubeSetupService`, `YouTubeAuthorizationService`,
  `TmdbSetupService`, and any other hit of
  `grep -rn "\.toMap()\|Settings.from(" src/main/java/dev/andre/homecontrol/sources`.
- Modify: `src/test/java/dev/andre/homecontrol/storage/JsonFileSourceSettingsTest.java`, and the source tests that
  call `toMap`/`from` or put raw maps (`grep -rln "toMap()\|Settings.from(\|sources.put(\|\.get(\"jellyfin\")\|\.get(\"youtube\")\|\.get(\"tmdb\")" src/test/java`).
- Create: `src/test/resources/fixtures/sources/sources-v1.json`
- Modify: `src/test/java/dev/andre/homecontrol/testsupport/FullAppReset.java` and `FullAppResetTest.java`

**Interfaces:**
- Consumes: `VersionedJsonFile` (Task 2).
- Produces, on `JsonFileSourceSettings`:
  - `<T> Optional<T> get(String sourceId, Class<T> type, Function<Map<String, String>, Optional<T>> fromVersionOne)`;
  - `void put(String sourceId, Object settings)`;
  - `void remove(String sourceId)`;
  - `Optional<SourcePreferences> preferences()`;
  - `void putPreferences(SourcePreferences)`;
  - `void reset()`.
- Produces:
  - `JellyfinSettings.fromVersionOne(Map<String, String>)`, which returns `Optional<JellyfinSettings>`;
  - `TmdbSettings.fromVersionOne(Map<String, String>)`, which returns `Optional<TmdbSettings>`;
  - `YouTubeSettings.fromVersionOne(Map<String, String>)`, which returns `YouTubeSettings`;
  - `YouTubeSettings.read(JsonFileSourceSettings)`, which returns `YouTubeSettings` (`EMPTY` when there is none).

- [ ] **Step 1: Write the fixture and the failing tests**

`src/test/resources/fixtures/sources/sources-v1.json`. The flat format v0.9 wrote, with every flattened key kind and a
section for a source whose module may be off:

```json
{"version":1,"sources":{"jellyfin":{"authMode":"PASSWORD","castReceiverId":"F007D354","deviceId":"hc-1","deviceServerUrl":"http://192.168.1.5:8096","link.shield-1":"jf-dev-9","player.shield-1":"vlc","serverId":"srv","serverName":"NAS","serverUrl":"http://nas:8096","serverVersion":"10.9.0","userId":"u1","userName":"andre"},"youtube":{"channelId":"UC1","channelTitle":"Me","connectedAt":"2026-09-01T10:00:00Z","lounge.devices":"shield-1,tv-2","lounge.remoteId":"r-1","playlist.PL1":"Cooking","playlist.PL2":"Music","watchLater":"true"},"tmdb":{"connectedAt":"2026-09-02T10:00:00Z","credentialKind":"API_KEY"},"future-source":{"a":"1"}},"preferences":{"railOrder":["jellyfin/next-up"],"hiddenRails":[],"disabledSources":[],"refreshMinutes":{"jellyfin":10},"locale":"de-DE","region":"DE","providers":["netflix"]}}
```

Check `TmdbCredential.Kind` for a real constant name and use it in place of `API_KEY` if it differs.

Rewrite `JsonFileSourceSettingsTest`, keeping its preference tests as they are and using a small record for the
typed sections:

```java
    record Probe(String serverUrl, Map<String, String> links) {
        static Optional<Probe> fromVersionOne(Map<String, String> flat) {
            if (flat.get("serverUrl") == null) {
                return Optional.empty();
            }
            Map<String, String> links = new TreeMap<>();
            flat.forEach((key, value) -> {
                if (key.startsWith("link.")) {
                    links.put(key.substring(5), value);
                }
            });
            return Optional.of(new Probe(flat.get("serverUrl"), links));
        }
    }

    private Optional<Probe> probe(JsonFileSourceSettings settings, String id) {
        return settings.get(id, Probe.class, Probe::fromVersionOne);
    }

    @Test
    void anAbsentFileHasNoSettings() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        assertThat(probe(settings, "jellyfin")).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void aSectionIsStoredAsItsOwnJsonAndReadBackTyped() throws IOException {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        settings.put("jellyfin", new Probe("http://nas:8096", Map.of("shield-1", "jf-9")));

        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin"))
                .contains(new Probe("http://nas:8096", Map.of("shield-1", "jf-9")));
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("version").asInt()).isEqualTo(2);
        assertThat(root.path("sources").path("jellyfin").path("links").path("shield-1").asString()).isEqualTo("jf-9");
    }

    @Test
    void removeDropsOnlyThatSource() {
        JsonFileSourceSettings settings = new JsonFileSourceSettings(dir.resolve("sources.json"));
        settings.put("jellyfin", new Probe("a", Map.of()));
        settings.put("other", new Probe("b", Map.of()));

        settings.remove("jellyfin");

        assertThat(probe(settings, "jellyfin")).isEmpty();
        assertThat(probe(settings, "other")).contains(new Probe("b", Map.of()));
    }

    @Test
    void aVersionOneSectionIsConvertedByItsSourceOnFirstReadAndStoredTyped() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        assertThat(probe(settings, "jellyfin")).contains(new Probe("http://nas:8096", Map.of("shield-1", "jf-dev-9")));

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("sources").path("jellyfin").path("serverUrl").asString()).isEqualTo("http://nas:8096");
        assertThat(root.path("unmigrated").has("jellyfin")).isFalse();
        assertThat(root.path("unmigrated").path("future-source").path("a").asString()).isEqualTo("1");
        assertThat(file.resolveSibling("sources.v1.json")).exists();
        assertThat(settings.preferences()).get().extracting(SourcePreferences::locale).isEqualTo("de-DE");
    }

    @Test
    void anUnconvertedSectionSurvivesUntilItsSourceReadsIt() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);

        settings.put("jellyfin", new Probe("http://other:8096", Map.of()));
        settings.putPreferences(SourcePreferences.defaults("en-GB", "GB"));

        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        assertThat(root.path("unmigrated").path("future-source").path("a").asString()).isEqualTo("1");
        assertThat(root.path("unmigrated").has("jellyfin")).isFalse();
    }

    @Test
    void anIncompleteVersionOneSectionReadsAsNotConnected() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "{\"version\":1,\"sources\":{\"jellyfin\":{\"userId\":\"u1\"}}}");

        assertThat(probe(new JsonFileSourceSettings(file), "jellyfin")).isEmpty();
    }

    @Test
    void aSectionThatDoesNotBindIsANamedStorageException() throws IOException {
        Path file = dir.resolve("sources.json");
        Files.writeString(file, "{\"version\":2,\"sources\":{\"jellyfin\":{\"links\":\"not an object\"}}}");

        assertThatThrownBy(() -> probe(new JsonFileSourceSettings(file), "jellyfin"))
                .isInstanceOf(StorageException.class)
                .hasMessage("Could not read the jellyfin settings in " + file + "; fix or delete that section");
    }

    @Test
    void resetDeletesTheFile() {
        Path file = dir.resolve("sources.json");
        JsonFileSourceSettings settings = new JsonFileSourceSettings(file);
        settings.put("jellyfin", new Probe("a", Map.of()));

        settings.reset();

        assertThat(file).doesNotExist();
        assertThat(probe(settings, "jellyfin")).isEmpty();
    }
```

In `putWritesAtomicallyAndRoundTrips` (it becomes `aSectionIsStoredAsItsOwnJsonAndReadBackTyped` above), in
`aMalformedFileIsANamedStorageException` and in the preference tests:
- replace `settings.put("jellyfin", Map.of(...))` with `settings.put("jellyfin", new Probe("http://nas:8096",
  Map.of()))`;
- replace `settings.get("jellyfin")` with `probe(settings, "jellyfin")`.

`malformedPreferencesAreANamedStorageException` keeps its v1 document. The migration carries `preferences` over, so
its error still names them.

Add typed-record tests per source, in each source's existing settings test class (create
`JellyfinSettingsTest`, `YouTubeSettingsTest` or `TmdbSettingsTest` beside its record if missing):

```java
    // JellyfinSettingsTest
    @Test
    void theVersionOneFixtureConvertsLinksAndPlayersAndRoundTripsThroughTheStore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        JellyfinSettings settings = new JsonFileSourceSettings(file)
                .get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne).orElseThrow();

        assertThat(settings.sessionLinks()).containsExactly(Map.entry("shield-1", "jf-dev-9"));
        assertThat(settings.player("shield-1")).isEqualTo(JellyfinSettings.Player.VLC);
        assertThat(new JsonFileSourceSettings(file)
                .get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne))
                .contains(settings);
        JsonNode section = JsonMapper.builder().build().readTree(Files.readAllBytes(file)).path("sources").path("jellyfin");
        assertThat(section.path("players").path("shield-1").asString()).isEqualTo("VLC");
        assertThat(section.path("sessionLinks").path("shield-1").asString()).isEqualTo("jf-dev-9");
    }

    // YouTubeSettingsTest
    @Test
    void theVersionOneFixtureConvertsPlaylistsAndLoungeDevicesAndRoundTripsThroughTheStore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        YouTubeSettings settings = YouTubeSettings.read(new JsonFileSourceSettings(file));

        assertThat(settings.playlists()).containsExactly(Map.entry("PL1", "Cooking"), Map.entry("PL2", "Music"));
        assertThat(settings.loungeDevices()).containsExactly("shield-1", "tv-2");
        assertThat(settings.watchLater()).isTrue();
        assertThat(YouTubeSettings.read(new JsonFileSourceSettings(file))).isEqualTo(settings);
        JsonNode section = JsonMapper.builder().build().readTree(Files.readAllBytes(file)).path("sources").path("youtube");
        assertThat(section.path("loungeDevices").isArray()).isTrue();
        assertThat(section.path("playlists").path("PL2").asString()).isEqualTo("Music");
    }

    // TmdbSettingsTest
    @Test
    void theVersionOneFixtureConvertsAndRoundTripsThroughTheStore(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sources.json");
        Files.copy(Path.of("src/test/resources/fixtures/sources/sources-v1.json"), file);

        TmdbSettings settings = new JsonFileSourceSettings(file)
                .get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne).orElseThrow();

        assertThat(settings.connectedAt()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
        assertThat(new JsonFileSourceSettings(file)
                .get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne)).contains(settings);
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.JsonFileSourceSettingsTest' --tests '*JellyfinSettingsTest' --tests '*YouTubeSettingsTest' --tests '*TmdbSettingsTest'`
Expected: compilation FAILS: no `get(String, Class, Function)`, `put(String, Object)`, `reset()`, `fromVersionOne` or
`YouTubeSettings.read`.

- [ ] **Step 3: Implement the store**

```java
/**
 * sources.json, version 2. It holds non-secret per-source settings, each source's section being its own settings
 * record as JSON, plus the household's rail and source preferences. Version 1 flattened every section into strings.
 * Its sections wait under {@code unmigrated} until their source reads them and converts them with its own
 * {@code fromVersionOne}, so this store needs no source's types, and a switched-off source's section survives
 * untouched. Secrets never live here; they go in {@link SecretStore}.
 */
public class JsonFileSourceSettings {

    private static final int VERSION = 2;
    private static final String SOURCES = "sources";
    private static final String UNMIGRATED = "unmigrated";
    private static final String PREFERENCES = "preferences";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The whole file. {@code preferences} is null until they are first saved. */
    record Document(Map<String, JsonNode> sources, Map<String, Map<String, String>> unmigrated, JsonNode preferences) {

        static final Document EMPTY = new Document(Map.of(), Map.of(), null);

        Document {
            sources = Collections.unmodifiableMap(new TreeMap<>(sources));
            unmigrated = Collections.unmodifiableMap(new TreeMap<>(unmigrated));
        }

        Document withSource(String id, JsonNode section) {
            Map<String, JsonNode> next = new TreeMap<>(sources);
            next.put(id, section);
            Map<String, Map<String, String>> rest = new TreeMap<>(unmigrated);
            rest.remove(id);
            return new Document(next, rest, preferences);
        }

        Document without(String id) {
            Map<String, JsonNode> next = new TreeMap<>(sources);
            next.remove(id);
            Map<String, Map<String, String>> rest = new TreeMap<>(unmigrated);
            rest.remove(id);
            return new Document(next, rest, preferences);
        }

        Document withPreferences(JsonNode node) {
            return new Document(sources, unmigrated, node);
        }
    }

    private final Path path;
    private final VersionedJsonFile<Document> file;

    public JsonFileSourceSettings(Path path) {
        this.path = path;
        this.file = new VersionedJsonFile<>(path, "source settings", VERSION, () -> Document.EMPTY,
                JsonFileSourceSettings::readDocument, JsonFileSourceSettings::writeDocument)
                .migrate(1, JsonFileSourceSettings::versionOneToTwo);
    }

    /**
     * The source's settings, or empty when it has none. A section still in version 1's flat form is converted by
     * {@code fromVersionOne} and stored typed in the same step. A flat section it cannot use is dropped, reading as
     * "not connected".
     */
    public synchronized <T> Optional<T> get(String sourceId, Class<T> type,
                                            Function<Map<String, String>, Optional<T>> fromVersionOne) {
        Document document = file.read();
        JsonNode section = document.sources().get(sourceId);
        if (section != null) {
            return Optional.of(bind(sourceId, section, type));
        }
        Map<String, String> flat = document.unmigrated().get(sourceId);
        if (flat == null) {
            return Optional.empty();
        }
        Optional<T> converted = fromVersionOne.apply(flat);
        file.update(current -> converted
                .map(value -> current.withSource(sourceId, MAPPER.valueToTree(value)))
                .orElseGet(() -> current.without(sourceId)));
        return converted;
    }

    public synchronized void put(String sourceId, Object settings) {
        file.update(document -> document.withSource(sourceId, MAPPER.valueToTree(settings)));
    }

    public synchronized void remove(String sourceId) {
        Document document = file.read();
        if (document.sources().containsKey(sourceId) || document.unmigrated().containsKey(sourceId)) {
            file.update(current -> current.without(sourceId));
        }
    }

    /** Deletes the file. Exists for the shared test context. */
    public synchronized void reset() {
        file.delete();
    }

    private <T> T bind(String sourceId, JsonNode section, Class<T> type) {
        try {
            return MAPPER.treeToValue(section, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new StorageException("Could not read the " + sourceId + " settings in " + path
                    + "; fix or delete that section", e);
        }
    }
```

- `preferences()` keeps today's body. It reads `file.read().preferences()`: `null` or a JSON `null` gives
  `Optional.empty()`, and the rest parses as today, with `path` in the message.
- `putPreferences` builds the same `ObjectNode` as today, from `MAPPER.createObjectNode()`, and calls
  `file.update(document -> document.withPreferences(node))`.
- Keep `strings`, `minutes` and their messages.

```java
    private static Document readDocument(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        Map<String, JsonNode> sources = new TreeMap<>();
        root.path(SOURCES).properties().forEach(field -> sources.put(field.getKey(), field.getValue()));
        Map<String, Map<String, String>> unmigrated = new TreeMap<>();
        root.path(UNMIGRATED).properties().forEach(field -> unmigrated.put(field.getKey(), flat(field.getValue())));
        JsonNode preferences = root.get(PREFERENCES);
        return new Document(sources, unmigrated, preferences);
    }

    private static Map<String, String> flat(JsonNode section) {
        Map<String, String> values = new TreeMap<>();
        section.properties().forEach(field -> values.put(field.getKey(), field.getValue().asString("")));
        return Collections.unmodifiableMap(values);
    }

    private static ObjectNode writeDocument(Document document) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode sources = root.putObject(SOURCES);
        document.sources().forEach(sources::set);
        if (!document.unmigrated().isEmpty()) {
            ObjectNode unmigrated = root.putObject(UNMIGRATED);
            document.unmigrated().forEach((id, values) -> {
                ObjectNode section = unmigrated.putObject(id);
                values.forEach(section::put);
            });
        }
        if (document.preferences() != null) {
            root.set(PREFERENCES, document.preferences());
        }
        return root;
    }

    /** Every flat section waits under "unmigrated" for its source; the preferences keep their shape. */
    private static JsonNode versionOneToTwo(JsonNode v1) {
        if (!v1.isObject()) {
            throw new IllegalArgumentException("document must be a JSON object");
        }
        ObjectNode v2 = MAPPER.createObjectNode();
        v2.putObject(SOURCES);
        JsonNode sections = v1.path(SOURCES);
        if (sections.isObject()) {
            v2.set(UNMIGRATED, sections);
        }
        JsonNode preferences = v1.get(PREFERENCES);
        if (preferences != null) {
            v2.set(PREFERENCES, preferences);
        }
        return v2;
    }
}
```

Imports: `java.util.Collections`, `java.util.TreeMap`, `java.util.function.Function`, and
`tools.jackson.core.JacksonException`. Drop the old temp-file imports.

- [ ] **Step 4: Implement the records and their callers**

`JellyfinSettings`:
- rename `from` to `fromVersionOne`, with the Javadoc
  `/** sources.json version 1's flat section: {@code link.<device>} and {@code player.<device>} keys. */`;
- delete `toMap()`, `LINK_PREFIX`'s other users, and `TreeMap`/`Locale` if they are now unused.

`TmdbSettings`: the same (`fromVersionOne`, no `toMap`).

`YouTubeSettings`:
- `EMPTY = new YouTubeSettings(null, null, null, false, Map.of(), Set.of(), null)`;
- rename `from` to `fromVersionOne`, delete `toMap()` and `putIfPresent`;
- add:

  ```java
    /** The stored settings, or {@link #EMPTY} before anything was saved. */
    public static YouTubeSettings read(JsonFileSourceSettings sources) {
        return sources.get(SOURCE_ID, YouTubeSettings.class, flat -> Optional.of(fromVersionOne(flat))).orElse(EMPTY);
    }
  ```

Callers:

| Where | Before | After |
| --- | --- | --- |
| `JellyfinSetupService.settings()` | `JellyfinSettings.from(sources.get(SOURCE_ID))` | `sources.get(JellyfinSettings.SOURCE_ID, JellyfinSettings.class, JellyfinSettings::fromVersionOne)` |
| `JellyfinSetupService` (two puts) | `sources.put(SOURCE_ID, x.toMap())` | `sources.put(JellyfinSettings.SOURCE_ID, x)` |
| `TmdbSetupService.settings()` | `TmdbSettings.from(sources.get(SOURCE_ID))` | `sources.get(TmdbSettings.SOURCE_ID, TmdbSettings.class, TmdbSettings::fromVersionOne)` |
| `TmdbSetupService` put | `settings.toMap()` | `settings` |
| `YouTubeSetupService.settings()` | `YouTubeSettings.from(sourceSettings.get(SOURCE_ID))` | `YouTubeSettings.read(sourceSettings)` |
| `YouTubeSetupService.save` | `settings.toMap()` | `settings` |
| `YouTubeAuthorizationService.storeGrant` | `YouTubeSettings.from(settings.get(…))`, `….toMap()` | `YouTubeSettings.read(settings)`, the record itself |

Then run
`grep -rn "Settings.from(\|\.toMap()" src/main/java/dev/andre/homecontrol/sources/{jellyfin,youtube,tmdb}`. It must print
only adapter settings (`UpnpSettings`, `CastSettings` and the like are outside these packages, so nothing here).

- [ ] **Step 5: The test callers and the full-app reset**

- For every test hit of the Step 1 grep, replace:
  - `sources.put(id, x.toMap())` with `sources.put(id, x)`;
  - `XSettings.from(map)` with `XSettings.fromVersionOne(map)`;
  - raw-map `put`s of whole sections with the record.
- Where a test asserted the flat file shape (`path("link.shield-1")`), assert the typed one
  (`path("sessionLinks").path("shield-1")`).

In `FullAppReset.reset`:
- replace `"sources.json"` in the deleted-file loop with a call:
  `app.getBean(JsonFileSourceSettings.class).reset();`;
- remove `"sources.json"` from the array.

Tasks 3 and 4 made `pinned.json` and `sports.json` cached, so deleting them behind the stores no longer resets them. The
pins and sports are already reset through their services above the loop. Remove `"sports.json"` and `"pinned.json"`
from the array too, and leave `"secrets.json"` and `"secret.key"` for Task 8.

In `FullAppResetTest`, the reset now proves itself by state, not by missing files:
- replace the loop over file names with:

  ```java
        assertThat(context.getBean(JsonFileSourceSettings.class).preferences()).isEmpty();
        assertThat(dataDir().resolve("sources.json")).doesNotExist();
        assertThat(dataDir().resolve("youtube-quota.json")).doesNotExist();
  ```
- keep the `pins.all()`, `sports.current()` and `quota` assertions;
- keep the `secrets.json`/`secret.key` checks until Task 8.

- [ ] **Step 6: Run and watch them pass**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.*' --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.content.*' --tests 'dev.andre.homecontrol.testsupport.FullAppResetTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/storage/JsonFileSourceSettings.java src/main/java/dev/andre/homecontrol/sources \
  src/test/java/dev/andre/homecontrol/storage/JsonFileSourceSettingsTest.java src/test/java/dev/andre/homecontrol/sources \
  src/test/java/dev/andre/homecontrol/content src/test/java/dev/andre/homecontrol/testsupport src/test/resources/fixtures/sources
git commit -m "feat: store each source's settings typed in sources.json version 2"
```

(Check `git status --short` before adding directories. Name only the files this task changed.)

---

### Task 8: Two kinds of secrets, and a login that is set and removed on purpose

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/core/DeviceSecrets.java`
- Modify: `src/main/java/dev/andre/homecontrol/storage/SecretStore.java`
- Modify: `src/main/java/dev/andre/homecontrol/security/LoginService.java`
- Create: `src/test/java/dev/andre/homecontrol/testsupport/InMemoryDeviceSecrets.java`
- Modify: `src/test/java/dev/andre/homecontrol/storage/SecretStoreTest.java`,
  `src/test/java/dev/andre/homecontrol/security/LoginServiceTest.java`
- Modify, for the "login disappears" expectation (grep from exploration): `FullAppReset`, `FullAppResetTest`,
  `JellyfinSetupServiceTest:173`, `WorkflowSetupControllerTest:304`, `TmdbSetupServiceTest:105,170`,
  `JellyfinEndToEndTest:231`, `StreamingLaunchersEndToEndTest:248`, `YouTubeEndToEndTest:270`, and any
  `LoginGatingTest` case that relies on it.

**Interfaces:**
- Produces `core.DeviceSecrets`:
  - `String PREFIX = "device."`;
  - `Optional<String> deviceSecret(String name)`, `void putDeviceSecret(String name, String value)` and
    `void removeDeviceSecrets(Collection<String> names)`;
  - `static String newReference()`: 16 lower-case hex characters.
- Produces, on `SecretStore implements DeviceSecrets`:
  - `putSecrets(Map)` needs a login only for account credentials;
  - `putFirstSecrets(Map, LoginCredential)` needs "no login yet";
  - `removeSecrets(Collection)` never removes the login;
  - `setLogin(LoginCredential)`, `removeLogin()`, `hasAccountCredentials()` and `Set<String> accountCredentialNames()`.
- Produces, on `LoginService`:
  - `List<String> connectedAccounts()`;
  - `void setPassword(String password, String confirmation, HttpServletRequest request)`;
  - `void removePassword(String current, HttpServletRequest request)`.

- [ ] **Step 1: Write the failing tests**

In `SecretStoreTest`:
- replace `removingTheLastSecretRemovesTheLogin` with `theLoginSurvivesTheLastSecret` below;
- in any other test asserting `login()` is empty after removal, assert `isPresent()`.

```java
    @Test
    void theLoginSurvivesTheLastSecret() {
        SecretStore store = store(null);
        store.putFirstSecrets(Map.of("a", "1"), new LoginCredential("h", "v1"));

        store.removeSecrets(List.of("a"));

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
    void anAccountCredentialStillNeedsALogin() {
        SecretStore store = store(null);
        store.putDeviceSecret("device.androidtv.keystore-password", "p");

        assertThatThrownBy(() -> store.putSecrets(Map.of("jellyfin.token", "t")))
                .isInstanceOf(IllegalStateException.class);
        store.putFirstSecrets(Map.of("jellyfin.token", "t"), new LoginCredential("h", "v1"));
        assertThat(store(null).accountCredentialNames()).containsExactly("jellyfin.token");
    }

    @Test
    void deviceSecretMethodsRefuseOtherNames() {
        SecretStore store = store(null);

        assertThatThrownBy(() -> store.putDeviceSecret("jellyfin.token", "t")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deviceSecret("jellyfin.token")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.removeDeviceSecrets(List.of("jellyfin.token")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoginIsSetAndRemovedOnPurpose() {
        SecretStore store = store(null);

        store.setLogin(new LoginCredential("h", "v1"));
        assertThat(store(null).login()).isPresent();
        assertThatThrownBy(() -> store.setLogin(new LoginCredential("h2", "v2"))).isInstanceOf(IllegalStateException.class);

        store.putSecrets(Map.of("tmdb.credential", "c"));
        assertThatThrownBy(store::removeLogin).isInstanceOf(IllegalStateException.class);

        store.removeSecrets(List.of("tmdb.credential"));
        store.removeLogin();
        assertThat(store(null).login()).isEmpty();
    }
```

Add to a new `src/test/java/dev/andre/homecontrol/core/DeviceSecretsTest.java`:

```java
class DeviceSecretsTest {

    @Test
    void aReferenceIsSixteenHexCharacters() {
        assertThat(DeviceSecrets.newReference()).matches("[0-9a-f]{16}");
        assertThat(DeviceSecrets.newReference()).isNotEqualTo(DeviceSecrets.newReference());
    }
}
```

In `LoginServiceTest`, replace `removingTheLastSecretEndsTheLoginRequirement` with the tests below. Reuse `PASSWORD`,
`store`, `login` and `firstSecretStored()`.

```java
    @Test
    void removingTheLastSecretKeepsTheLogin() {
        firstSecretStored();

        login.removeSecrets(List.of("jellyfin.token"));

        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void aPasswordIsSetWithoutAnySecretAndLogsThisBrowserIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        login.setPassword(PASSWORD, PASSWORD, request);

        assertThat(login.loginRequired()).isTrue();
        assertThat(login.isAuthenticated(request)).isTrue();
        assertThat(login.isAuthenticated(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void aSecondPasswordIsRefused() {
        login.setPassword(PASSWORD, PASSWORD, new MockHttpServletRequest());

        assertThatThrownBy(() -> login.setPassword("another password", "another password", new MockHttpServletRequest()))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("A login password is already set; change it instead");
    }

    @Test
    void theCurrentPasswordRemovesTheLogin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        login.setPassword(PASSWORD, PASSWORD, request);
        MockHttpSession before = (MockHttpSession) request.getSession(false);

        login.removePassword(PASSWORD, request);

        assertThat(login.loginRequired()).isFalse();
        login.setPassword("a later password", "a later password", new MockHttpServletRequest());
        assertThat(login.isAuthenticated(before)).isFalse();
    }

    @Test
    void aWrongPasswordDoesNotRemoveTheLogin() {
        login.setPassword(PASSWORD, PASSWORD, new MockHttpServletRequest());

        assertThatThrownBy(() -> login.removePassword("not the password", new MockHttpServletRequest()))
                .isInstanceOf(WrongPasswordException.class);
        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void theLoginStaysWhileAnAccountIsConnectedAndTheRefusalNamesIt() {
        firstSecretStored();

        assertThatThrownBy(() -> login.removePassword(PASSWORD, new MockHttpServletRequest()))
                .isInstanceOf(PasswordRejectedException.class)
                .isNotInstanceOf(WrongPasswordException.class)
                .hasMessage("Disconnect Jellyfin first: its credentials need the login password");
        assertThat(login.loginRequired()).isTrue();
    }

    @Test
    void connectedAccountsAreNamedOnceEachAndDeviceSecretsAreNotAccounts() {
        store.putDeviceSecret("device.androidtv.keystore-password", "p");
        MockHttpServletRequest request = firstSecretStored();
        login.storeSecrets(Map.of("youtube.client-id", "id", "youtube.client-secret", "s", "workflow.w-0123456789ab", "x"),
                null, null, request);

        assertThat(login.connectedAccounts()).containsExactly("Jellyfin", "Workflows", "YouTube");
    }

    @Test
    void removingThePasswordIsRefusedOnceAnAccountIsConnectedMeanwhile() {
        Argon2PasswordHasher hasher = mock(Argon2PasswordHasher.class);
        given(hasher.hash(anyString())).willAnswer(call -> "hash of " + call.getArgument(0));
        LoginService scripted = new LoginService(store, hasher, new SecureRandom());
        MockHttpServletRequest request = new MockHttpServletRequest();
        scripted.setPassword(PASSWORD, PASSWORD, request);
        given(hasher.matches(anyString(), anyString())).willAnswer(call -> {
            store.putSecrets(Map.of("tmdb.credential", "c")); // connected while the password was being checked
            return true;
        });

        assertThatThrownBy(() -> scripted.removePassword(PASSWORD, request))
                .isInstanceOf(PasswordRejectedException.class)
                .hasMessage("Disconnect TMDB first: its credentials need the login password");
        assertThat(store.login()).isPresent();
    }
```

In `FullAppResetTest`, after `FullAppReset.reset(context)`:
- replace `assertThat(secrets.names()).isEmpty();` with
  `assertThat(secrets.names()).allMatch(name -> name.startsWith(DeviceSecrets.PREFIX));`;
- remove `"secrets.json"` and `"secret.key"` from the doesn't-exist checks.

`assertThat(login.loginRequired()).isFalse();` stays: the reset removes the login explicitly.

For each test that asserted the login disappears with the last secret (`JellyfinSetupServiceTest:173`,
`TmdbSetupServiceTest:105,170`, `WorkflowSetupControllerTest:304`, and the three end-to-end tests at the lines listed
under Files):
- flip the assertion to `isTrue()` (the login stays);
- reword the comment, for example `// Disconnecting removes the last credential; the login stays until removed on
  purpose.`
- Where an end-to-end test goes on to act as a device-only visitor, have it log in first with the password it set,
  or remove the password through `POST /setup/password/remove` with that password. Read each test and do the smaller
  of the two.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.storage.SecretStoreTest' --tests 'dev.andre.homecontrol.security.LoginServiceTest' --tests 'dev.andre.homecontrol.core.DeviceSecretsTest'`
Expected: compilation FAILS: no `DeviceSecrets`, `putDeviceSecret`, `setLogin`, `setPassword`…

- [ ] **Step 3: Implement `DeviceSecrets`**

```java
package dev.andre.homecontrol.core;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Credentials a device adapter keeps for a device: a TV's client key, the Android TV keystore's password. They are
 * encrypted at rest with the account credentials but never need the household login. Every name starts with
 * {@link #PREFIX}. Nothing here is ever logged or shown.
 */
public interface DeviceSecrets {

    String PREFIX = "device.";

    Optional<String> deviceSecret(String name);

    void putDeviceSecret(String name, String value);

    void removeDeviceSecrets(Collection<String> names);

    /**
     * A new reference naming one device's secrets: 16 random hex characters, not itself secret. It lives in the
     * adapter's settings, so a merge or split carries it along.
     */
    static String newReference() {
        byte[] bytes = new byte[8];
        Holder.RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Lazily created, once. */
    final class Holder {
        private static final SecureRandom RANDOM = new SecureRandom();

        private Holder() {
        }
    }
}
```

- [ ] **Step 4: Implement the store**

In `SecretStore`:
- Class line: `public class SecretStore implements DeviceSecrets {`.
- Class Javadoc: `Invariant: secrets exist if and only if a login exists` becomes:

  > Two kinds of secrets: names starting with {@code device.} are device secrets, which never need the login.
  > Every other name is an account credential. Invariant: account credentials exist only while a login exists.
  > The login is set and removed on purpose; it never disappears with the last secret.

```java
    /** Adds or replaces secrets. An account credential needs a login. */
    public synchronized void putSecrets(Map<String, String> values) {
        validate(values);
        if (credential == null && values.keySet().stream().anyMatch(SecretStore::isAccountCredential)) {
            throw new IllegalStateException("Set a login password before storing account credentials");
        }
        Map<String, String> next = new HashMap<>(secrets);
        next.putAll(values);
        write(credential, next);
    }

    /** Stores the first account credentials and the login that protects them in one atomic write. */
    public synchronized void putFirstSecrets(Map<String, String> values, LoginCredential newLogin) {
        validate(values);
        if (newLogin == null) {
            throw new IllegalArgumentException("A login is required");
        }
        if (credential != null) {
            throw new IllegalStateException("A login already exists; log in to add more");
        }
        Map<String, String> next = new HashMap<>(secrets);
        next.putAll(values);
        write(newLogin, next);
    }

    /** Never removes the login: that is {@link #removeLogin}'s job alone. */
    public synchronized void removeSecrets(Collection<String> names) {
        Map<String, String> next = new HashMap<>(secrets);
        names.forEach(next::remove);
        if (next.size() == secrets.size()) {
            return;
        }
        write(credential, next);
    }

    public synchronized void setLogin(LoginCredential newLogin) {
        if (newLogin == null) {
            throw new IllegalArgumentException("A login is required");
        }
        if (credential != null) {
            throw new IllegalStateException("A login already exists");
        }
        write(newLogin, secrets);
    }

    /** Refused while account credentials exist: nothing may be stored unprotected. */
    public synchronized void removeLogin() {
        if (hasAccountCredentials()) {
            throw new IllegalStateException("Account credentials need the login");
        }
        if (credential != null) {
            write(null, secrets);
        }
    }

    public synchronized boolean hasAccountCredentials() {
        return secrets.keySet().stream().anyMatch(SecretStore::isAccountCredential);
    }

    public synchronized Set<String> accountCredentialNames() {
        return secrets.keySet().stream().filter(SecretStore::isAccountCredential)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Override
    public synchronized Optional<String> deviceSecret(String name) {
        return secret(requireDeviceName(name));
    }

    @Override
    public synchronized void putDeviceSecret(String name, String value) {
        putSecrets(Map.of(requireDeviceName(name), value));
    }

    @Override
    public synchronized void removeDeviceSecrets(Collection<String> names) {
        names.forEach(SecretStore::requireDeviceName);
        removeSecrets(names);
    }

    private static boolean isAccountCredential(String name) {
        return !name.startsWith(DeviceSecrets.PREFIX);
    }

    private static String requireDeviceName(String name) {
        if (name == null || !name.startsWith(DeviceSecrets.PREFIX)) {
            throw new IllegalArgumentException("Not a device secret name");
        }
        return name;
    }
```

- `accountCredentialNames` returns an unmodifiable view:
  `Collections.unmodifiableSet(new TreeSet<>(…))`, or a `TreeSet` wrapped in `Collections.unmodifiableSortedSet`.
- Imports: `dev.andre.homecontrol.core.DeviceSecrets`, `java.util.TreeSet`, `java.util.Collections`,
  `java.util.stream.Collectors`.

- [ ] **Step 5: Implement the service**

In `LoginService`:
- The class Javadoc becomes `/** The single household password (spec §9): set and removed on purpose, required while
  it exists. */`.
- Add these constants and methods:

```java
    /** What the Account section calls each kind of account credential, by the first part of its name. */
    private static final Map<String, String> ACCOUNTS = Map.of("jellyfin", "Jellyfin", "youtube", "YouTube",
            "tmdb", "TMDB", "sports", "Sports", "workflow", "Workflows");

    /** The sources whose credentials need the login, by name, each once, sorted. */
    public List<String> connectedAccounts() {
        return store.accountCredentialNames().stream()
                .map(name -> ACCOUNTS.getOrDefault(name.substring(0, name.indexOf('.') < 0 ? name.length() : name.indexOf('.')),
                        name))
                .distinct()
                .sorted()
                .toList();
    }

    /** Sets the first login password and logs this browser in. The slow hash runs outside the lock. */
    public void setPassword(String password, String confirmation, HttpServletRequest request) {
        checkNewPassword(password, confirmation);
        LoginCredential credential = newCredential(password);
        synchronized (this) {
            if (store.login().isPresent()) {
                throw new PasswordRejectedException("A login password is already set; change it instead");
            }
            store.setLogin(credential);
            startSession(request, credential);
        }
        changed();
    }

    /**
     * Removes the login password. The accounts are checked first, so a refusal never costs a guess. The current
     * password is checked outside the lock. The login is then removed only if it was not changed meanwhile and no
     * account was connected meanwhile.
     */
    public void removePassword(String current, HttpServletRequest request) {
        LoginCredential login = store.login()
                .orElseThrow(() -> new PasswordRejectedException("There is no login password to remove"));
        refuseWhileAccountsAreConnected();
        if (!verify(current, login)) {
            throw new WrongPasswordException("The current password is wrong");
        }
        synchronized (this) {
            if (!store.login().map(login::equals).orElse(false)) {
                throw new PasswordRejectedException("The password was changed meanwhile; try again");
            }
            refuseWhileAccountsAreConnected();
            store.removeLogin();
        }
        changed();
    }

    private void refuseWhileAccountsAreConnected() {
        List<String> accounts = connectedAccounts();
        if (!accounts.isEmpty()) {
            String names = accounts.size() == 1 ? accounts.getFirst()
                    : String.join(", ", accounts.subList(0, accounts.size() - 1)) + " and " + accounts.getLast();
            throw new PasswordRejectedException("Disconnect " + names + " first: "
                    + (accounts.size() == 1 ? "its" : "their") + " credentials need the login password");
        }
    }
```

- Replace the lambda's prefix expression with a small `private static String account(String secretName)` helper if
  that reads better; the behaviour stays the same.
- `removePassword`'s `request` is unused apart from matching `changePassword`'s shape. Keep it: a later change may end
  this browser's session there. If Sonar flags it (`java:S1172`), drop the parameter from the service and keep it in
  the controller.
- `storeSecrets`'s Javadoc becomes `The first account credentials need a new login password and log this browser in;
  later ones need an already authenticated request.`

- [ ] **Step 6: Update the full-app reset**

In `FullAppReset.reset`, replace the `removeSecrets(names())` line with:

```java
        SecretStore secrets = app.getBean(SecretStore.class);
        app.getBean(LoginService.class).removeSecrets(secrets.accountCredentialNames());
        secrets.removeLogin();
```

Remove `"secrets.json"` and `"secret.key"` from the delete loop, and delete the loop and `delete` helper if nothing is
left in it.

Device secrets stay:
- the keystore password protects the kept `keystore.p12` (Task 11);
- the devices were forgotten above, and their adapters removed their own secrets (Task 10).

Update the Javadoc's "Besides devices, secrets and settings" sentence to say the login is removed on purpose and
device secrets stay with the kept keystore.

- [ ] **Step 7: The in-memory fake for adapter tests**

```java
package dev.andre.homecontrol.testsupport;

import dev.andre.homecontrol.core.DeviceSecrets;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@link DeviceSecrets} in memory, for adapter tests that do not need secrets.json. */
public final class InMemoryDeviceSecrets implements DeviceSecrets {

    private final Map<String, String> values = new ConcurrentHashMap<>();

    @Override
    public Optional<String> deviceSecret(String name) {
        return Optional.ofNullable(values.get(require(name)));
    }

    @Override
    public void putDeviceSecret(String name, String value) {
        values.put(require(name), value);
    }

    @Override
    public void removeDeviceSecrets(Collection<String> names) {
        names.forEach(name -> values.remove(require(name)));
    }

    public Map<String, String> all() {
        return Map.copyOf(values);
    }

    private static String require(String name) {
        if (!name.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a device secret name");
        }
        return name;
    }
}
```

- [ ] **Step 8: Run and watch them pass**

Run: the Step 2 command. Expected: PASS.

Then run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.*' --tests 'dev.andre.homecontrol.security.*' --tests 'dev.andre.homecontrol.web.*' --tests 'dev.andre.homecontrol.testsupport.*'`
Expected: PASS. Where an end-to-end test still expects the login to vanish, apply Step 1's last paragraph to it.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/core/DeviceSecrets.java src/main/java/dev/andre/homecontrol/storage/SecretStore.java \
  src/main/java/dev/andre/homecontrol/security/LoginService.java src/test/java/dev/andre/homecontrol
git commit -m "feat: keep the login until it is removed on purpose, and store device secrets without it"
```

(Check `git status --short` first. `src/test/java/dev/andre/homecontrol` may be staged whole only if every change in
it belongs to this task.)

---

### Task 9: The Account section: set, change and remove the password

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/web/LoginController.java`
- Modify: `src/main/java/dev/andre/homecontrol/security/LoginModelAdvice.java`
- Modify: `src/main/resources/templates/setup.html` (nav link at line 49, section at line 268)
- Modify: `src/test/java/dev/andre/homecontrol/web/LoginControllerTest.java`
- Modify: `src/test/java/dev/andre/homecontrol/web/SetupControllerTest.java` (or create `AccountSectionTest extends
  WebSliceTest` in `web`)

**Interfaces:**
- Consumes: `LoginService.setPassword`, `removePassword` and `connectedAccounts` (Task 8).
- Produces `POST /setup/password/set` with `password` and `confirmation`, and `POST /setup/password/remove` with
  `current`. Both redirect to `/setup` with flash `loginMessage` or `loginError`.
- Produces the model attribute `connectedAccounts` (`List<String>`) on the setup page.

- [ ] **Step 1: Write the failing tests**

In `LoginControllerTest`:

```java
    @Test
    void settingAPasswordReportsSuccessAndNeedsNoGuess() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.setPassword("household password", "household password", request, redirect))
                .isEqualTo("redirect:/setup");

        verify(login).setPassword("household password", "household password", request);
        assertThat(flash(redirect)).containsEntry("loginMessage",
                "Password set. Every browser now needs it to open Home Control.");
    }

    @Test
    void aRejectedNewPasswordIsShown() {
        willThrow(new PasswordRejectedException("The two passwords do not match"))
                .given(login).setPassword("household password", "different", request);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.setPassword("household password", "different", request, redirect);

        assertThat(flash(redirect)).containsEntry("loginError", "The two passwords do not match");
    }

    @Test
    void removingThePasswordReportsSuccessAndClearsTheAddress() {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        assertThat(controller.removePassword("household password", request, redirect)).isEqualTo("redirect:/setup");

        verify(login).removePassword("household password", request);
        assertThat(flash(redirect)).containsEntry("loginMessage",
                "Password removed. Anyone on your network can open Home Control.");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }

    @Test
    void aWrongCurrentPasswordIsAGuessAndTheNextRemovalWaits() {
        willThrow(new WrongPasswordException("The current password is wrong"))
                .given(login).removePassword("guess", request);

        controller.removePassword("guess", request, new RedirectAttributesModelMap());
        RedirectAttributesModelMap second = new RedirectAttributesModelMap();
        controller.removePassword("household password", request, second);

        assertThat((String) flash(second).get("loginError")).startsWith("Too many attempts.");
        verify(login, times(1)).removePassword(any(), any());
    }

    @Test
    void aRefusalWhileAccountsAreConnectedIsNotAGuess() {
        willThrow(new PasswordRejectedException("Disconnect Jellyfin first: its credentials need the login password"))
                .given(login).removePassword("household password", request);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.removePassword("household password", request, redirect);

        assertThat(flash(redirect)).containsEntry("loginError",
                "Disconnect Jellyfin first: its credentials need the login password");
        assertThat(limiter.blockedFor(ADDRESS)).isEmpty();
    }
```

The existing `aWrongCurrentPasswordIsAGuessAndTheNextChangeWaits` shows the limiter's exact API. Match its
`blockedFor` or equivalent call and its flash assertion.

The page states go in a slice test. Add to `SetupControllerTest`, or create
`web/AccountSectionTest extends WebSliceTest` with the same `@Autowired MockMvc` and a `@BeforeEach` like
`SetupControllerTest`'s:

```java
    @Test
    void withoutALoginTheAccountSectionOffersToSetAPassword() throws Exception {
        given(login.loginRequired()).willReturn(false);

        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"#account\"")))
                .andExpect(content().string(containsString("action=\"/setup/password/set\"")))
                .andExpect(content().string(not(containsString("action=\"/setup/password/remove\""))));
    }

    @Test
    void withALoginTheAccountSectionOffersChangeAndRemove() throws Exception {
        given(login.loginRequired()).willReturn(true);
        given(login.isAuthenticated(any(HttpServletRequest.class))).willReturn(true);
        given(login.connectedAccounts()).willReturn(List.of());

        mockMvc.perform(get("/setup"))
                .andExpect(content().string(containsString("action=\"/setup/password\"")))
                .andExpect(content().string(containsString("action=\"/setup/password/remove\"")))
                .andExpect(content().string(not(containsString("action=\"/setup/password/set\""))))
                .andExpect(content().string(not(containsString("<fieldset disabled"))));
    }

    @Test
    void removingIsDisabledAndExplainedWhileAccountsAreConnected() throws Exception {
        given(login.loginRequired()).willReturn(true);
        given(login.isAuthenticated(any(HttpServletRequest.class))).willReturn(true);
        given(login.connectedAccounts()).willReturn(List.of("Jellyfin", "YouTube"));

        mockMvc.perform(get("/setup"))
                .andExpect(content().string(containsString("Disconnect Jellyfin, YouTube first")))
                .andExpect(content().string(containsString("<fieldset disabled")));
    }
```

If the slice's filter chain is not active, drop the `isAuthenticated` stubs. Check how `SetupControllerTest` reaches
`/setup` today. If `<fieldset disabled` renders as `disabled="disabled"`, assert on `disabled` inside the remove form
instead.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.LoginControllerTest' --tests 'dev.andre.homecontrol.web.SetupControllerTest'`
(add `AccountSectionTest` if it was created)
Expected: compilation FAILS: no `setPassword` or `removePassword` on the controller.

- [ ] **Step 3: Implement the controller**

In `LoginController`, add `private static final String SETUP_REDIRECT = "redirect:/setup";` and
`private static final String LOGIN_MESSAGE = "loginMessage";`. Then refactor `changePassword` onto a shared guard and
add the two routes:

```java
    @PostMapping("/setup/password")
    public String changePassword(@RequestParam(required = false) String current, @RequestParam(required = false) String password,
                                 @RequestParam(required = false) String confirmation, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        return guessing(request, redirect, () -> loginService.changePassword(current, password, confirmation, request),
                "Password changed. Other browsers need to log in again.");
    }

    /** No guess is involved, so it is not rate-limited. Only possible while no login exists. */
    @PostMapping("/setup/password/set")
    public String setPassword(@RequestParam(required = false) String password,
                              @RequestParam(required = false) String confirmation, HttpServletRequest request,
                              RedirectAttributes redirect) {
        try {
            loginService.setPassword(password, confirmation, request);
            redirect.addFlashAttribute(LOGIN_MESSAGE, "Password set. Every browser now needs it to open Home Control.");
        } catch (PasswordRejectedException e) {
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage());
        }
        return SETUP_REDIRECT;
    }

    @PostMapping("/setup/password/remove")
    public String removePassword(@RequestParam(required = false) String current, HttpServletRequest request,
                                 RedirectAttributes redirect) {
        return guessing(request, redirect, () -> loginService.removePassword(current, request),
                "Password removed. Anyone on your network can open Home Control.");
    }

    /**
     * Runs an action that checks the current password. Every checked guess counts against the address. A rejected
     * request, a busy verifier or an unexpected failure gives the reservation back.
     */
    private String guessing(HttpServletRequest request, RedirectAttributes redirect, Runnable attempt, String success) {
        String address = request.getRemoteAddr();
        Optional<Duration> blocked = limiter.reserve(address);
        if (blocked.isPresent()) {
            redirect.addFlashAttribute(LOGIN_ERROR, tooManyAttempts(blocked.get()));
            return SETUP_REDIRECT;
        }
        try {
            attempt.run();
            limiter.succeeded(address);
            redirect.addFlashAttribute(LOGIN_MESSAGE, success);
        } catch (WrongPasswordException e) {
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage()); // a wrong guess keeps counting
        } catch (PasswordRejectedException | LoginBusyException e) {
            limiter.release(address);
            redirect.addFlashAttribute(LOGIN_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            limiter.release(address);
            throw e;
        }
        return SETUP_REDIRECT;
    }
```

If `WrongPasswordException` extends `PasswordRejectedException`, keep its catch first, as above (check the class
hierarchy). All the existing `changePassword` tests must pass unchanged.

- [ ] **Step 4: Implement the model attribute and the page**

In `LoginModelAdvice`:

```java
    @ModelAttribute("connectedAccounts")
    public List<String> connectedAccounts() {
        LoginService service = login.getIfAvailable();
        return service == null ? List.of() : service.connectedAccounts();
    }
```

Change its Javadoc to `/** Tells the setup page's Account section whether a login exists and which accounts need it. */`.

In `setup.html`:
- the nav link at line 49 becomes
  `<a href="#account"><span aria-hidden="true">04</span> Account</a>` (no `th:if`);
- the section (lines 268–289) becomes:

```html
    <section id="account">
        <p class="eyebrow">ACCOUNT</p>
        <h2>Keep your home personal</h2>
        <p class="error" th:if="${loginError}" th:text="${loginError}">Wrong password</p>
        <p class="hint" th:if="${loginMessage}" th:text="${loginMessage}">Password changed</p>
        <th:block th:unless="${loginRequired}">
            <p class="hint">Anyone on your network can open Home Control. Set a password to keep it to your
                household. Choose at least 10 characters.</p>
            <form method="post" th:action="@{/setup/password/set}">
                <label>New password
                    <input type="password" name="password" autocomplete="new-password" minlength="10" required>
                </label>
                <label>Confirm new password
                    <input type="password" name="confirmation" autocomplete="new-password" minlength="10" required>
                </label>
                <button type="submit">Set password</button>
            </form>
        </th:block>
        <th:block th:if="${loginRequired}">
            <p class="hint">Update the password used to access Home Control. Choose at least 10 characters.</p>
            <form method="post" th:action="@{/setup/password}">
                <label>Current password
                    <input type="password" name="current" autocomplete="current-password" required>
                </label>
                <label>New password
                    <input type="password" name="password" autocomplete="new-password" minlength="10" required>
                </label>
                <label>Confirm new password
                    <input type="password" name="confirmation" autocomplete="new-password" minlength="10" required>
                </label>
                <button type="submit">Change password</button>
            </form>
            <p class="hint" th:if="${#lists.isEmpty(connectedAccounts)}">Removing the password lets anyone on your
                network open Home Control again.</p>
            <p class="hint" th:unless="${#lists.isEmpty(connectedAccounts)}"
               th:text="|Disconnect ${#strings.listJoin(connectedAccounts, ', ')} first: their credentials need the password.|">
                Disconnect Jellyfin first: their credentials need the password.</p>
            <form method="post" th:action="@{/setup/password/remove}">
                <fieldset th:disabled="${!#lists.isEmpty(connectedAccounts)}">
                    <label>Current password
                        <input type="password" name="current" autocomplete="current-password" required>
                    </label>
                    <button type="submit">Remove password</button>
                </fieldset>
            </form>
            <form method="post" th:action="@{/logout}">
                <button type="submit">Log out</button>
            </form>
        </th:block>
    </section>
```

- [ ] **Step 5: Run and watch them pass**

Run: the Step 2 command, plus `--tests 'dev.andre.homecontrol.web.LoginGatingTest'`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/web/LoginController.java src/main/java/dev/andre/homecontrol/security/LoginModelAdvice.java \
  src/main/resources/templates/setup.html src/test/java/dev/andre/homecontrol/web
git commit -m "feat: let the household set and remove the login password on the setup page"
```

---

### Task 10: webOS client keys and Tizen tokens move to `secrets.json`

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/adapters/webos/WebOsSettings.java`, `WebOsAdapter.java`,
  `WebOsSession.java`, `WebOsPairing.java`, `WebOsConfiguration.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/tizen/TizenSettings.java`, `TizenAdapter.java`,
  `TizenSession.java`, `TizenPairing.java`, `TizenConfiguration.java`
- Modify the tests that construct them: `WebOsAdapterTest`, `WebOsSessionTest`, `WebOsPairingTest`,
  `TizenAdapterTest`, `TizenSessionTest`, `TizenPairingTest`
- Create: `src/test/java/dev/andre/homecontrol/adapters/webos/WebOsKeyMigrationTest.java`,
  `src/test/java/dev/andre/homecontrol/adapters/tizen/TizenTokenMigrationTest.java`
- Create: `src/test/resources/fixtures/devices/devices-v2-tv-keys.json`

**Interfaces:**
- Consumes: `DeviceSecrets` and `InMemoryDeviceSecrets` (Task 8); `DeviceAdapter.migrate` and
  `DeviceManager.start()` (Task 6).
- Produces:
  - `WebOsSettings(String keyRef, String clientKey, String macAddress, boolean macAddressManual)`, with
    `static WebOsSettings of(Device, DeviceSecrets)`, `static String secretName(String keyRef)`,
    `KEY_REF = "keyRef"` and `LEGACY_CLIENT_KEY = "clientKey"`;
  - `TizenSettings(String keyRef, String token, boolean paired, String macAddress, boolean macAddressManual)`, with
    `of(Device, DeviceSecrets)`, `secretName(String keyRef)`, `KEY_REF` and `LEGACY_TOKEN = "token"`;
  - the adapter constructors each gain a trailing `DeviceSecrets secrets`: `WebOsAdapter(…, DeviceSecrets)`,
    `WebOsPairing(…, DeviceSecrets)`, `TizenAdapter(…, DeviceSecrets)` and `TizenPairing(…, DeviceSecrets)`.
  - The session constructors gain `DeviceSecrets secrets` after `LearnedSettings learned`.

- [ ] **Step 1: Write the fixture and the failing tests**

`src/test/resources/fixtures/devices/devices-v2-tv-keys.json`:

```json
[{"id":"lg-1","name":"LG","kind":"WEBOS","host":"192.168.1.30","adapters":{"webos":{"clientKey":"lg-client-key","macAddress":"aa:bb:cc:dd:ee:ff"}},"lastSeen":"2026-09-20T10:00:00Z"},
 {"id":"sam-1","name":"Samsung","kind":"TIZEN","host":"192.168.1.31","adapters":{"tizen":{"paired":"true","token":"sam-token"}},"lastSeen":"2026-09-20T10:00:00Z"}]
```

`WebOsKeyMigrationTest`:

```java
package dev.andre.homecontrol.adapters.webos;

class WebOsKeyMigrationTest {

    @TempDir
    Path dir;

    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    private WebOsAdapter adapter() {
        // build as WebOsAdapterTest does, with `secrets` as the new last argument
    }

    @Test
    void aKeyInTheRegistryMovesToADeviceSecretAtStartup() throws IOException {
        Path file = dir.resolve("devices.json");
        Files.copy(Path.of("src/test/resources/fixtures/devices/devices-v2-tv-keys.json"), file);
        JsonFileDeviceRegistry registry = new JsonFileDeviceRegistry(file);

        try (DeviceManager manager = new DeviceManager(registry, List.of(adapter()), event -> { })) {
            manager.start();
        }

        Map<String, String> settings = new JsonFileDeviceRegistry(file).findById("lg-1").orElseThrow().adapterSettings("webos");
        assertThat(settings).doesNotContainKey("clientKey").containsKey("keyRef")
                .containsEntry("macAddress", "aa:bb:cc:dd:ee:ff");
        assertThat(secrets.deviceSecret(WebOsSettings.secretName(settings.get("keyRef")))).contains("lg-client-key");
        assertThat(Files.readString(file)).doesNotContain("lg-client-key");
    }

    @Test
    void migrationIsIdempotent() {
        Device device = new Device("lg-1", "LG", DeviceKind.WEBOS, "192.168.1.30",
                Map.of("webos", Map.of("clientKey", "lg-client-key")), Instant.now());
        WebOsAdapter adapter = adapter();

        Device once = adapter.migrate(device);
        Device twice = adapter.migrate(once);

        assertThat(twice).isEqualTo(once);
        assertThat(secrets.all()).hasSize(1);
    }

    @Test
    void aMigrationThatCrashedBeforeTheRegistryWasSavedReusesItsReference() {
        Device device = new Device("lg-1", "LG", DeviceKind.WEBOS, "192.168.1.30",
                Map.of("webos", Map.of("clientKey", "lg-client-key", "keyRef", "0123456789abcdef")), Instant.now());

        Device migrated = adapter().migrate(device);

        assertThat(migrated.adapterSettings("webos")).containsEntry("keyRef", "0123456789abcdef").doesNotContainKey("clientKey");
        assertThat(secrets.all()).containsOnlyKeys("device.webos.0123456789abcdef.client-key");
    }

    @Test
    void theSettingsReadTheKeyFromTheSecret() {
        secrets.putDeviceSecret(WebOsSettings.secretName("0123456789abcdef"), "k");
        Device device = new Device("lg-1", "LG", DeviceKind.WEBOS, "h", Map.of("webos", Map.of("keyRef", "0123456789abcdef")),
                Instant.now());

        assertThat(WebOsSettings.of(device, secrets).clientKey()).isEqualTo("k");
        assertThat(WebOsSettings.of(device, secrets).toString()).doesNotContain("k,").contains("clientKey=stored");
    }

    @Test
    void forgettingTheDeviceRemovesItsSecret() {
        secrets.putDeviceSecret(WebOsSettings.secretName("0123456789abcdef"), "k");
        Device device = new Device("lg-1", "LG", DeviceKind.WEBOS, "h", Map.of("webos", Map.of("keyRef", "0123456789abcdef")),
                Instant.now());

        adapter().forget(device);

        assertThat(secrets.all()).isEmpty();
    }

    @Test
    void aMergeCarriesTheReferenceSoThePairingStillWorks() {
        // Through DeviceManager.merge with a second, pairing-free adapter entry (a Cast or probe adapter): register an
        // LG entry with keyRef R and a target device, merge the LG into the target, then assert the target's webos
        // settings still hold R and WebOsSettings.of(target, secrets).clientKey() is the stored key.
    }
}
```

Write the last test out fully in the task, in `DeviceManagerMergeTest`'s style:
- `grep -n "void \|new DeviceManager" src/test/java/dev/andre/homecontrol/device/DeviceManagerMergeTest.java` shows how
  a merge is set up;
- use this test's `adapter()` for `webos`, and the merge test's pairing-free adapter for the target.

`TizenTokenMigrationTest` mirrors the first four tests with:
- `sam-1`, the `tizen` adapter, `token` → `TizenSettings.secretName(ref)`, and `paired` kept as `"true"`;
- `learnedTokenWithoutAReferenceGetsOne`, which drives `TizenSession`'s learned-token path the way `TizenSessionTest`
  already reaches it, and asserts that a `keyRef` was stored through `LearnedSettings` and the token as a secret. If
  that path is not reachable without a fake TV, cover it in `TizenSessionTest` beside the existing learned-token
  test.

In `WebOsPairingTest` and `TizenPairingTest`, change the assertions that read `clientKey`/`token` from the attached
settings: they now read `keyRef` and look the key up in the `InMemoryDeviceSecrets` passed to the pairing. Add:

```java
    @Test
    void repairingTheSameTvReusesItsReference() {
        // pair twice against the same fake TV/host; assert one secret in `secrets.all()` and the same keyRef both times
    }
```

Write it out in each class's style.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.webos.*' --tests 'dev.andre.homecontrol.adapters.tizen.*'`
Expected: compilation FAILS: the constructors and `of(Device, DeviceSecrets)` do not exist.

- [ ] **Step 3: Implement the settings**

`WebOsSettings`:

```java
/**
 * The webOS adapter's settings under {@code adapters.webos} in devices.json. The client key is a device secret named
 * by {@code keyRef}, never in devices.json. Before 2B it was stored there as {@code clientKey}, and
 * {@link WebOsAdapter#migrate} moves it. The key never goes into a log, an error message or a page.
 */
public record WebOsSettings(String keyRef, String clientKey, String macAddress, boolean macAddressManual) {

    public static final String ADAPTER_ID = "webos";
    static final String KEY_REF = "keyRef";
    static final String LEGACY_CLIENT_KEY = "clientKey";

    public static WebOsSettings of(Device device, DeviceSecrets secrets) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        String keyRef = blankToNull(settings.get(KEY_REF));
        String clientKey = keyRef == null ? null : secrets.deviceSecret(secretName(keyRef)).orElse(null);
        return new WebOsSettings(keyRef, clientKey, blankToNull(settings.get(WakeOnLanAdapter.MAC_ADDRESS)),
                "true".equals(settings.get(WakeOnLanAdapter.MAC_ADDRESS_MANUAL)));
    }

    static String secretName(String keyRef) {
        return DeviceSecrets.PREFIX + ADAPTER_ID + "." + keyRef + ".client-key";
    }

    @Override
    public String toString() {
        return "WebOsSettings[keyRef=" + keyRef + ", clientKey=" + (clientKey == null ? "none" : "stored")
                + ", macAddress=" + macAddress + ", macAddressManual=" + macAddressManual + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
```

`TizenSettings`: the same shape. `of` reads `PAIRED_KEY` as today. The token comes from `secretName(keyRef)` =
`DeviceSecrets.PREFIX + "tizen." + keyRef + ".token"`. `LEGACY_TOKEN = "token"` replaces `TOKEN_KEY`.

- [ ] **Step 4: Implement the adapters, sessions and pairings**

`WebOsAdapter`:
- It takes `DeviceSecrets secrets` as its last constructor argument and passes it to each `WebOsSession`.
- It overrides:

```java
    /** Moves a client key still in devices.json into a device secret. Idempotent, and safe to rerun after a crash. */
    @Override
    public Device migrate(Device device) {
        Map<String, String> settings = device.adapterSettings(ADAPTER_ID);
        String legacy = settings.get(WebOsSettings.LEGACY_CLIENT_KEY);
        if (legacy == null) {
            return device;
        }
        Map<String, String> next = new LinkedHashMap<>(settings);
        next.remove(WebOsSettings.LEGACY_CLIENT_KEY);
        if (!legacy.isBlank()) {
            String keyRef = next.computeIfAbsent(WebOsSettings.KEY_REF, key -> DeviceSecrets.newReference());
            secrets.putDeviceSecret(WebOsSettings.secretName(keyRef), legacy);
        }
        return device.withAdapter(ADAPTER_ID, next);
    }

    @Override
    public void forget(Device device) {
        String keyRef = device.adapterSettings(ADAPTER_ID).get(WebOsSettings.KEY_REF);
        if (keyRef != null && !keyRef.isBlank()) {
            secrets.removeDeviceSecrets(List.of(WebOsSettings.secretName(keyRef)));
        }
    }
```

Update the class Javadoc: `The client key is a device secret named in the device's adapter settings.`

`WebOsSession`:
- The constructor gains `DeviceSecrets secrets` after `LearnedSettings learned`, kept in a field.
- In `connect()`:
  - `WebOsSettings settings = WebOsSettings.of(current(), secrets); String clientKey = settings.clientKey();`
  - the learned-key branch becomes
    `secrets.putDeviceSecret(WebOsSettings.secretName(settings.keyRef()), key);` (a key is only registered when
    `keyRef` is set).
- The MAC read at line 343 becomes `WebOsSettings.of(current(), secrets)`.

`WebOsPairing`:
- It takes `DeviceSecrets secrets` last.
- In `pair`, after `register`:

```java
            String keyRef = devices.devices().stream()
                    .filter(existing -> Hosts.same(existing.host(), host))
                    .map(existing -> existing.adapterSettings(WebOsAdapter.ADAPTER_ID).get(WebOsSettings.KEY_REF))
                    .filter(ref -> ref != null && !ref.isBlank())
                    .findFirst()
                    .orElseGet(DeviceSecrets::newReference);
            secrets.putDeviceSecret(WebOsSettings.secretName(keyRef), key);
            Device device = devices.attach(host, deviceName(connection, host, name), DeviceKind.WEBOS,
                    WebOsAdapter.ADAPTER_ID, Map.of(WebOsSettings.KEY_REF, keyRef));
```

(A re-pair of the same TV reuses its reference, so no secret is orphaned. `Hosts.same` is the match
`DeviceMerge.attach` uses.)

`WebOsConfiguration`: both beans take `DeviceSecrets secrets` and pass it on.

Tizen, in the same way:
- `TizenAdapter.migrate` moves `LEGACY_TOKEN`, and `forget` removes `secretName(keyRef)`.
- In `TizenSession.connect()`, `settings = TizenSettings.of(current(), secrets)`. The learned-token branch becomes:

```java
                opened.token().filter(token -> !token.equals(settings.token())).ifPresent(this::storeToken);
```

with

```java
    /** A token the TV issued: stored as a device secret, under a new reference if the device has none yet. */
    private void storeToken(String token) {
        String keyRef = TizenSettings.of(current(), secrets).keyRef();
        if (keyRef == null) {
            keyRef = DeviceSecrets.newReference();
            secrets.putDeviceSecret(TizenSettings.secretName(keyRef), token);
            learned.store(Map.of(TizenSettings.KEY_REF, keyRef));
        } else {
            secrets.putDeviceSecret(TizenSettings.secretName(keyRef), token);
        }
    }
```

- `TizenSession` line 327 becomes `TizenSettings.of(current(), secrets)`.
- In `TizenPairing.pair`, the `CONNECTED` branch keeps `PAIRED_KEY`. When `connection.token()` is present, it reuses a
  reference the same way `WebOsPairing` does, stores the token under it, and puts `KEY_REF` into the settings in place
  of the token.
- `TizenConfiguration` passes `DeviceSecrets` to both beans.

Test constructors: add `new InMemoryDeviceSecrets()` (or the test's shared instance) as the new argument. Where a test
put `clientKey`/`token` into a device's settings to make it paired:
- give the device `keyRef` `0123456789abcdef` instead;
- `putDeviceSecret(secretName("0123456789abcdef"), <old value>)` in the test's `InMemoryDeviceSecrets`.

- [ ] **Step 5: Run and watch them pass**

Run: the Step 2 command, plus `--tests 'dev.andre.homecontrol.device.*'`.
Expected: PASS.

- [ ] **Step 6: Run the full-app tests that pair TVs**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.web.*'`
Expected: PASS. The `SecretStore` bean is the `DeviceSecrets` the configurations receive.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/webos src/main/java/dev/andre/homecontrol/adapters/tizen \
  src/test/java/dev/andre/homecontrol/adapters/webos src/test/java/dev/andre/homecontrol/adapters/tizen \
  src/test/resources/fixtures/devices/devices-v2-tv-keys.json
git commit -m "feat: keep webOS client keys and Tizen tokens encrypted in secrets.json"
```

---

### Task 11: A generated keystore password

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/adapters/androidtv/KeystorePassword.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/CertificateStore.java` (lazy password supplier,
  `opens`, `reprotect`)
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvConfiguration.java`
- Modify: `src/main/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvProperties.java` (no default)
- Modify: `src/main/resources/application.yaml` (`keystore-password: ${SHIELD_KEYSTORE_PASSWORD:}`)
- Modify: `compose.yaml` (drop the `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD: change-me` line)
- Create: `src/test/java/dev/andre/homecontrol/adapters/androidtv/KeystorePasswordTest.java`
- Modify: `AndroidTvModuleSwitchTest` or any context-runner test of `AndroidTvConfiguration` that now needs a
  `DeviceSecrets` bean

**Interfaces:**
- Consumes: `DeviceSecrets` (Task 8) and `AtomicFiles` (Task 1).
- Produces:
  - `CertificateStore(Path file, Supplier<char[]> password)`. The existing `(Path, char[])` constructor delegates to
    it.
  - `static boolean opens(Path file, char[] password)` and
    `static boolean reprotect(Path file, char[] current, char[] next)`, both package-private.
  - `KeystorePassword.resolve(Path keystore, String configured, DeviceSecrets secrets, SecureRandom random)`, which
    returns `char[]`.

- [ ] **Step 1: Write the failing tests**

```java
package dev.andre.homecontrol.adapters.androidtv;

class KeystorePasswordTest {

    @TempDir
    Path dir;

    private final InMemoryDeviceSecrets secrets = new InMemoryDeviceSecrets();

    private Path keystore() {
        return dir.resolve("keystore.p12");
    }

    private void pairUnder(String password) {
        new CertificateStore(keystore(), password.toCharArray()).save("shield-1", TestCredentials.clientCertificate());
    }

    private char[] resolve(String configured) {
        return KeystorePassword.resolve(keystore(), configured, secrets, new SecureRandom());
    }

    @Test
    void aConfiguredPasswordIsUsedAsItIs() {
        pairUnder("mine");

        assertThat(resolve("mine")).isEqualTo("mine".toCharArray());
        assertThat(secrets.all()).isEmpty();
    }

    @Test
    void aFreshInstallGeneratesAndStoresAPassword() {
        char[] password = resolve("");

        assertThat(new String(password)).hasSizeGreaterThanOrEqualTo(40);
        assertThat(secrets.deviceSecret(KeystorePassword.SECRET)).contains(new String(password));
        assertThat(resolve(null)).isEqualTo(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"shield", "change-me"})
    void aKeystoreUnderAnOldDefaultIsReprotectedAndItsPairingStillLoads(String old) {
        pairUnder(old);

        char[] password = resolve("");

        assertThat(CertificateStore.opens(keystore(), old.toCharArray())).isFalse();
        assertThat(new CertificateStore(keystore(), password).load("shield-1")).isPresent();
    }

    @Test
    void aStoredPasswordWhoseKeystoreIsStillUnderTheOldDefaultIsReprotected() {
        pairUnder("shield");
        secrets.putDeviceSecret(KeystorePassword.SECRET, "stored-before-a-crash");

        char[] password = resolve("");

        assertThat(password).isEqualTo("stored-before-a-crash".toCharArray());
        assertThat(new CertificateStore(keystore(), password).load("shield-1")).isPresent();
    }

    @Test
    void aKeystoreThatOpensWithNoneOfThemStopsStartupByName() {
        pairUnder("something else");

        assertThatThrownBy(() -> resolve(""))
                .isInstanceOf(StorageException.class)
                .hasMessage("keystore.p12 does not open with the stored password or an old default; set"
                        + " home-control.androidtv.keystore-password to the password it was created with, or delete"
                        + " keystore.p12 and pair the Android TV devices again");
    }

    @Test
    void theStoreAsksForThePasswordOnlyWhenItNeedsIt() {
        AtomicInteger asked = new AtomicInteger();
        CertificateStore store = new CertificateStore(keystore(), () -> {
            asked.incrementAndGet();
            return "pw".toCharArray();
        });

        store.verifyReadable();
        assertThat(store.load("shield-1")).isEmpty();
        assertThat(asked).hasValue(0);

        store.save("shield-1", TestCredentials.clientCertificate());
        store.load("shield-1");
        assertThat(asked).hasValue(1);
    }
}
```

`TestCredentials.clientCertificate()` is the helper `DeviceManagerTest` uses. Import it from wherever it lives.

In `LegacyConfigurationStartupTest`, nothing changes: `--shield.keystore-password=old-secret` is still the configured
password.

Add to `AndroidTvModuleSwitchTest` (or `ModulesOffSmokeTest`, whichever starts the full app with Android TV on):

```java
    @Test
    void aFreshInstallStartsWithoutAnySecretOrLogin() {
        // start as the class does; assert secrets.deviceSecret("device.androidtv.keystore-password") is empty
        // (nothing paired yet: the password is created with the keystore) and login().isEmpty()
    }
```

Write it out in that class's style.

- [ ] **Step 2: Run them and watch them fail**

Run: `scripts/gradle.sh test --tests 'dev.andre.homecontrol.adapters.androidtv.*'`
Expected: compilation FAILS: no `KeystorePassword`, `opens`, or supplier constructor.

- [ ] **Step 3: Implement the store's lazy password and the two helpers**

In `CertificateStore`:

```java
    private final Path file;
    private final Supplier<char[]> passwordSource;
    private char[] password;

    public CertificateStore(Path file, char[] password) {
        this(file, () -> password);
    }

    /** The password is asked for once, the first time the keystore is opened or written. */
    public CertificateStore(Path file, Supplier<char[]> password) {
        this.file = file;
        this.passwordSource = password;
    }

    private char[] password() {
        if (password == null) {
            password = passwordSource.get();
        }
        return password;
    }
```

- Every use of the `password` field in `load`, `save`, `write` and `openOrEmpty` becomes `password()`. All of them are
  already inside `synchronized` methods.
- `openOrEmpty` with no file calls `keyStore.load(null, null)`, so an empty store never asks for the password.
- `verifyReadable` returns early when the file does not exist, as today. Otherwise it calls `password()` before its
  `try`, so a `StorageException` from resolving keeps its own message.

```java
    /** True when the keystore file opens with {@code password}. */
    static boolean opens(Path file, char[] password) {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore.getInstance("PKCS12").load(in, password);
            return true;
        } catch (IOException | GeneralSecurityException _) {
            return false;
        }
    }

    /** Re-saves the keystore and each of its keys under {@code next}; false when it does not open with {@code current}. */
    static boolean reprotect(Path file, char[] current, char[] next) {
        KeyStore keyStore;
        try (InputStream in = Files.newInputStream(file)) {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, current);
        } catch (IOException | GeneralSecurityException _) {
            return false;
        }
        try {
            for (String alias : Collections.list(keyStore.aliases())) {
                if (keyStore.isKeyEntry(alias)) {
                    Key key = keyStore.getKey(alias, current);
                    keyStore.setKeyEntry(alias, key, next, keyStore.getCertificateChain(alias));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keyStore.store(out, next);
            AtomicFiles.write(file, out.toByteArray(), true);
            return true;
        } catch (IOException | GeneralSecurityException e) {
            throw new StorageException("Could not re-protect keystore " + file + "; check file permissions", e);
        }
    }
```

`KeystorePassword`:

```java
package dev.andre.homecontrol.adapters.androidtv;

/**
 * The Android TV keystore's password. A configured one ({@code home-control.androidtv.keystore-password}, or the old
 * {@code SHIELD_KEYSTORE_PASSWORD}) is used as it is. Otherwise one is generated once and kept as a device secret. A
 * keystore still under a password Home Control shipped is re-protected under it. The password is stored before the
 * keystore is re-protected, and every start re-protects a keystore that does not open with it, so a crash in between
 * costs nothing.
 */
final class KeystorePassword {

    static final String SECRET = DeviceSecrets.PREFIX + "androidtv.keystore-password";
    /** What application.yaml and compose.yaml shipped before the password was generated. */
    static final List<String> OLD_DEFAULTS = List.of("shield", "change-me");

    private static final Logger log = LoggerFactory.getLogger(KeystorePassword.class);

    private KeystorePassword() {
    }

    static char[] resolve(Path keystore, String configured, DeviceSecrets secrets, SecureRandom random) {
        if (configured != null && !configured.isBlank()) {
            return configured.toCharArray();
        }
        String password = secrets.deviceSecret(SECRET).orElseGet(() -> {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String generated = Base64.getEncoder().encodeToString(bytes);
            secrets.putDeviceSecret(SECRET, generated);
            return generated;
        });
        if (Files.exists(keystore) && !CertificateStore.opens(keystore, password.toCharArray())) {
            boolean reprotected = OLD_DEFAULTS.stream()
                    .anyMatch(old -> CertificateStore.reprotect(keystore, old.toCharArray(), password.toCharArray()));
            if (!reprotected) {
                throw new StorageException("keystore.p12 does not open with the stored password or an old default; set"
                        + " home-control.androidtv.keystore-password to the password it was created with, or delete"
                        + " keystore.p12 and pair the Android TV devices again");
            }
            log.info("Protected {} with a generated password, kept encrypted in secrets.json", keystore);
        }
        return password.toCharArray();
    }
}
```

`AndroidTvConfiguration`:

```java
    @Bean
    public CertificateStore certificateStore(DataDirectory data, AndroidTvProperties properties, DeviceSecrets secrets,
                                             SecureRandom random) {
        Path keystore = data.resolve(DataDirectory.KEYSTORE);
        return new CertificateStore(keystore,
                () -> KeystorePassword.resolve(keystore, properties.keystorePassword(), secrets, random));
    }
```

- `AndroidTvProperties`: remove `@DefaultValue("shield")` from `keystorePassword`. Its Javadoc becomes `{@code
  home-control.androidtv.*}: the keystore password (generated when empty) and the connection's waits.`
- `application.yaml`: `keystore-password: ${SHIELD_KEYSTORE_PASSWORD:}`, with the comment
  `# Empty: generated on first use and kept in secrets.json. SHIELD_KEYSTORE_PASSWORD, the old name, still sets it.`
- `compose.yaml`: delete the `HOME_CONTROL_ANDROIDTV_KEYSTORE_PASSWORD: change-me` line. If it leaves an empty
  `environment:` block, delete that too.

If `DeviceManagerTest.properties()` or another test builds `AndroidTvProperties` positionally with `"shield"`, it
keeps working: it passes the password explicitly.

Context-runner tests of `AndroidTvConfiguration` now need a `DeviceSecrets` and a `SecureRandom` bean. Register
`InMemoryDeviceSecrets` and `new SecureRandom()` with `.withBean(…)` where the runner is built.

- [ ] **Step 4: Run and watch them pass**

Run: the Step 2 command, plus `--tests 'dev.andre.homecontrol.config.*' --tests 'dev.andre.homecontrol.device.*'`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/andre/homecontrol/adapters/androidtv src/main/resources/application.yaml compose.yaml \
  src/test/java/dev/andre/homecontrol/adapters/androidtv
git commit -m "feat: generate the Android TV keystore password and re-protect keystores under the old defaults"
```

---

### Task 12: Docs, ADR and the full build

**Files:**
- Create: `docs/adr/0002-versioned-data-files-and-device-secrets.md`; modify `docs/adr/README.md` (its table)
- Modify: `docs/user/configuration.md`, `docs/user/devices.md`, and every user or developer doc hit by
  `grep -rln "keystore-password\|change-me\|last secret\|device-only\|login password\|secrets.json\|devices.json\|sources.json" docs/user docs/dev README.md casaos`
- Modify: `docs/dev/architecture.md` if it describes storage, validation in the registry, or the frozen counts

- [ ] **Step 1: Write the ADR**

Copy the header of `docs/adr/0001-cast-sender.md` (date, status, context), then:

```markdown
# 0002: Versioned data files and device secrets

- Date: 2026-09-29
- Status: Accepted

## Context

Every store under /data wrote its own file its own way, without a directory sync or owner-only permissions.
- Only `secrets.json` had a real format version.
- `devices.json` was a bare array.
- `sources.json` flattened nested data into prefixed strings.
- webOS client keys and Tizen tokens sat in plain text in `devices.json`.
- The Android TV keystore was protected by a password everyone knew.

## Decision

- **One writer and one versioned layer.** Every file under /data is written through `AtomicFiles`: an owner-only temp
  file, forced to disk, moved atomically, with the directory synced. Every JSON store holds a
  `VersionedJsonFile`, which:
  - reads the file once and caches it;
  - migrates it forward on that read, keeping the original once as `<name>.v<n>.json`;
  - refuses a file a newer Home Control wrote.
- **New versions:** `devices.json` 3 (`{"version": 3, "devices": [...]}`) and `sources.json` 2 (typed sections;
  version 1 sections wait under `unmigrated` until their source converts them).
- **Two kinds of secrets.**
  - Names starting with `device.` are device secrets and need no login: TV pairing keys and the keystore password.
  - Every other secret is an account credential and needs the household login.
  - The login is set and removed on purpose.
- **TV pairing keys are device secrets.** A TV's pairing key is stored under a random reference kept in the device's
  settings, so merge and split carry it along.
- **The keystore password is generated** unless one is configured. A keystore still under a shipped default is
  re-protected.

## Consequences

- An upgraded install cannot be downgraded by swapping the image alone. The `<name>.v<n>.json` copies allow a manual
  rollback.
- A store no longer notices its file being changed behind its back. Edit files only while the app is stopped.
- Installs that pair an Android TV, webOS or Tizen TV now get `secrets.json` and `secret.key` even without a login.
```

Add the row `| [0002](0002-versioned-data-files-and-device-secrets.md) | Versioned data files, and device secrets that
need no login | Accepted | 2026-09-29 |` to the README's table.

- [ ] **Step 2: Update the user and developer docs**

- **`docs/user/configuration.md`:**
  - The `home-control.androidtv.keystore-password` row's default becomes "generated on first use and kept in
    `secrets.json`".
  - A short note: an install that set a password keeps it, and one that used `shield` or `change-me` is re-protected
    automatically.
  - Under data files, list the version and backup names.
- **`docs/user/devices.md`, the keystore paragraph at line 14:** the password is generated unless set, and keeping the
  data directory keeps pairings.
- **The login docs** (found by the grep):
  - the password is set and removed in Setup → Account;
  - it no longer disappears when the last source is disconnected;
  - it cannot be removed while a source that needs it is connected.
- **`docs/dev/architecture.md`:** where it names the registry's adapter checks or storage, say that adapters validate
  and migrate their own settings through `DeviceAdapter.validate`/`migrate`, and link ADR 0002.

Follow each doc's existing style. Keep statements short and exact.

- [ ] **Step 3: The full build**

Run: `scripts/gradle.sh build`
Expected: BUILD SUCCESSFUL.
- Test count at or above 2,802 (the 2A figure).
- `git status --short src/test/archunit-store` shows only the smaller stores from Tasks 5 and 6.

If a test fails, fix its cause with systematic debugging, never the assertion alone. Record any deviation as a ruling
in the ledger.

- [ ] **Step 4: Browser tests compile**

Run: `scripts/gradle.sh compileE2eJava` (or the source set `scripts/e2e.sh` names; check `build.gradle.kts`).
Expected: BUILD SUCCESSFUL.
- If a browser test drives the Account section or reads `devices.json`, update it the way Task 9 or Task 6 changed
  the page or file.

- [ ] **Step 5: Commit**

```bash
git add docs/adr docs/user docs/dev README.md
git commit -m "docs: record the versioned data files and device secrets, and document the login and keystore changes"
```

(Add only the files the grep and Step 1 touched.)
