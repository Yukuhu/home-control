# Shield Web Remote — final review record (2026-08-29)

Outcome: **ready to merge**, 81 tests passing. This file preserves the two
analyses that drove the final fix wave, so the decisions outlive the scratch
workspace they were written in.

## Known issues shipping unfixed

## Residual — what ships unfixed

### 1. The stated latch window is wrong by ~2x (LOW, documentation only)

`DeviceSession.java:34-39` and `final-fix-report.md:137` both claim that on the default ramp "the
fifth verdict lands about half a minute after the first". It does not.

With `reconnect-initial-delay-seconds: 1` (`src/main/resources/application.yaml`), `scheduleReconnect`
(`DeviceSession.java:179-187`) schedules with the *current* backoff and then doubles, so the delays
*between* verdicts are 1s, 2s, 4s, 8s. Verdict 5 therefore lands **~15 seconds** after verdict 1, not
~31s. The 31s figure sums five delays when only four elapse; the same off-by-one method gives 3s for
the old threshold of 3, which is why it matched the review's own baseline and went unnoticed.
Threshold 6 would be needed for ~31s.

**What ships:** the code is right and 5x wider than before (3s → 15s); only the comment and the fix
report are wrong. **What it means for the reboot claim:** 15s does *not* span a full Shield reboot
(~30-60s), so the stated justification does not hold as written. The threshold is nonetheless
defensible for a different reason the comment does not give: during most of a reboot the port is
closed, which yields `ConnectException` → network class → counter reset (addendum 1), so the only
window that actually accumulates ambiguous verdicts is the short phase where the device accepts TLS
but tears the connection down. 15s of *that* phase is a plausible margin. The comment should be
corrected to say 15s and to rest on the reset rather than on spanning the whole reboot.

### 2. `adopt()` can still leave a stale session overwriting the live badge (MEDIUM, pre-existing)

`DeviceSessionManager.startSession` (`DeviceSessionManager.java:73-84`) closes only a session with
the *same* device id. `PairingService.deviceId(host)` derives the id from the host, so re-pairing at
a changed address inside one process run mints a new id: `adopt()` starts a session for the new
device and leaves the old one running, retrying the dead address and publishing id-less
`DISCONNECTED` events that overwrite the new device's badge. That is exactly the failure item D
describes, reached through the live re-pair path instead of boot.

**What ships:** item D's stated requirement (`startRegisteredDevices` starts only `registry.first()`)
is implemented in full and tested, and a restart now recovers cleanly — so the boot path is fixed and
the re-pair path is not. The true root cause, `DeviceStateChangedEvent` carrying no device id, is
explicitly out of scope for this wave. A one-line close of sessions whose id is not
`registry.first()` inside `adopt()` would shut the remaining hole without touching the event.

### 3. Initial-send vs fan-out ordering on `/events` (LOW, pre-existing, not worsened)

`StateController.events()` (`StateController.java:24-34`) registers the emitter via `subscribe()`
*before* sending the current state on the request thread. A broadcast that lands between the
registration and that send delivers a newer state first, so the new tab can end up showing the older
one.

**What ships:** the window is narrow — both paths read the same live volatile, so the initial send
usually carries an equal-or-newer state — and any subsequent transition corrects the badge. Item B's
queueing makes the correct order *more* likely, not less, since the fan-out is now delayed relative
to `publishEvent`. Sending the initial state before registering, or registering under the same lock,
would close it.

### 4. Nit — timing-tolerant assertion in the new broadcaster test

`DeviceStateBroadcasterTest.dropsASubscriberWhoseSendFailsWithoutDisturbingTheOthers` asserts
`broken.count()` immediately after `await(healthy == 2)`. The fan-out sends to `healthy` first, so a
failure to remove `broken` could in principle be observed a moment too early. Awaitility's 100ms poll
makes this effectively unreachable in practice, but the assertion is timing-tolerant rather than
causal.

## Deferred-findings triage from the whole-branch review

## 4. Deferred-Findings Triage — complete

`deferred-findings.md` has 24 lines: 22 findings, plus 2 per-task count lines
("0 minors deferred, 1 parked)" after Task 9 and "2 minors deferred, 1 parked)" after
Task 12) which are tallies, not findings and need no call. All 22 findings below, in file
order, including both parked items.

