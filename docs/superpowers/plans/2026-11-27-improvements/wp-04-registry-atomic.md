# WP-04: Registry writes atomic, keys encrypted with modules off (R6) — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** Merging or splitting a device writes `devices.json` once, so a full disk or a killed container can no longer
lose a TV's pairing half-way. Forgetting a Bluetooth speaker no longer stalls pairings, discovery and learned settings
behind a slow BlueZ. A webOS or Tizen key never stays in plain text in `devices.json`, and leaves `secrets.json` with
its device, whichever modules are on. The build refuses a registry write outside the device package.

**Findings:**

- [ARCH-1](../../reviews/2026-11-27-improvement-catalogue.md#arch-1): all three parts. (a) `DeviceRegistry.replace`
  makes merge and split one write. (b) `DeviceAdapter.afterForget` moves Bluetooth's BlueZ call out from under the
  registry lock. (c) An always-on `TvPairingKeys` migrates and removes TV keys whatever the module state. ADR 0002
  names it as an exception, together with `JsonFileDeviceRegistry.withoutTvKeys`.
- [DEV-9](../../reviews/2026-11-27-improvement-catalogue.md#dev-9) (merged into ARCH-1): DC2, DC3, DC4 and the startup
  sweep of orphaned device secrets. Its AC8 part (Android TV keys left in `keystore.p12`) is left out (decision 6).
- [DATA-5](../../reviews/2026-11-27-improvement-catalogue.md#data-5) (merged): the failing-write tests and the entry
  order that a delete followed by a save left. Its looping default for fakes is not taken (decision 4).
- [DATA-10](../../reviews/2026-11-27-improvement-catalogue.md#data-10) (merged): the always-on bean in
  `adapters.support`, and a conservative sweep that runs only after the whole registry has been read (PR 4).
- [ARCH-V1](../../reviews/2026-11-27-improvement-catalogue.md#arch-v1) (merged): the webOS and Tizen half. Forget
  removes the key while the module is off. The Android TV half goes with AC8.
- [ARCH-6](../../reviews/2026-11-27-improvement-catalogue.md#arch-6): (a), a strict ArchUnit rule against `save`,
  `delete` and `replace` outside `device`. (b), a read-only view, waits for ARCH-12's `connect` change (WP-33), as
  the finding proposes.

**Approach:** Three changes, each made at the seam its finding names:

1. `core.DeviceRegistry` gains `replace(saved, deleted)`. `JsonFileDeviceRegistry` runs it as one
   `VersionedJsonFile.update`, which is one `AtomicFiles.write`, and `save` and `delete` become its one-element cases.
   `Enrollment.merge` and `split` call it once each. The entries end up in the order the old calls left them.
2. `core.DeviceAdapter` gains `default void afterForget(Device)`. `Enrollment.forget` queues it on `AfterLock`, which
   runs it last, outside the `RegistryLock`: after the handle is closed and the screens are told.
   `BluetoothSpeakerAdapter` moves `bluez.remove` there.
3. The TV key bookkeeping moves from the two adapters into one always-on class, `adapters.support.TvPairingKeys`. It
   is built on `PairingKeys` and a new `PairingKeys.Family` enum. webOS `clientKey` becomes
   `device.webos.<ref>.client-key`, and Tizen `token` becomes `device.tizen.<ref>.token`. `device` reaches it through
   a new core interface, `core.StoredPairingKeys`, which `Devices.assemble` takes. `Enrollment` calls it under the
   lock, from `migrate` and from `forget`, whether or not the TV's adapter is on. PR 4 adds a startup sweep,
   `removeUnnamed`.

The core seam keeps `device` free of adapter knowledge: it still depends on `core`, `storage` and `config` alone. The
adapter-specific names sit in `adapters.support` beside `PairingKeys`, which both TV adapters share and which may
depend on neither of them (`adaptersAreIndependent`). The literals in `withoutTvKeys` stay where they are, so that
`device` keeps knowing no adapter package. ADR 0002 names both places. The tests of both read
`src/test/resources/fixtures/devices/devices-v2-tv-keys.json`, which keeps them in step.

**Depends on / touches:** Depends on nothing. It touches:

- WP-22 (DEV-3, PROD-5): Rename and Change address use `replace` for any change that writes several devices. WP-22
  also takes AC8 (decision 6).
- WP-33 (ARCH-12): takes ARCH-6 (b).
- WP-31: also edits `ArchitectureTest`, so whichever of the two lands second rebases.
- WP-17 (TEST-2, DATA-1): its upgrade fixtures should include TV keys with the TV modules off, and its snapshot runs
  before the sweep.
- WP-03 and WP-16: they redact the new WARN line.
- [ADR 0002](../../../adr/0002-versioned-data-files-and-device-secrets.md) is amended (decision 1).

**Effort:** M overall, at its upper end. PR 1: S. PR 2: S. PR 3: M. PR 4: S.

## Before you start

- Re-check against current main. At 6a253e4, `git diff --stat 3932f9b HEAD -- src protocols core` is empty, and every
  finding holds:
  - `Enrollment.merge` deletes and then saves (lines 373-378), and `split` saves twice (lines 416-417).
    `DeviceRegistry` has only `findAll`, `findById`, `first`, `save` and `delete`.
  - `Enrollment.forget` calls `adapter.forget` inside `synchronized (lock)` (lines 167-187), and
    `BluetoothSpeakerAdapter.forget` calls `bluez.remove`, whose D-Bus reply timeout is `bluez-timeout: 45s`
    (`application.yaml` line 114).
  - `Enrollment.migrate` runs only the switched-on adapters (lines 126-139). Only `WebOsAdapter` and `TizenAdapter`
    move or remove TV keys, in `migrate` and `forget`.
  - Two tests pin DC4. `WebOsKeyMigrationTest.aKeyInTheRegistryMovesToADeviceSecretAtStartup` asserts that the file
    still `contains("sam-token")`, and `TizenTokenMigrationTest.aTokenInTheRegistryMovesToADeviceSecretAtStartup` that
    it still `contains("lg-client-key")`.
  - No ArchUnit rule covers calls to `DeviceRegistry`. Outside `device`, only the TV adapters and their sessions hold
    one, and they only read it.

  Repeat the checks with `git log --oneline 3932f9b..origin/main -- core/src/main/java/dev/andre/homecontrol/core
  src/main/java/dev/andre/homecontrol/device src/main/java/dev/andre/homecontrol/adapters`. Then run
  `grep -rnE 'registry\.(save|delete)\(' src/main/java | grep -v '/device/'`, which must print nothing.
- Owner decisions. Ask these up front:
  1. **ADR change: amend ADR 0002 or write a new one?** Recommended: amend 0002 in place, with an `**Amended:**` line
     and one Decision bullet (text in Task 5). The change qualifies 0002's own promises, "the registry no longer
     knows any adapter" and "forgetting the device removes it". A new ADR would leave 0002 saying something untrue.
  2. **Product rule: may code run for a switched-off module's data?** This is an exception to "modules can be switched
     off", and the rule's text stays as it is. Recommended: yes. `TvPairingKeys` holds no protocol code, opens no
     socket and creates no bean of a TV module. Without it, "secrets are encrypted at rest" would depend on the module
     switches. ARCH-1's alternative, refusing to start or warning, leaves the key in plain text.
  3. **Should TV keys be handled in one place?** Recommended: yes. `WebOsAdapter` and `TizenAdapter` lose their
     `migrate` and `forget` overrides. The always-on class runs first, which would leave those overrides as dead
     copies of it.
  4. **Should `replace` go into the core interface, and with a default?** Recommended: add it with no default. Save
     before delete (ARCH-1's fallback) fixes merge only, and split needs `replace` anyway. DATA-5's looping default
     would bring the two writes back for every later implementation. The only other implementation is
     `EnrollmentTest.FullDisk`.
  5. **Should a startup sweep (PR 4) remove TV keys that no device names?** It would be the only automatic deletion of
     stored data. Recommended: yes, with three limits. It touches only `device.webos.<ref>.client-key` and
     `device.tizen.<ref>.token`. It runs once in `Enrollment.start`, after migration and before anything connects or
     pairs. It does nothing while the registry is empty, because a missing `devices.json` is more likely a mistake than
     a household with keys and no TV. It cleans up after half-failed merges, forgets made while a module was off, and
     crashed migrations.
  6. **Where do AC8 and the Android TV half of ARCH-V1 go?** AC8 is a key that pairing writes before the TV answers.
     The Android TV half is an alias left behind when a TV is forgotten with its module off. Recommended: WP-22, whose
     DEV-3 (AC2's re-pair by fingerprint) rewrites `PairingService.begin` and the alias rules. A keystore sweep needs
     the module's `CertificateStore`, and the keystore stays encrypted meanwhile. WP-22's row in the index notes this.
  7. **Should Forget still wait for BlueZ before it answers?** Recommended: yes. `afterForget` runs in the request's
     own thread, so only that request waits, for at most `bluez-timeout`. Unpairing in the background would be work
     after the response, which "commands are ephemeral" rules out.

## Global constraints

- **Commands are ephemeral.** `afterForget` runs in the caller's thread before `forget` returns. Nothing is queued or
  retried. When it fails, the failure is logged and the device stays forgotten.
- **State is JSON under `/data`, written atomically.** `replace` is one `AtomicFiles.write`: if it fails, the old file
  and the old snapshot stay. The `RegistryLock` is held only for in-memory work and local disk writes (`devices.json`,
  `secrets.json`, `keystore.p12`).
- **Secrets are encrypted at rest.** After the first start no `clientKey` or `token` is left in `devices.json`,
  whatever modules are on. Nothing logs a key, and the sweep logs a count, not names.
- **Existing installs upgrade in place.** `devices.json` stays at version 3, a v2 file with the TV modules off is
  migrated on its first start, and its `devices.v2.json` backup stays redacted. The secret names, `keyRef` and the
  legacy fields `clientKey` and `token` keep their spelling, because installed files hold them
  (`PairingKeysTest.theSecretNamesAreThoseInstallsAlreadyHold`).
- **Modules can be switched off.** A TV module that is off leaves no bean, pairing, setup section or route
  (`SmartTvModulesOffTest.noTvAdapterOrPairingExists`). `adapters.support` is no switchable module, so
  `HomeControlApplicationTest.noSwitchableModuleNeedsAnotherModulesBean` stays green.
- **Architecture.** `device` reaches the key bookkeeping only through `core.StoredPairingKeys`. `adapters.support`
  imports neither `adapters.webos` nor `adapters.tizen`, and `core` stays JDK-only. The new rule is strict.
- **Tests** go in the package and module of the code they test, with `core` tests in `core/src/test/java`. The
  existing `fixtures/devices/` recordings, `FakeBluezClient` and `InMemoryDeviceSecrets` are enough.
- `scripts/gradle.sh build` is green at the end of every task. Nothing a browser shows changes, so `scripts/e2e.sh` is
  not needed.
- Commits follow Conventional Commits, with the scope `devices` the log uses. Stage only the task's files
  (`git add <paths>`, never `-A`). Each commit ends with the session's `Co-Authored-By` and `Claude-Session` lines.

## Review focus

1. **Merge and split write once, and a failed write changes nothing.** Pinned by
   `EnrollmentTest.aMergeOnADiskWithRoomForOneWriteLosesNoDevice`, `aSplitOnADiskWithRoomForOneWriteLosesNoConnection`
   and `JsonFileDeviceRegistryTest.aReplaceThatCannotBeWrittenChangesNothing`.
2. **No adapter I/O under the lock.** Pinned by `EnrollmentTest.anAdapterFinishesForgettingOutsideTheLock`, which
   adopts another device while a speaker's unpairing is held, by
   `BluetoothSpeakerAdapterTest.forgetLeavesTheHostAlone`, and by step 13 of `BluetoothSpeakerEndToEndTest`.
3. **TV keys with the module off.** Pinned by `TvPairingKeysTest.anUpgradeWithBothTvModulesOffLeavesNoKeyInDevicesJson`
   and `forgettingATvWhileItsModuleIsOffRemovesItsKey`, and in the modules-off context by
   `SmartTvModulesOffTest.aTvForgottenWhileItsModuleIsOffTakesItsKeyAlong`. The two DC4 assertions are reversed.
4. **Installed keys keep their names.** Pinned by `PairingKeysTest.theSecretNamesAreThoseInstallsAlreadyHold`. The
   TV end-to-end and session tests stay unchanged.
5. **The sweep is conservative.** Pinned by `TvPairingKeysTest.theKeystorePasswordAndOtherNamesAreKept`,
   `nothingIsRemovedWhileNoDeviceIsRegistered` and `aKeyLeftByAMigrationThatCrashedIsRemovedOnTheNextStart`.
6. **A registry write outside `device` fails the build.** Pinned by
   `ArchitectureTest.theRegistryWriteRuleCatchesAWriterOutsideTheDevicePackage`.

## File structure

- **Create**
  - `core/src/main/java/dev/andre/homecontrol/core/StoredPairingKeys.java`
  - `src/main/java/dev/andre/homecontrol/adapters/support/TvPairingKeys.java`, `PairingKeysConfiguration.java`
  - `core/src/test/java/dev/andre/homecontrol/core/StoredPairingKeysTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/support/PairingKeysTest.java`, `TvPairingKeysTest.java`
- **Modify**
  - `core/src/main/java/dev/andre/homecontrol/core/`: `DeviceRegistry.java`, `DeviceAdapter.java`, `DeviceSecrets.java`
  - `src/main/java/dev/andre/homecontrol/device/`: `JsonFileDeviceRegistry.java`, `Enrollment.java`, `Devices.java`,
    `RegistryLock.java`
  - `src/main/java/dev/andre/homecontrol/`: `HomeControlConfiguration.java`, `adapters/support/PairingKeys.java`,
    `adapters/bluetooth/BluetoothSpeakerAdapter.java`, `adapters/webos/WebOsSettings.java`,
    `adapters/webos/WebOsAdapter.java`, `adapters/tizen/TizenSettings.java`, `adapters/tizen/TizenAdapter.java`,
    `storage/SecretStore.java`
  - `docs/adr/0002-versioned-data-files-and-device-secrets.md`, `docs/dev/architecture.md`, `docs/user/devices.md`
- **Test**
  - `core/src/test/java/dev/andre/homecontrol/core/DeviceAdapterTest.java`
  - `src/test/java/dev/andre/homecontrol/`: `ArchitectureTest.java`, `HomeControlConfigurationTest.java`,
    `device/EnrollmentTest.java`, `device/StubAdapter.java`, `device/JsonFileDeviceRegistryTest.java`,
    `adapters/bluetooth/BluetoothSpeakerAdapterTest.java`, `adapters/webos/WebOsKeyMigrationTest.java`,
    `adapters/tizen/TizenTokenMigrationTest.java`, `web/SmartTvModulesOffTest.java`, `storage/SecretStoreTest.java`,
    `testsupport/InMemoryDeviceSecrets.java`
  - These stay unchanged and must stay green: `DevicesMergeTest`, `DevicesTest`, `BluetoothSpeakerEndToEndTest`,
    `WebOsEndToEndTest`, `TizenEndToEndTest`, `WebOsPairingTest`, `TizenPairingTest`, `ModulesOffSmokeTest`.

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(devices): merge and split a device in one registry write` | 1, 2 | S |
| 2 | `fix(devices): let an adapter finish forgetting a device outside the registry lock` | 3 | S |
| 3 | `fix(devices): move and remove TV pairing keys whatever module is on` | 4, 5 | M |
| 4 | `fix(devices): remove TV pairing keys that no device names at startup` | 6 | S |

PRs 1, 2 and 3 can merge in any order. PRs 2 and 3 both edit `Enrollment.forget`, so whichever lands second rebases,
and PR 4 needs PR 3. The catalogue proposes one PR for ARCH-1; four keep each behaviour change reviewable on its own,
and they can be squashed if the owner prefers.

## Tasks

The full paths are under File structure.

### Task 1: Merge and split in one registry write

**Files:** Modify `DeviceRegistry`, `JsonFileDeviceRegistry` and `Enrollment`. Test `JsonFileDeviceRegistryTest` and
`EnrollmentTest`.

**Interfaces:** `DeviceRegistry` gets a new method with no default,
`void replace(Collection<Device> saved, Collection<String> deleted)`. Its javadoc: "Saves `saved` and deletes `deleted`
in one write: all of it reaches the store, or none. Each saved device replaces the entry with its id. An id saved
twice, or both saved and deleted, is refused before anything is written."

- [ ] **Step 1: Write the failing test.**
  - `JsonFileDeviceRegistryTest`, with a helper `device(id, host)` built like `shield()`:
    - `replaceSavesAndDeletesTogether`: save `a`, `b` and `c`, then `replace(List.of(device("a", "10.0.0.50")),
      List.of("b"))`. A registry reopened on the file holds `containsExactly("c", "a")`, the order a delete and a save
      left, and `a` is at 10.0.0.50.
    - `anIdThatIsSavedAndDeletedAtOnceIsRefused`: `IllegalArgumentException`
      `Device a is written twice in one change`, and the file is unchanged.
    - `aReplaceThatCannotBeWrittenChangesNothing`: save `a` and `b`, then replace `devices.json` with a non-empty
      directory, so the atomic rename fails, for root too. `replace` throws `StorageException`, and `findAll()`
      still gives the old `a` and `b`.
  - `EnrollmentTest`. `FullDisk` gets `roomFor(int writes)`, an `AtomicInteger` that `write()` counts down, and a
    `replace` that costs one write and delegates:
    - `aMergeOnADiskWithRoomForOneWriteLosesNoDevice`: the setup of
      `aMergeThatCannotBeSavedLeavesTheSourceConnected`, then `roomFor(1)` and `merge("a", "b")`. The reopened file
      holds only `a`, with adapters `one` and `two`.
    - `aSplitOnADiskWithRoomForOneWriteLosesNoConnection`: `tv` with `one`, and `two` at `host=10.0.0.9`, then
      `roomFor(1)` and `split("tv", "two")`. The file holds `tv` with `one`, and the new device with `two`.
    - `aSplitThatCannotBeSavedLeavesTheDeviceWhole`: with `full = true`, `split` throws, `tv` keeps both adapters,
      and its handles stay open.
    - `aMergeThatCannotBeSavedLeavesTheSourceConnected` also asserts that `a` and `b` are still registered unchanged.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.device.EnrollmentTest' --tests 'dev.andre.homecontrol.device.JsonFileDeviceRegistryTest'`.
  Compilation fails, because there is no `replace`. To watch the defect itself, add only the budget and the two
  room-for-one tests first. At HEAD they fail with `IllegalStateException: No space left on device`, and `b` is gone
  from the file.
- [ ] **Step 3: Implement.** `save(device)` becomes `replace(List.of(device), List.of())`, and `delete(id)` becomes
  `replace(List.of(), List.of(id))`:

  ```java
  /** One write: the entries left keep their order and the saved ones follow, as a delete and then saves left them. */
  @Override
  public void replace(Collection<Device> saved, Collection<String> deleted) {
      Set<String> gone = Set.copyOf(deleted);
      Set<String> written = new HashSet<>();
      for (Device device : saved) {
          if (gone.contains(device.id()) || !written.add(device.id())) {
              throw new IllegalArgumentException("Device " + device.id() + " is written twice in one change");
          }
      }
      file.update(devices -> {
          List<Device> next = new ArrayList<>(devices);
          next.removeIf(existing -> gone.contains(existing.id()) || written.contains(existing.id()));
          next.addAll(saved);
          return List.copyOf(next);
      });
  }
  ```

  `Enrollment.merge` calls `registry.replace(List.of(merged), List.of(sourceId))`. Its comment says that this is one
  write, so a failure leaves both devices as they were, and that credentials move with the settings, so nothing calls
  `adapter.forget()`. `split` calls `registry.replace(List.of(rest, split), List.of())`.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.device.*'`, `scripts/gradle.sh :core:test` and `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** None.
- [ ] **Step 6: Commit.** `fix(devices): merge and split a device in one registry write`. The body explains that a
  failure between the two writes lost the source's entry, its `keyRef` and its MAC (DC2).

### Task 2: Only the device package writes the registry

**Files:** Modify `ArchitectureTest` and `docs/dev/architecture.md`.

- [ ] **Step 1: Write the failing test.** A rule that passes on clean code may match nothing, so prove that it fires.
  Add a nested test class `RegistryWriter` whose `static void rename(DeviceRegistry registry, Device device)` calls
  `registry.save(device)`; the main import (`DoNotIncludeTests`) never sees it. Then add
  `@ArchTest static void theRegistryWriteRuleCatchesAWriterOutsideTheDevicePackage(JavaClasses imported)`. It asserts
  that `imported` contains `JsonFileDeviceRegistry`, and that `onlyTheDevicePackageWritesTheRegistry.check(…)` on
  `new ClassFileImporter().importClasses(RegistryWriter.class)` throws an `AssertionError` with `DeviceRegistry.save`.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.ArchitectureTest'`.
  Compilation fails, because there is no such rule.
- [ ] **Step 3: Implement.** Add the rule next to `onlyTheConfigurationReachesIntoDevice`:

  ```java
  @ArchTest
  static final ArchRule onlyTheDevicePackageWritesTheRegistry = noClasses()
          .that().resideOutsideOfPackage("dev.andre.homecontrol.device..")
          .should().callMethodWhere(target(nameMatching("save|delete|replace"))
                  .and(target(owner(assignableTo(DeviceRegistry.class)))))
          .because("every registry write runs under the RegistryLock, in step with the connections' generations; "
                  + "adapters read the registry and store what their sessions learn through LearnedSettings");
  ```

- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh build`. Both are green: no class
  outside `device` writes the registry today.
- [ ] **Step 5: Update docs.** In the Package rules table of `docs/dev/architecture.md`, add after the `device` row:
  "Only `device` writes the device registry (`DeviceRegistry.save`, `delete`, `replace`): every write runs under its
  `RegistryLock` | strict".
- [ ] **Step 6: Commit.** `test: only the device package may write the device registry`.

### Task 3: An adapter finishes forgetting a device after the registry lock

**Files:** Modify `DeviceAdapter`, `Enrollment`, `RegistryLock`, `BluetoothSpeakerAdapter` and
`docs/dev/architecture.md`. Test `DeviceAdapterTest`, `StubAdapter`, `EnrollmentTest` and
`BluetoothSpeakerAdapterTest`.

**Interfaces:** `DeviceAdapter` gets `default void afterForget(Device device) { }`. Its javadoc: "Work outside this
process once the device is forgotten, such as unpairing a speaker on the host. It runs after the registry lock is
released, the connection is closed and the screens are told, so it may wait. A failure is logged, and the device stays
forgotten." The javadoc of `forget` gains: "Runs under the registry lock, so local work only. Anything that waits on
the network or on another process belongs in `afterForget`."

- [ ] **Step 1: Write the failing test.**
  - `DeviceAdapterTest.anAdapterWithoutStoredStateForgetsValidatesAndMigratesNothing` also calls `afterForget`.
  - `StubAdapter` gets `final List<String> finished`, which its `afterForget` fills.
  - `EnrollmentTest.anAdapterFinishesForgettingOutsideTheLock`: the adapter's `afterForget` opens a `finishing` latch
    and waits on `release`, as `heldResolver` waits. While `forget("jbl")` is held there on `background`,
    `adopt(device("tv", …))` on `background` finishes within 5 s. `jbl` is gone, its handle is closed and its
    DISCONNECTED event is published. After `release`, `finished` is `["jbl"]`.
  - `anAdapterThatFailsToFinishForgettingLeavesTheDeviceForgotten`: `afterForget` throws `IllegalStateException`.
    `forget` returns normally, the device is gone, and the event was published.
  - `aForgetThatFailsLeavesTheDeviceConnected` also asserts that `finished` is empty.
  - `BluetoothSpeakerAdapterTest`: `forgetUnpairsOnTheHost` and `forgetNeverFails` become `afterForget…` and call
    `afterForget`. The new `forgetLeavesTheHostAlone` asserts that `forget` leaves `bluez.calls()` empty.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.device.EnrollmentTest' --tests
  'dev.andre.homecontrol.adapters.bluetooth.BluetoothSpeakerAdapterTest'`. Compilation fails, because `StubAdapter`'s
  `@Override` of `afterForget` overrides nothing.
- [ ] **Step 3: Implement.** In `Enrollment`, add a private `record Forgotten(DeviceAdapter adapter, Device device)`.
  `AfterLock` gets a `List<Forgotten>` and `afterForget(adapter, device)`, and its `run()` ends like this:

  ```java
  removed.forEach(connections::closeRetired);
  connects.forEach(connections::complete);
  removed.forEach(id -> events.publishEvent(new DeviceStateChangedEvent(id, DeviceState.initial())));
  // Last: unpairing on the host may wait for another process, and the screens already show the device gone.
  forgotten.forEach(Enrollment::finishForgetting);
  ```

  - `finishForgetting` catches `RuntimeException`. As `discovered()` does, it logs `Could not finish forgetting {}
    through the {} adapter: {}` at WARN with `e.toString()`, and the stack at DEBUG.
  - `forget` queues `after.afterForget(adapter, device)` next to `adapter.forget(device)`, before `registry.delete`.
    A forget that fails therefore never runs it.
  - `BluetoothSpeakerAdapter.forget` becomes `afterForget`, with the same body.
  - The `Enrollment` javadoc's phase 3 and the `RegistryLock` javadoc both name `afterForget`.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh :core:test --tests
  'dev.andre.homecontrol.core.DeviceAdapterTest'`, `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.BluetoothSpeakerEndToEndTest'` (step 13 still ends with `remove <address>`) and
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** In the `device` row of `docs/dev/architecture.md`, "connects and publishes events after
  it" gains "; so is an adapter's work outside the process when a device is forgotten (`afterForget`, such as unpairing
  a speaker in BlueZ)".
- [ ] **Step 6: Commit.** `fix(devices): let an adapter finish forgetting a device outside the registry lock`. The body
  explains that BlueZ's `RemoveDevice` ran under the lock, with a 45 s reply timeout (DC3).

### Task 4: Name the TV pairing-key families in one place

A refactor that changes no behaviour. It gives Task 5 the key names without the adapter packages.

**Files:** Modify `PairingKeys`, `WebOsSettings`, `WebOsAdapter`, `TizenSettings` and `TizenAdapter`. Create
`PairingKeysTest`. Test `WebOsKeyMigrationTest` and `TizenTokenMigrationTest`.

**Interfaces:** all in `PairingKeys`.
- New: `public enum Family { WEBOS("webos", "clientKey", "client-key"), TIZEN("tizen", "token", "token") }`, with
  `adapterId()`, `legacyField()` and `secretName(keyRef)`. `secretName` returns
  `DeviceSecrets.PREFIX + adapterId + "." + keyRef + "." + suffix`.
- `PairingKeys(Family, DeviceSecrets)` replaces `PairingKeys(String, UnaryOperator<String>, DeviceSecrets)`.
- `migrate(Device)` replaces `migrate(Device, String)`.

`WebOsSettings.keys` and `secretName` delegate to `Family.WEBOS`, and `LEGACY_KEY_FIELD` goes. `TizenSettings` does
the same with `TIZEN`, and `LEGACY_TOKEN` goes. `ADAPTER_ID` stays a literal in both, because `adapters.support` cannot
reach it.

- [ ] **Step 1: Write the failing test.**
  - `PairingKeysTest.theSecretNamesAreThoseInstallsAlreadyHold` asserts the exact strings
    `device.webos.0123456789abcdef.client-key` and `device.tizen.0123456789abcdef.token`.
  - `theLegacyFieldsAreThoseOlderVersionsWrote` asserts `clientKey` and `token`.
  - `WebOsKeyMigrationTest.theKeyFamilyIsThisAdapters` asserts that `Family.WEBOS.adapterId()` equals
    `WebOsSettings.ADAPTER_ID`. `TizenTokenMigrationTest` does the same for `TIZEN`.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.PairingKeysTest' --tests 'dev.andre.homecontrol.adapters.webos.*' --tests
  'dev.andre.homecontrol.adapters.tizen.*'`. Compilation fails, because there is no `PairingKeys.Family`.
- [ ] **Step 3: Implement** the interfaces. Until Task 5 removes them, `WebOsAdapter.migrate` and
  `TizenAdapter.migrate` call `keys.migrate(device)`.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.web.WebOsEndToEndTest' --tests 'dev.andre.homecontrol.web.TizenEndToEndTest'` and
  `scripts/gradle.sh build`. No other test changes.
- [ ] **Step 5: Update docs.** None.
- [ ] **Step 6: Commit.** `refactor: name the TV pairing-key families in one place`.

### Task 5: TV keys are migrated and removed whatever module is on

**Files:** Create `StoredPairingKeys`, `StoredPairingKeysTest`, `TvPairingKeys`, `PairingKeysConfiguration` and
`TvPairingKeysTest`. Modify `Enrollment`, `Devices`, `HomeControlConfiguration`, `WebOsAdapter`, `TizenAdapter`, the
javadoc of `DeviceAdapter`, and the three docs. Test `EnrollmentTest`, `HomeControlConfigurationTest`,
`WebOsKeyMigrationTest`, `TizenTokenMigrationTest` and `SmartTvModulesOffTest`.

**Interfaces:**

```java
/**
 * The pairing keys device entries name, looked after by the device package whatever adapter module is on: moved out
 * of the registry at startup and removed with their device, so they stay encrypted at rest (ADR 0002). Called under
 * the registry lock, so an implementation does local work only.
 */
public interface StoredPairingKeys {
    /** Looks after no keys: for wiring whose devices hold none. */
    StoredPairingKeys NONE = new StoredPairingKeys() { };

    /** Moves a key an entry still holds in the registry into a device secret. Idempotent; startup saves a change. */
    default Device migrate(Device device) { return device; }

    /** Removes the keys {@code device}'s entries name, unless another of the {@code registered} devices names one. */
    default void forget(Device device, List<Device> registered) { }
}
```

- `public final class TvPairingKeys implements StoredPairingKeys`, with the constructor `TvPairingKeys(DeviceSecrets)`.
  It holds one `PairingKeys` per `Family`, folds `migrate` over them, and calls `forget` on each.
- `PairingKeysConfiguration` is `@Configuration(proxyBeanMethods = false)`, with no `@ConditionalOnModule`. It declares
  `@Bean StoredPairingKeys storedPairingKeys(DeviceSecrets secrets)`.
- New: `Devices.assemble(DeviceRegistry, List<DeviceAdapter>, StoredPairingKeys, ApplicationEventPublisher)`. The
  three-argument form passes `NONE`, for tests whose devices hold no keys, so the other test callers stay unchanged.
- The `Enrollment` constructor takes `StoredPairingKeys` after the adapters. `HomeControlConfiguration.devices` takes
  the bean and passes it on.
- `WebOsAdapter` and `TizenAdapter` lose `migrate`, `forget`, their `keys` field and the `PairingKeys` import. They keep
  `registry`, which their sessions read.

- [ ] **Step 1: Write the failing test.**
  - `StoredPairingKeysTest.noneMovesAndRemovesNothing`.
  - `TvPairingKeysTest`, on an `InMemoryDeviceSecrets` and a `@TempDir`:
    - `anUpgradeWithBothTvModulesOffLeavesNoKeyInDevicesJson`: copy `devices-v2-tv-keys.json` and start
      `Devices.assemble(registry, List.of(), keys, …)`, with no adapter. The file contains neither `lg-client-key` nor
      `sam-token`. Both entries carry a `keyRef` whose secret holds the key, and `macAddress` and `paired` are kept.
    - `forgettingATvWhileItsModuleIsOffRemovesItsKey` (`@EnumSource(PairingKeys.Family.class)`): register an entry
      with a `keyRef`, store its secret, and call `forget` through the assembled `enrollment()`. `secrets.all()` is
      empty.
    - `aDeviceWithoutATvEntryIsLeftAlone`.
    - Moved here from the adapter tests, with the same assertions: `migrationIsIdempotent` (`@EnumSource`),
      `aMigrationThatCrashedBeforeTheRegistryWasSavedMovesTheKeyAgainAndLeavesTheFirstSecretUnused`,
      `anEntryWithBothTheKeyAndAReferenceKeepsTheReference`, `aPairingWithoutATokenNeedsNoSecret` and
      `forgettingOneOfTwoEntriesThatShareAReferenceKeepsTheKeyForTheOther` (`@EnumSource`). Both adapters'
      `forgettingTheDeviceRemovesItsSecret` become the `forgettingATvWhileItsModuleIsOffRemovesItsKey` above.
  - `WebOsKeyMigrationTest`: the startup and merge tests assemble with `new TvPairingKeys(secrets)`. The startup test
    ends with `doesNotContain("lg-client-key").doesNotContain("sam-token")`, and the moved tests go.
    `TizenTokenMigrationTest` changes the same way.
  - `EnrollmentTest`: `wire` passes `NONE`, and an overload takes a `StoredPairingKeys`. In
    `aKeyThatCannotBeRemovedLeavesTheDeviceRegisteredAndConnected`, the keys' `forget` throws. `forget` rethrows, and
    the device stays registered with its handle open.
  - `SmartTvModulesOffTest.aTvForgottenWhileItsModuleIsOffTakesItsKeyAlong`, with every module off: autowire
    `DeviceEnrollment` and `DeviceSecrets`, store `Family.WEBOS.secretName(ref)`, adopt an LG entry with that `keyRef`
    and forget it. The secret is gone. A `finally` removes it anyway, so the shared context stays clean.
  - `HomeControlConfigurationTest` passes `NONE`, and its `getMethod("devices", …)` names `StoredPairingKeys.class`.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.TvPairingKeysTest' --tests 'dev.andre.homecontrol.device.EnrollmentTest'
  --tests 'dev.andre.homecontrol.web.SmartTvModulesOffTest'`. Compilation fails, because there is no
  `StoredPairingKeys`. To watch DC4 itself, reverse only the `sam-token` assertion at HEAD: it fails with
  `not to contain "sam-token"`.
- [ ] **Step 3: Implement** the interfaces.
  - `Enrollment.migrate` starts from `pairingKeys.migrate(device)` and then runs the switched-on adapters.
  - `Enrollment.forget` calls `pairingKeys.forget(device, registry.findAll())` before the adapter loop, with the
    comment "whatever module is on: a switched-off TV's key leaves with its device". It runs before `registry.delete`,
    as `adapter.forget` did, so a key that cannot be removed leaves the device registered.
  - Javadoc: phase 2 of `Enrollment`; `WebOsSettings` and `TizenSettings` (`TvPairingKeys` moves the legacy field);
    and the example in `DeviceAdapter.migrate` ("renaming a setting an older version wrote").
  - The comment in `JsonFileDeviceRegistryTest.theBackupLeavesOutTheTvKeysThatNowLiveOnlyInSecrets` becomes
    `// moved to secrets by TvPairingKeys at startup`.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh :core:test`, `scripts/gradle.sh test
  --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.adapters.*' --tests
  'dev.andre.homecontrol.Home*' --tests 'dev.andre.homecontrol.web.*EndToEndTest'` and `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.**
  - ADR 0002 gets `**Amended:** <merge date> — TV pairing keys are looked after whatever module is on.` under its
    date. After "The adapters check and migrate their own settings" it gets:

    ```markdown
    - **TV pairing keys are looked after whatever module is on.** `adapters.support.TvPairingKeys`, which the device
      package calls through `core.StoredPairingKeys`, moves a webOS `clientKey` or Tizen `token` still in
      `devices.json` into a device secret when the device manager starts, and removes a TV's key when the device is
      forgotten, even while that TV's module is off. It holds no protocol code. It and
      `JsonFileDeviceRegistry.withoutTvKeys` are the named exceptions to "the registry knows no adapter"; a new kind of
      TV with a pairing key adds itself to `PairingKeys.Family` and to `withoutTvKeys`.
    ```

    Consequences gains: "Switching a TV module off no longer leaves its key in plain text, nor behind a device
    forgotten meanwhile."
  - `docs/dev/architecture.md`: the `device` row, after "(`DeviceAdapter.validate`, `migrate`)", gains "and has
    `core.StoredPairingKeys` move TV keys out of `devices.json` whatever module is on". The `adapters` row, after "TV
    pairing keys kept as device secrets", gains ", which the always-on `TvPairingKeys` migrates and removes with their
    device". The "Largest class" measure gets `Enrollment`'s new length (480 lines at HEAD; the table says 453).
  - `docs/user/devices.md`, after "Switch a module off with `HOME_CONTROL_WEBOS_ENABLED=false` or
    `HOME_CONTROL_TIZEN_ENABLED=false`.": "Its TVs stay registered, with their pairing keys encrypted in
    `data/secrets.json`; **Forget** on one of them removes its key too."
- [ ] **Step 6: Commit.** `fix(devices): move and remove TV pairing keys whatever module is on`. The body names DC4
  and ARCH-V1.

### Task 6: Remove the TV keys no device names, once at startup

**Files:** Modify `DeviceSecrets`, `StoredPairingKeys`, `SecretStore`, `PairingKeys`, `TvPairingKeys`, `Enrollment` and
ADR 0002. Test `InMemoryDeviceSecrets`, `SecretStoreTest`, `PairingKeysTest`, `TvPairingKeysTest` and
`StoredPairingKeysTest`.

**Interfaces:**
- `DeviceSecrets.deviceSecretNames()` returns `Set<String>`. Its javadoc: "The names of the stored device secrets,
  never their values". `SecretStore` filters `names()` by `PREFIX`.
- `StoredPairingKeys.removeUnnamed(List<Device> registered)` is a default no-op.
- `PairingKeys.Family.referenceIn(String secretName)` returns `Optional<String>`.
- `PairingKeys.unnamed(List<Device> registered)` returns `List<String>`: this family's names whose reference no entry
  holds, sorted.

- [ ] **Step 1: Write the failing test.**
  - `PairingKeysTest.aSecretNameGivesBackItsReference`: `abc` from `device.webos.abc.client-key`.
  - `otherNamesGiveNoReference` is parameterized and gives empty for `WEBOS` on `device.webos.abc.token`,
    `device.tizen.abc.client-key`, `device.webos..client-key`, `device.webos.a.b.client-key` and
    `device.androidtv.keystore-password`.
  - `TvPairingKeysTest`, each test starting `Devices.assemble(registry, List.of(), keys, …)`:
    - `aKeyNoDeviceNamesIsRemovedAtStartup`: `lg-1` names R1, and the secrets are webOS R1 and R2 and Tizen R3. Only
      R1 is left.
    - `theKeystorePasswordAndOtherNamesAreKept`: `device.androidtv.keystore-password` and `device.webos.R4.other`
      survive.
    - `nothingIsRemovedWhileNoDeviceIsRegistered`.
    - `aKeyLeftByAMigrationThatCrashedIsRemovedOnTheNextStart`: the v2 fixture, plus a secret under a reference the
      file does not name. Afterwards `secrets.all()` holds exactly the two keys its entries now name. This proves that
      the sweep runs after the migration.
  - `SecretStoreTest.deviceSecretNamesLeaveOutAccountCredentials`: `store(null)` with a login, an account credential
    and two device secrets lists only the two.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.support.*' --tests 'dev.andre.homecontrol.storage.SecretStoreTest'`. Compilation
  fails, because there is no `referenceIn` and no `deviceSecretNames`.
- [ ] **Step 3: Implement.**
  - `referenceIn` strips `PREFIX + adapterId + "."` and `"." + suffix`. It returns empty unless both match and the rest
    is non-empty and has no dot.
  - `TvPairingKeys.removeUnnamed` returns at once when `registered` is empty, with the comment "a missing or replaced
    devices.json is likelier a mistake than keys without a TV". Otherwise it gathers each family's `unnamed`, and if
    there are any, it calls `secrets.removeDeviceSecrets` once and logs `Removed {} TV pairing keys that no registered
    device names any more` at INFO, with the count only.
  - `Enrollment.start` calls `pairingKeys.removeUnnamed(registry.findAll())` inside the lock, after the migration loop.
    The comment says that every key is now under the reference its entry names. `start` is the `Devices` bean's init
    method, so it runs before the web server takes a request and before discovery starts. A pairing, which stores its
    key before it registers the entry, cannot be under way.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh :core:test`, `scripts/gradle.sh test
  --tests 'dev.andre.homecontrol.device.*' --tests 'dev.andre.homecontrol.web.*EndToEndTest'` and
  `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** ADR 0002's new bullet gains: "At startup, after every entry is migrated and before
  anything connects, it also removes any TV key that no registered device names. It never does so while the registry
  is empty, and it touches only names of the form `device.webos.<ref>.client-key` and `device.tizen.<ref>.token`."
- [ ] **Step 6: Commit.** `fix(devices): remove TV pairing keys that no device names at startup`.

## Done when

- [ARCH-1](../../reviews/2026-11-27-improvement-catalogue.md#arch-1) is resolved in full, with the details of
  [DEV-9](../../reviews/2026-11-27-improvement-catalogue.md#dev-9),
  [DATA-5](../../reviews/2026-11-27-improvement-catalogue.md#data-5),
  [DATA-10](../../reviews/2026-11-27-improvement-catalogue.md#data-10) and
  [ARCH-V1](../../reviews/2026-11-27-improvement-catalogue.md#arch-v1). Only AC8 and the Android TV keystore half are
  left out, for WP-22.
- [ARCH-6](../../reviews/2026-11-27-improvement-catalogue.md#arch-6) (a) is resolved, and (b) is left out for WP-33.
- Every test named under Review focus exists and passes. Secret names and legacy fields are unchanged, and the TV
  adapters no longer migrate or forget keys themselves.
- ADR 0002, `docs/dev/architecture.md` and `docs/user/devices.md` describe the change.
- `scripts/gradle.sh build` is green after every commit, and `scripts/check-commits.sh` accepts the commits.
- This package's row in [the index](README.md) is updated. If the owner agrees to decision 6, WP-22's row notes AC8.

## Out of scope

- **AC8 and the Android TV half of ARCH-V1** go to WP-22 (decision 6). Its DEV-3 rewrites `PairingService.begin` and
  the alias rules, and a keystore sweep needs the module's `CertificateStore`.
- **ARCH-6 (b), a read-only registry view**, goes to WP-33 with ARCH-12's `connect` change. Until then, the rule keeps
  the adapters to reads.
- **Refusing to start, or warning, over a plaintext key of a switched-off module** (ARCH-1's alternative) is not done.
  The key is migrated instead.
- **A whole-install upgrade test, and a snapshot before the sweep**, go to WP-17 (TEST-2, DATA-1).
- **Redacting the new WARN line** goes to WP-03 (SEC-1) and WP-16 (OPS-3).
- **The rest of R6:** DC5 goes to WP-10 (ARCH-2), and ST1 to ST4 go to WP-06 (DATA-7).
- **Batching the startup migration's per-device saves** is not done. Each save is atomic, and the migration is
  idempotent.