| # | Item | Call |
|---|---|---|
| 1 | T1 Thymeleaf "no templates" WARN / Mockito agent warning | fine to leave |
| 2 | T2 `ProtoSchemaTest` comment "Pairing messages are always..." wording | fine to leave |
| 3 | T4 `CertificateStore` keeps password `char[]` by reference | fine to leave — single trusted caller |
| 4 | T4 `loadOrCreate` reads the keystore twice on a miss | fine to leave |
| 5 | T5 `compute()` normalises the code twice per call | fine to leave |
| 6 | T5 no test for a 6-char non-hex code | should fix soon — behaviour already right, 3-line test pins a user-facing path |
| 7 | T6 `TlsSockets.connect` catches blanket `Exception` | MUST FIX BEFORE MERGE — folded into the Critical; same method, same edit |
| 8 | T6 `FakePairingServer.serve()` swallows exceptions | fine to leave (already accepted) |
| 9 | T7 `FakeRemoteServer` swallows `handle()` exceptions | fine to leave (already accepted) |
| 10 | T8 no startup sweep for stray temp files from a crashed write | fine to leave — `writeAll` cleans up the failure path it can see |
| 11 | T9 PARKED: residual TOCTOU where `close()` flips the flag after the guard | fine to leave — window is one doomed callback on a session being torn down; the `RejectedExecutionException` catch covers the consequence that matters |
| 12 | T10 hardcoded 1000ms `requestServiceInfo` timeout | fine to leave |
| 13 | T11 none outstanding (coercion Minor subsumed) | nothing to decide |
| 14 | T12 broadcaster does not `completeWithError` on a failed send | MUST FIX BEFORE MERGE — see section 1; the unchecked exception reaching the session's control thread can wedge it permanently |
| 15 | T12 PARKED: `submit()`'s trailing `cancel()` re-reads the volatile | fine to leave — needs two tabs racing; worst case is a cancelled in-flight pairing the user restarts. Nothing corrupted, no credential lost. Two-line fix if you want it: compare against the local `current` before nulling |
| 16 | T12 FIX 4 may guard an unreachable path since Spring never... | fine to leave — cheap guard against behaviour you do not control |
| 17 | T13 keyboard-triggered `fetch()` has no `.catch`, so a 409 is silent | should fix soon — click toasts, keypress does nothing; reads as a dead app |
| 18 | T13 icon-only buttons lack `aria-label`/`title` | should fix soon — the whole transport row is bare glyphs |
| 19 | T13 `app.js` does no null-checks on `getElementById` | fine to leave — safe while `app.js` loads only from `remote.html` |
| 20 | T13 no CSS breakpoints, untested above phone widths | fine to leave |
| 21 | T14 README config table omits `shield.reconnect-initial-delay-seconds` | fine to leave |
| 22 | T14 `.dockerignore` does not exclude `.superpowers/` | should fix soon — one line, keeps the SDD dir (incl. these review artifacts) out of the build context |

Tally: 2 must-fix-before-merge (#7, #14), 4 should-fix-soon (#6, #17, #18, #22), 15 fine to
leave, 1 nothing-to-decide (#13). Both parked items are fine to leave — those are the two I
looked at hardest, and neither corrupts state nor loses a credential.

Not from this file, but belongs in the same fix wave as a separate item (per your note):
the `UNPAIRED_CONFIRMATION_THRESHOLD` latching window — three strikes on a 1s/2s ramp
completes about three seconds after the first verdict, which a device reboot clears easily.
Make the latch span a plausible reboot window (five strikes on the existing ramp is roughly
31s) or gate it on elapsed time since the first ambiguous verdict. Should fix soon, not a
blocker.

## 5. Architectural Assessment

## Architectural assessment

## 5. Architectural Assessment

The layering holds and the protocol boundary is real, not aspirational: grep across main and
test finds no `com.google.protobuf` or generated-type import outside `protocol`, and the
outward surface is `RemoteKey`, `PairingResult`, `DisconnectCause`, `X509Certificate`. The
Python-sidecar fallback in spec section 11 would genuinely be a drop-in. `MessageStream`,
`PairingDigest` and `RemoteConnection` are the strongest code here — the digest's
zero-stripping is pinned by a fixed vector exactly as section 5.2 demands, and the comments
explaining why `InvalidProtocolBufferException` must be unwrapped are the kind of thing that
saves the next maintainer an afternoon.

The weak seam is where the carefully single-threaded `DeviceSession` meets Spring's
synchronous event publisher. `DeviceSession` goes to real trouble to funnel every mutation
onto one thread — and then hands that thread to `SseEmitter.send`, which blocks and throws
unchecked. Both of my top two findings live there. It is a boundary neither task owned, and
it is the one place I would change the design rather than patch the code.

The second structural gap is device identity: `DeviceStateChangedEvent` carries no device id,
so the "registry abstraction now, multi-device later" claim of section 3 is weaker than it
looks — the SSE stream would need reworking, not just the UI, and today a duplicate registry
entry already makes it incoherent. Worth adding the id to the event now, while it costs
nothing.

Yes, I would be comfortable maintaining this. Fix the connect-failure classification, harden
the broadcaster loop, close the pairing socket on failure, and add the SSE test section 9
already asked for, and it is ready.
