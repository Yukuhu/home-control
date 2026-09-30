# Phase 2C, PR 1: The Guarded Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One `GuardedHttpClient` in `sources.http`, with one address policy, one URL parser and one set of error kinds,
carrying calendars and workflows.

**Architecture:** `ContentSourceException.Kind` replaces the five source `Kind` enums. `OutboundAddressPolicy` and
`HttpUrls` replace the two address policies and four URL parsers. `GuardedHttpClient` generalizes
`WorkflowHttpClient`'s worker-and-deadline model on the Phase 0.1 `VettedHttpClients` helper; workflows and calendars
become thin users of it.

**Tech Stack:** Java 25, Apache HttpClient 5 (classic API), JUnit 5, AssertJ, `testsupport.FakeHttpServer`.

**Spec:** `docs/superpowers/specs/2026-09-30-phase-2c-outbound-http-design.md` (sections 1–4, PR 1).

## Global Constraints

- Branch `refactor/source-http`, from main `98d7a13`; the spec is commit `8e287bc`.
- Behaviour stays the same unless the spec names the change. PR 1's visible changes: a calendar's redirect chain
  shares one deadline (2); calendars refuse IPv4-mapped loopback unless loopback is allowed (3); calendar and workflow
  transport failures read the unified way (5).
- Always refused: any-local, link-local, multicast. LAN allowed. Loopback only with the policy's `allowLoopback`.
  IPv4-mapped IPv6 addresses are judged as the IPv4 address they carry.
- No failure message contains a path, query, header value or credential, and no transport failure carries a cause.
- `ContentSourceException.Kind` is exactly: `INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, BLOCKED, UNAUTHORIZED,
  REVOKED, FORBIDDEN, NOT_FOUND, RATE_LIMITED, QUOTA_EXHAUSTED, SERVER_ERROR, BAD_RESPONSE, TOO_LARGE`.
- Folds: Jellyfin `NOT_JELLYFIN`, `UNSUPPORTED_VERSION` → `BAD_RESPONSE`; Jellyfin `USER_NOT_FOUND` → `UNAUTHORIZED`;
  calendar `NOT_A_CALENDAR` → `BAD_RESPONSE`; YouTube `SEARCH_LIMIT` → `QUOTA_EXHAUSTED`. Messages stay.
- Jellyfin, TMDB, TheSportsDB and YouTube keep their JDK transports in this PR (PR 2 moves them).
- `scripts/gradle.sh build` green after every task; browser tests (`scripts/e2e.sh -Pe2eBrowsers=chromium`) green at
  the end. Frozen violations stay 42; never refreeze.
- Stage only the files you changed. Commits: Conventional Commits with the session trailer.

**Deviations from the spec's wording, decided here:**
- The client's value types are `OutboundRequest`, `OutboundResponse` and `OutboundFailure` (not `Response`), so tests
  that also use `testsupport.Response` read clearly.
- Loopback is part of the `OutboundAddressPolicy` passed with the `Profile`, not a `Profile` field: tests inject a
  resolver through the policy, and one object then owns every address rule.
- `Profile` gains `HttpUrls.Rules urlRules`, the rules a redirect's `Location` is parsed with (the spec's "the
  source's `HttpUrls` rules").
- `HttpUrls.parse` throws `HttpUrls.InvalidUrlException` (an `IllegalArgumentException`) carrying a `Problem`, so each
  caller keeps its exact wording; its own message is a generic, actionable default.
- An `OutboundRequest` may raise the body cap too (Jellyfin's 10 MB images over a 2 MB profile), not only lower it.

## Review Focus

1. **IPv6 hosts.** `URI.getHost()` keeps the brackets (`[::1]`); a bracketed literal must be judged, not sent to DNS
   or refused as unknown. Pinned in Task 2 (`OutboundAddressPolicyTest.judgesBracketedIpv6Literals`).
2. **Relative and protocol-relative redirects** (`Location: /b`, `Location: //other.test/b`) resolve against the
   current URL before the origin check. Pinned in Task 3 (`GuardedHttpClientTest.resolvesRelativeRedirects`).
3. **Body-cap boundaries:** exactly the cap is fine, one byte over fails, with and without Content-Length. Pinned in
   Task 3 (`GuardedHttpClientTest.aBodyOfExactlyTheCapIsRead`, `…OneByteOverFails…`).
4. **Shutdown during a refresh:** an interrupted caller keeps its interrupt flag and opens no connection. Pinned in
   Task 3 (`GuardedHttpClientTest.anInterruptedCallerOpensNoConnection`).
5. **A hanging DNS lookup** ends at the deadline for the caller, and a late answer never leads to a connection. Pinned
   in Task 3 (`GuardedHttpClientTest.aHangingLookupEndsAtTheDeadline`).

---

### Task 1: One set of error kinds

**Files:**
- Modify: `src/main/java/dev/andre/homecontrol/core/content/ContentSourceException.java`
- Modify: `sources/jellyfin/JellyfinException.java`, `sources/tmdb/TmdbException.java`,
  `sources/sports/thesportsdb/TheSportsDbException.java`, `sources/youtube/YouTubeException.java`,
  `sources/sports/calendar/CalendarFetchException.java`
- Modify (scripted): every main, test and e2e file that names `<Source>Exception.Kind`, and every
  `new ContentSourceException(` without a kind
- Test: `src/test/java/dev/andre/homecontrol/core/content/ContentSourceExceptionTest.java` (new)

**Interfaces:**
- Produces: `ContentSourceException.Kind` (the thirteen constants above); `ContentSourceException(Kind, String)`,
  `ContentSourceException(Kind, String, Throwable)`, `Kind kind()`. The kind-less constructors go. Each source
  exception keeps its constructors, typed with `ContentSourceException.Kind`, and loses its own enum and `kind` field.
  `YouTubeException` keeps `reason()`.

- [ ] **Step 1: Write the failing test**

```java
// File: src/test/java/dev/andre/homecontrol/core/content/ContentSourceExceptionTest.java
package dev.andre.homecontrol.core.content;

import dev.andre.homecontrol.sources.jellyfin.JellyfinException;
import dev.andre.homecontrol.sources.sports.calendar.CalendarFetchException;
import dev.andre.homecontrol.sources.sports.thesportsdb.TheSportsDbException;
import dev.andre.homecontrol.sources.tmdb.TmdbException;
import dev.andre.homecontrol.sources.youtube.YouTubeException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every source reports its failures with one set of kinds. */
class ContentSourceExceptionTest {

    @Test
    void everySourceExceptionCarriesTheSharedKind() {
        List<ContentSourceException> failures = List.of(
                new JellyfinException(ContentSourceException.Kind.NOT_FOUND, "Jellyfin"),
                new TmdbException(ContentSourceException.Kind.NOT_FOUND, "TMDB"),
                new TheSportsDbException(ContentSourceException.Kind.NOT_FOUND, "TheSportsDB"),
                new YouTubeException(ContentSourceException.Kind.NOT_FOUND, "YouTube"),
                new CalendarFetchException(ContentSourceException.Kind.NOT_FOUND, "Calendar"));

        assertThat(failures).extracting(ContentSourceException::kind).containsOnly(ContentSourceException.Kind.NOT_FOUND);
    }

    @Test
    void theKindsAreTheSharedSet() {
        assertThat(ContentSourceException.Kind.values()).extracting(Enum::name).containsExactly(
                "INVALID_INPUT", "NOT_CONFIGURED", "UNREACHABLE", "BLOCKED", "UNAUTHORIZED", "REVOKED", "FORBIDDEN",
                "NOT_FOUND", "RATE_LIMITED", "QUOTA_EXHAUSTED", "SERVER_ERROR", "BAD_RESPONSE", "TOO_LARGE");
    }
}
```

- [ ] **Step 2: Run it.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.core.content.ContentSourceExceptionTest'`.
  Expected: compilation fails (`ContentSourceException.Kind` does not exist).

- [ ] **Step 3: Implement the shared kind**

```java
// File: src/main/java/dev/andre/homecontrol/core/content/ContentSourceException.java
package dev.andre.homecontrol.core.content;

/** A content source could not answer. The message is user-facing and names the source; it never contains a credential. */
public class ContentSourceException extends RuntimeException {

    /** What went wrong, in terms every source shares; sources branch on it, the web edge does not. */
    public enum Kind {
        INVALID_INPUT, NOT_CONFIGURED, UNREACHABLE, BLOCKED, UNAUTHORIZED, REVOKED, FORBIDDEN, NOT_FOUND,
        RATE_LIMITED, QUOTA_EXHAUSTED, SERVER_ERROR, BAD_RESPONSE, TOO_LARGE
    }

    private final Kind kind;

    public ContentSourceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ContentSourceException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
```

Each source exception drops its `enum Kind`, its `kind` field and its `kind()` method, and passes the kind to
`super`. For example:

```java
// File: src/main/java/dev/andre/homecontrol/sources/tmdb/TmdbException.java
package dev.andre.homecontrol.sources.tmdb;

import dev.andre.homecontrol.core.content.ContentSourceException;

/** A TMDB call failed; the message is user-facing and never contains a credential. */
public class TmdbException extends ContentSourceException {

    public TmdbException(Kind kind, String message) {
        super(kind, message);
    }

    public TmdbException(Kind kind, String message, Throwable cause) {
        super(kind, message, cause);
    }
}
```

`YouTubeException(Kind, String, String reason)` calls `super(kind, message)` and keeps its `reason` field.

- [ ] **Step 4: Move every user onto the shared kind** with a script in the plan's workspace (`t1m.py`):
  - replace `JellyfinException.Kind`, `TmdbException.Kind`, `TheSportsDbException.Kind`, `YouTubeException.Kind` and
    `CalendarFetchException.Kind` with `ContentSourceException.Kind` in `src/main`, `src/test` and `src/e2e`;
  - replace an `import …<Source>Exception.Kind;` with `import dev.andre.homecontrol.core.content.ContentSourceException.Kind;`
    (the calendar files use `Kind.X` unqualified) and a static import of a source kind likewise;
  - add `import dev.andre.homecontrol.core.content.ContentSourceException;` to each changed file outside
    `core.content` that lacks it;
  - fold: `Kind.NOT_JELLYFIN` and `Kind.UNSUPPORTED_VERSION` → `Kind.BAD_RESPONSE`; `Kind.USER_NOT_FOUND` →
    `Kind.UNAUTHORIZED`; `Kind.NOT_A_CALENDAR` → `Kind.BAD_RESPONSE`; `Kind.SEARCH_LIMIT` → `Kind.QUOTA_EXHAUSTED`.
  Then give every `new ContentSourceException("…")` and `new ContentSourceException("…", cause)` a kind: "not
  connected" and "choose your services" messages → `NOT_CONFIGURED`; an unavailable playlist or Watch Later →
  `NOT_FOUND`; a sports or workflow schedule failure passed on → `BAD_RESPONSE`; a test fake's failure →
  `UNREACHABLE`. Prune imports left unused (`unused.py`).

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green. Tests that asserted a folded kind now
  assert its new kind; their messages are unchanged.

- [ ] **Step 6: Commit** `refactor: give every content source one set of error kinds`.

---

### Task 2: One address policy and one URL parser

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/http/OutboundAddressPolicy.java`,
  `src/main/java/dev/andre/homecontrol/sources/http/HttpUrls.java`,
  `src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarLinks.java`
- Modify: `sources/sports/calendar/CalendarUrlPolicy.java` (parse goes), `sources/sports/calendar/SportsCalendars.java`,
  `sources/sports/calendar/CalendarFetcher.java` (redirect parsing), `sources/workflows/WorkflowUrlPolicy.java` (parse
  goes), `sources/workflows/WorkflowHttpClient.java` (parses with `HttpUrls`), `sources/workflows/WorkflowTemplate.java`
  (`validUri`), `sources/jellyfin/JellyfinClient.java` (`normalizeServerUrl`)
- Test: `src/test/java/dev/andre/homecontrol/sources/http/OutboundAddressPolicyTest.java`,
  `src/test/java/dev/andre/homecontrol/sources/http/HttpUrlsTest.java` (new); `CalendarUrlPolicyTest`'s parse cases move
  to `CalendarLinksTest`; `WorkflowUrlPolicyTest`'s parse cases move to `HttpUrlsTest`

**Interfaces:**
- Produces: `OutboundAddressPolicy(boolean allowLoopback)`, `OutboundAddressPolicy(boolean allowLoopback, Resolver)`,
  `InetAddress[] addresses(String host) throws UnknownHostException`, `static boolean isLiteral(String host)`,
  `OutboundAddressPolicy.Resolver`, `OutboundAddressPolicy.BlockedAddressException extends UnknownHostException`.
- Produces: `HttpUrls.parse(String raw, HttpUrls.Rules rules)`, `HttpUrls.Rules(boolean allowQuery, boolean
  allowFragment, boolean allowDotSegments, boolean webcal, int maxLength)` (0 = no limit), `HttpUrls.Problem`,
  `HttpUrls.InvalidUrlException#problem()`.
- Produces: `CalendarLinks.parse(String raw)`, `CalendarLinks.RULES`; `WorkflowHttpClient.CALL_URLS`.
- The two old policies keep only their address methods until Tasks 4 and 5 delete them.

- [ ] **Step 1: Write the failing tests**

```java
// File: src/test/java/dev/andre/homecontrol/sources/http/OutboundAddressPolicyTest.java
package dev.andre.homecontrol.sources.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Where a content source may connect: the LAN and the internet, never this machine's link or its metadata. */
class OutboundAddressPolicyTest {

    private static OutboundAddressPolicy resolvingTo(boolean allowLoopback, String... addresses) {
        return new OutboundAddressPolicy(allowLoopback, host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.ofLiteral(addresses[i]);
            }
            return resolved;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "192.168.1.20", "10.0.0.5", "172.16.3.4", "2001:db8::1", "fd00::5"})
    void allowsTheInternetAndTheLan(String address) throws UnknownHostException {
        assertThat(resolvingTo(false, address).addresses("media.example")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "169.254.169.254", "fe80::1", "224.0.0.251", "ff02::1",
            "127.0.0.1", "::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254"})
    void refusesThisMachineItsLinkAndMulticast(String address) {
        assertThatThrownBy(() -> resolvingTo(false, address).addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "::ffff:127.0.0.1"})
    void allowsLoopbackOnlyWhenAsked(String address) throws UnknownHostException {
        assertThat(resolvingTo(true, address).addresses("media.example")).hasSize(1);
    }

    @Test
    void oneRefusedAddressRefusesTheHost() {
        assertThatThrownBy(() -> resolvingTo(false, "93.184.216.34", "127.0.0.1").addresses("media.example"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @Test
    void judgesLiteralsWithoutAskingDns() throws UnknownHostException {
        AtomicInteger lookups = new AtomicInteger();
        var policy = new OutboundAddressPolicy(false, host -> {
            lookups.incrementAndGet();
            throw new UnknownHostException(host);
        });

        assertThat(policy.addresses("192.168.1.20")).hasSize(1);
        assertThatThrownBy(() -> policy.addresses("169.254.169.254"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(lookups).hasValue(0);
    }

    @Test
    void judgesBracketedIpv6Literals() throws UnknownHostException {
        assertThat(resolvingTo(true).addresses("[::1]")).hasSize(1);
        assertThatThrownBy(() -> resolvingTo(true).addresses("[fe80::1]"))
                .isInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
        assertThat(OutboundAddressPolicy.isLiteral("[::1]")).isTrue();
        assertThat(OutboundAddressPolicy.isLiteral("media.example")).isFalse();
    }

    @Test
    void anUnknownHostIsNotBlocked() {
        assertThatThrownBy(() -> resolvingTo(false).addresses("nowhere.example"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(OutboundAddressPolicy.BlockedAddressException.class);
    }

    @Test
    void neverHandsOutTheResolversOwnArray() throws UnknownHostException {
        InetAddress[] kept = {InetAddress.ofLiteral("192.168.1.20")};
        var policy = new OutboundAddressPolicy(false, host -> kept);

        InetAddress[] checked = policy.addresses("media.example");
        kept[0] = InetAddress.ofLiteral("127.0.0.1");

        assertThat(checked[0].getHostAddress()).isEqualTo("192.168.1.20");
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/sources/http/HttpUrlsTest.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.sources.http.HttpUrls.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One parser for every outbound link; the rules say what a caller accepts beyond the shared ones. */
class HttpUrlsTest {

    private static final HttpUrls.Rules LENIENT = new HttpUrls.Rules(true, true, true, false, 0);
    private static final HttpUrls.Rules STRICT = new HttpUrls.Rules(false, false, false, false, 64);
    private static final HttpUrls.Rules WEBCAL = new HttpUrls.Rules(true, true, true, true, 0);

    private static Problem problemOf(String raw, HttpUrls.Rules rules) {
        try {
            HttpUrls.parse(raw, rules);
            return null;
        } catch (HttpUrls.InvalidUrlException e) {
            return e.problem();
        }
    }

    @Test
    void acceptsPlainHttpAndHttps() {
        assertThat(HttpUrls.parse("https://media.example:8920/x?y=1#z", LENIENT))
                .isEqualTo(URI.create("https://media.example:8920/x?y=1#z"));
        assertThat(HttpUrls.parse("http://[::1]:8096", LENIENT).getHost()).isEqualTo("[::1]");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''                                  | MISSING",
            "'   '                               | MISSING",
            "not a url                           | SYNTAX",
            "ftp://media.example/x               | SCHEME",
            "mailto:someone@example.org          | SCHEME",
            "https://user:pass@media.example/x   | USER_INFO",
            "https:///x                          | HOST",
            "http://[fe80::1%25eth0]/x           | HOST",
            "http://media.example:0/x            | PORT",
            "http://media.example:70000/x        | PORT"})
    void refusesWhatNoOutboundLinkMayBe(String raw, Problem problem) {
        assertThat(problemOf(raw, LENIENT)).isEqualTo(problem);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://media.example/x?y=1         | QUERY",
            "https://media.example/x#top         | FRAGMENT",
            "https://media.example/a/../b        | DOT_SEGMENT",
            "https://media.example/./b           | DOT_SEGMENT"})
    void refusesWhatTheRulesDoNotAllow(String raw, Problem problem) {
        assertThat(problemOf(raw, STRICT)).isEqualTo(problem);
        assertThat(problemOf(raw, LENIENT)).isNull();
    }

    @Test
    void refusesALinkLongerThanTheRules() {
        assertThat(problemOf("https://media.example/" + "a".repeat(64), STRICT)).isEqualTo(Problem.TOO_LONG);
    }

    @Test
    void turnsWebcalIntoHttpsOnlyWhenTheRulesSaySo() {
        assertThat(HttpUrls.parse("webcal://cal.example/a.ics", WEBCAL)).isEqualTo(URI.create("https://cal.example/a.ics"));
        assertThat(HttpUrls.parse("WEBCALS://cal.example/a.ics", WEBCAL)).isEqualTo(URI.create("https://cal.example/a.ics"));
        assertThat(problemOf("webcal://cal.example/a.ics", LENIENT)).isEqualTo(Problem.SCHEME);
    }

    @Test
    void itsDefaultMessageSaysWhatToFix() {
        assertThatThrownBy(() -> HttpUrls.parse("ftp://media.example/x", LENIENT))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Use an http or https link");
    }
}
```

Move `CalendarUrlPolicyTest`'s parse test (its messages) into a new `CalendarLinksTest` calling `CalendarLinks.parse`,
with two extra cases: `http://cal.example:0/a.ics` and `http://[fe80::1%25eth0]/a.ics` answer "That is not a valid link".
Delete the parse cases from `WorkflowUrlPolicyTest` (they are `HttpUrlsTest`'s now) and assert through
`WorkflowHttpClient` instead where a test needs the "invalid HTTP URL" detail.

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`. Expected:
  compilation fails (`OutboundAddressPolicy`, `HttpUrls` do not exist).

- [ ] **Step 3: Implement**

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/OutboundAddressPolicy.java
package dev.andre.homecontrol.sources.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Where a content source may connect. The LAN and the internet are allowed; this machine's any-local address, its
 * link-local network (cloud metadata lives there) and multicast never are, and loopback only when the source allows
 * it. An IPv4-mapped IPv6 address is judged as the IPv4 address it carries. The connection must go to exactly the
 * addresses returned, so a host cannot pass with one address and be reached at another.
 */
public final class OutboundAddressPolicy {

    /** Looks a host name up; {@code InetAddress::getAllByName} outside tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The host resolves, but to an address no source may reach. An {@link UnknownHostException} so it passes
     * through HttpClient's DNS hook unchanged. */
    public static final class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException() {
            super("address not allowed");
        }
    }

    private final boolean allowLoopback;
    private final Resolver resolver;

    public OutboundAddressPolicy(boolean allowLoopback) {
        this(allowLoopback, InetAddress::getAllByName);
    }

    public OutboundAddressPolicy(boolean allowLoopback, Resolver resolver) {
        this.allowLoopback = allowLoopback;
        this.resolver = Objects.requireNonNull(resolver);
    }

    /** The host's addresses when every one of them may be reached; accepts an IPv6 literal with or without brackets. */
    public InetAddress[] addresses(String host) throws UnknownHostException {
        String bare = unbracketed(host);
        InetAddress literal = literal(bare);
        InetAddress[] resolved = literal != null ? new InetAddress[] {literal} : resolver.resolve(bare);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException("unknown host");
        }
        // A resolver may retain its array. Never hand out an array it can change after the check.
        InetAddress[] checked = resolved.clone();
        for (InetAddress address : checked) {
            if (address == null) {
                throw new UnknownHostException("unknown host");
            }
            if (!allowed(address)) {
                throw new BlockedAddressException();
            }
        }
        return checked;
    }

    /** Whether a host is an IP literal, which HttpClient may connect to without asking the DNS hook. */
    public static boolean isLiteral(String host) {
        return host != null && literal(unbracketed(host)) != null;
    }

    private boolean allowed(InetAddress address) throws UnknownHostException {
        InetAddress judged = unmapped(address);
        return !(judged.isAnyLocalAddress() || judged.isLinkLocalAddress() || judged.isMulticastAddress()
                || (!allowLoopback && judged.isLoopbackAddress()));
    }

    private static InetAddress unmapped(InetAddress address) throws UnknownHostException {
        byte[] bytes = address.getAddress();
        boolean mapped = bytes.length == 16 && Arrays.equals(bytes, 0, 10, new byte[10], 0, 10)
                && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        return mapped ? InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)) : address;
    }

    private static String unbracketed(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static InetAddress literal(String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/HttpUrls.java
package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one parser for links a content source connects to. Every link is http or https, has a host without a zone and
 * no user name or password, and a port from 1 to 65535; {@link Rules} holds what differs between callers. A caller
 * words the refusal itself from the {@link Problem}; the exception's own message is a generic default.
 */
public final class HttpUrls {

    /** What is wrong with a link. */
    public enum Problem {
        MISSING("Enter a link"),
        TOO_LONG("That link is too long"),
        SYNTAX("That is not a valid link"),
        SCHEME("Use an http or https link"),
        USER_INFO("Links with a user name or password are not supported"),
        HOST("That link has no valid host"),
        PORT("That link has an invalid port"),
        QUERY("That link may not have a query"),
        FRAGMENT("That link may not have a fragment"),
        DOT_SEGMENT("That link may not contain . or .. path segments");

        private final String message;

        Problem(String message) {
            this.message = message;
        }
    }

    /** A refused link. Its message never repeats the link. */
    public static final class InvalidUrlException extends IllegalArgumentException {
        private final Problem problem;

        InvalidUrlException(Problem problem) {
            super(problem.message);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    /**
     * What a caller accepts beyond the shared rules: a query, a fragment, {@code .} or {@code ..} path segments, a
     * {@code webcal(s)} link (read as https), and at most {@code maxLength} characters (0: no limit).
     */
    public record Rules(boolean allowQuery, boolean allowFragment, boolean allowDotSegments, boolean webcal,
                        int maxLength) {
    }

    private HttpUrls() {
    }

    public static URI parse(String raw, Rules rules) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException(Problem.MISSING);
        }
        if (rules.maxLength() > 0 && raw.length() > rules.maxLength()) {
            throw new InvalidUrlException(Problem.TOO_LONG);
        }
        URI uri = uri(raw);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (rules.webcal() && (scheme.equals("webcal") || scheme.equals("webcals"))) {
            uri = uri("https" + raw.substring(uri.getScheme().length()));
        } else if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new InvalidUrlException(Problem.SCHEME);
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidUrlException(Problem.USER_INFO);
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getHost().contains("%")) {
            throw new InvalidUrlException(Problem.HOST);
        }
        if (uri.getPort() == 0 || uri.getPort() > 65_535) {
            throw new InvalidUrlException(Problem.PORT);
        }
        if (!rules.allowQuery() && uri.getRawQuery() != null) {
            throw new InvalidUrlException(Problem.QUERY);
        }
        if (!rules.allowFragment() && uri.getRawFragment() != null) {
            throw new InvalidUrlException(Problem.FRAGMENT);
        }
        if (!rules.allowDotSegments() && hasDotSegment(uri.getPath())) {
            throw new InvalidUrlException(Problem.DOT_SEGMENT);
        }
        return uri;
    }

    private static URI uri(String raw) {
        try {
            return new URI(raw);
        } catch (URISyntaxException _) {
            throw new InvalidUrlException(Problem.SYNTAX);
        }
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarLinks.java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.sources.http.HttpUrls;

import java.net.URI;

/** The calendar links people add: http, https or webcal, a query and fragment allowed, at most 2,048 characters. */
public final class CalendarLinks {

    static final HttpUrls.Rules RULES = new HttpUrls.Rules(true, true, true, true, 2_048);

    private CalendarLinks() {
    }

    /** The link to fetch; throws {@link IllegalArgumentException} with a sentence for the setup page. */
    public static URI parse(String raw) {
        try {
            return HttpUrls.parse(raw == null ? null : raw.strip(), RULES);
        } catch (HttpUrls.InvalidUrlException e) {
            throw new IllegalArgumentException(switch (e.problem()) {
                case MISSING -> "Enter a calendar link";
                case TOO_LONG -> "That calendar link is too long";
                case SCHEME -> "Use an http, https or webcal link";
                case USER_INFO -> "Links with a user name or password are not supported; use the calendar's secret link instead";
                default -> "That is not a valid link";
            }, e);
        }
    }
}
```

Callers:
- `SportsCalendars`: `policy.parse(request.url())` → `CalendarLinks.parse(request.url())`. If `CalendarUrlPolicy` was
  its only reason for the constructor parameter, the parameter goes (and `SportsConfiguration` and
  `SportsCalendarsTest` follow).
- `CalendarFetcher.redirectTarget`: `policy.parse(...)` → `CalendarLinks.parse(...)`.
- `CalendarUrlPolicy`: delete `parse` and `MAX_LENGTH`; keep `addresses` and `checkAddress` (Task 5 deletes the class).
- `WorkflowHttpClient`: add `static final HttpUrls.Rules CALL_URLS = new HttpUrls.Rules(true, false, true, false, 8_192);`
  and a private `static URI parse(String value)` that returns `HttpUrls.parse(value, CALL_URLS)` and turns
  `HttpUrls.InvalidUrlException` into `new WorkflowException(Stage.FETCH, "invalid HTTP URL")`; use it where
  `policy.parse` was. `WorkflowUrlPolicy` loses `parse` and `invalidUrl`.
- `WorkflowTemplate.validUri`:

```java
    private static final HttpUrls.Rules TEMPLATE_URLS = new HttpUrls.Rules(true, false, false, false, 0);

    private URI validUri(String raw) {
        try {
            return HttpUrls.parse(raw, TEMPLATE_URLS);
        } catch (HttpUrls.InvalidUrlException e) {
            if (e.problem() == HttpUrls.Problem.DOT_SEGMENT) fail("dot path segment in " + label);
            throw new WorkflowException(stage, INVALID + label + " template");
        }
    }
```

- `JellyfinClient.normalizeServerUrl`: keep stripping whitespace and trailing slashes, then
  `HttpUrls.parse(trimmed, SERVER_URLS)` with `private static final HttpUrls.Rules SERVER_URLS =
  new HttpUrls.Rules(false, false, true, false, 0);`; any `InvalidUrlException` becomes the existing
  `JellyfinException(Kind.INVALID_INPUT, "Enter the Jellyfin address as http://host:8096 (or https://…)")`.

- [ ] **Step 4: Run the build.** `scripts/gradle.sh build`. Expected: green; `CalendarLinksTest`, `HttpUrlsTest`,
  `OutboundAddressPolicyTest` included, and the Jellyfin, template and workflow tests unchanged.

- [ ] **Step 5: Commit** `refactor: parse outbound links and judge their addresses in one place each`.

---

### Task 3: The guarded client

**Files:**
- Create: `src/main/java/dev/andre/homecontrol/sources/http/GuardedHttpClient.java`, `OutboundRequest.java`,
  `OutboundResponse.java`, `OutboundFailure.java`, `Statuses.java` (all in `sources/http`)
- Test: `src/test/java/dev/andre/homecontrol/sources/http/GuardedHttpClientTest.java`, `StatusesTest.java`,
  `OutboundFailureTest.java`

**Interfaces:**
- Consumes: `OutboundAddressPolicy`, `HttpUrls` (Task 2); `ContentSourceException.Kind` (Task 1);
  `VettedHttpClients.create(AddressVetting, int, Duration)` (Phase 0.1).
- Produces:
  - `GuardedHttpClient(Profile, OutboundAddressPolicy, Function<OutboundFailure, ? extends RuntimeException>)`,
    `OutboundResponse send(OutboundRequest)`, `void vet(OutboundRequest)`, `void close()`.
  - `GuardedHttpClient.Redirects { NONE, SAME_ORIGIN, CHECKED }`.
  - `GuardedHttpClient.Profile(String name, Redirects redirects, int maxRedirects, int maxBytes, Duration
    connectTimeout, Duration deadline, int maxConcurrent, HttpUrls.Rules urlRules)`.
  - `OutboundRequest.get(URI)`, `OutboundRequest.post(URI, byte[] body, String contentType)`,
    `.header(String, String)`, `.limitedTo(int bytes)`, `.failingFastWhenBusy()`, `.endingBy(long nanoTime)`.
  - `OutboundResponse(int status, String contentType, byte[] body, Map<String, String> headers)`,
    `String header(String name)`.
  - `OutboundFailure(Kind kind, String host, String reason, int limit)`, `String describe(String name)`, and the reason
    constants `TIMED_OUT`, `INTERRUPTED`, `CLOSED`, `FAILED`, `UNKNOWN_HOST`, `REFUSED`, `ADDRESS_NOT_ALLOWED`,
    `OTHER_ORIGIN`, `TOO_LARGE`, `COMPRESSED`, `TOO_MANY_REDIRECTS`, `NO_REDIRECT_TARGET`, `INVALID_REDIRECT`, `BUSY`.
  - `Statuses.kindOf(int status)`.

- [ ] **Step 1: Write the failing tests**

```java
// File: src/test/java/dev/andre/homecontrol/sources/http/StatusesTest.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StatusesTest {

    @ParameterizedTest
    @CsvSource({"401, UNAUTHORIZED", "403, UNAUTHORIZED", "404, NOT_FOUND", "410, NOT_FOUND", "429, RATE_LIMITED",
            "500, SERVER_ERROR", "503, SERVER_ERROR", "400, BAD_RESPONSE", "302, BAD_RESPONSE", "418, BAD_RESPONSE"})
    void mapsAnUnsuccessfulStatusToItsKind(int status, Kind kind) {
        assertThat(Statuses.kindOf(status)).isEqualTo(kind);
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/sources/http/OutboundFailureTest.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failure's default sentence names the source and the host, never more. */
class OutboundFailureTest {

    @Test
    void describesEachFailureForAPerson() {
        assertThat(new OutboundFailure(Kind.UNREACHABLE, "cal.example", OutboundFailure.TIMED_OUT, 0).describe("the calendar"))
                .isEqualTo("Could not reach the calendar at cal.example (request timed out)");
        assertThat(new OutboundFailure(Kind.BLOCKED, "cal.example", OutboundFailure.ADDRESS_NOT_ALLOWED, 0).describe("the calendar"))
                .isEqualTo("Home Control does not connect to cal.example (address not allowed)");
        assertThat(new OutboundFailure(Kind.TOO_LARGE, "cal.example", OutboundFailure.TOO_LARGE, 5_242_880).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent more than 5 MB");
        assertThat(new OutboundFailure(Kind.TOO_LARGE, "cal.example", OutboundFailure.TOO_LARGE, 64).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent more than 64 bytes");
        assertThat(new OutboundFailure(Kind.BAD_RESPONSE, "cal.example", OutboundFailure.COMPRESSED, 0).describe("the calendar"))
                .isEqualTo("The calendar at cal.example sent a response Home Control cannot read (response compression is not supported)");
        assertThat(new OutboundFailure(Kind.RATE_LIMITED, "cal.example", OutboundFailure.BUSY, 0).describe("the calendar"))
                .isEqualTo("Home Control is busy talking to the calendar; try again in a moment");
    }
}
```

```java
// File: src/test/java/dev/andre/homecontrol/sources/http/GuardedHttpClientTest.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException;
import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import dev.andre.homecontrol.sources.http.GuardedHttpClient.Profile;
import dev.andre.homecontrol.sources.http.GuardedHttpClient.Redirects;
import dev.andre.homecontrol.testsupport.FakeHttpServer;
import dev.andre.homecontrol.testsupport.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The guarded client against a fake server on loopback. Host names resolve through the test's policy: source.test
 * and other.test to the fake, metadata.test to the cloud metadata address.
 */
class GuardedHttpClientTest {

    private static final int CAP = 64;

    private FakeHttpServer server;
    private final AtomicInteger lookups = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = FakeHttpServer.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.close();
    }

    private OutboundAddressPolicy policy(boolean allowLoopback) {
        Map<String, String> names = Map.of("source.test", "127.0.0.1", "other.test", "127.0.0.1",
                "metadata.test", "169.254.169.254");
        return new OutboundAddressPolicy(allowLoopback, host -> {
            lookups.incrementAndGet();
            String address = names.get(host);
            if (address == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.ofLiteral(address)};
        });
    }

    private static Profile profile(Redirects redirects, Duration deadline, int maxConcurrent) {
        return new Profile("the source", redirects, 2, CAP, Duration.ofSeconds(1), deadline, maxConcurrent,
                new HttpUrls.Rules(true, false, true, false, 0));
    }

    private GuardedHttpClient client(Redirects redirects) {
        return client(redirects, Duration.ofSeconds(2), 2, true);
    }

    private GuardedHttpClient client(Redirects redirects, Duration deadline, int maxConcurrent, boolean allowLoopback) {
        return new GuardedHttpClient(profile(redirects, deadline, maxConcurrent), policy(allowLoopback),
                failure -> new ContentSourceException(failure.kind(), failure.describe("the source")));
    }

    private URI at(String host, String path) {
        return URI.create("http://" + host + ":" + server.url().getPort() + path);
    }

    private static ContentSourceException failureOf(Runnable call) {
        return catchThrowableOfType(ContentSourceException.class, call::run);
    }

    private void blockUntilReleased(String path, CountDownLatch entered) {
        server.handle("GET", path, exchange -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
    }

    @Test
    void readsABodyFromAnApprovedHost() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            OutboundResponse response = client.send(OutboundRequest.get(at("source.test", "/ok")).header("Accept", "text/plain"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
            assertThat(response.contentType()).startsWith("text/plain");
            assertThat(server.last("GET", "/ok").headers()).containsEntry("Accept", "text/plain");
        }
    }

    @Test
    void aHostIsLookedUpOnceAndConnectedToAtTheAddressThatPassed() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            client.send(OutboundRequest.get(at("source.test", "/ok")));
        }

        assertThat(lookups).hasValue(1);
        assertThat(server.count("GET", "/ok")).isEqualTo(1);
    }

    @Test
    void refusesARefusedAddressWithoutConnecting() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE, Duration.ofSeconds(2), 2, false)) {
            ContentSourceException named = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok"))));
            ContentSourceException literal = failureOf(() -> client.send(OutboundRequest.get(at("127.0.0.1", "/ok"))));

            assertThat(named.kind()).isEqualTo(Kind.BLOCKED);
            assertThat(named).hasMessage("Home Control does not connect to source.test (address not allowed)");
            assertThat(literal.kind()).isEqualTo(Kind.BLOCKED);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void sameOriginFollowsWithinTheOriginAndRefusesAnother() {
        server.respond("GET", "/a", Response.empty(302).withHeader("Location", "/b"));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        server.respond("GET", "/away", Response.empty(302).withHeader("Location", at("other.test", "/b").toString()));
        try (var client = client(Redirects.SAME_ORIGIN)) {
            assertThat(client.send(OutboundRequest.get(at("source.test", "/a"))).body()).asString().isEqualTo("b");

            ContentSourceException away = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/away"))));
            assertThat(away.kind()).isEqualTo(Kind.BLOCKED);
            assertThat(away).hasMessageContaining(OutboundFailure.OTHER_ORIGIN);
        }
    }

    @Test
    void resolvesRelativeRedirects() {
        server.respond("GET", "/relative", Response.empty(302).withHeader("Location", "b"));
        server.respond("GET", "/protocol-relative", Response.empty(302)
                .withHeader("Location", "//other.test:" + server.url().getPort() + "/b"));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        try (var sameOrigin = client(Redirects.SAME_ORIGIN); var checked = client(Redirects.CHECKED)) {
            assertThat(sameOrigin.send(OutboundRequest.get(at("source.test", "/relative"))).body()).asString().isEqualTo("b");
            assertThat(failureOf(() -> sameOrigin.send(OutboundRequest.get(at("source.test", "/protocol-relative")))).kind())
                    .isEqualTo(Kind.BLOCKED);
            assertThat(checked.send(OutboundRequest.get(at("source.test", "/protocol-relative"))).body()).asString().isEqualTo("b");
        }
    }

    @Test
    void checkedFollowsAnotherOriginAndChecksEveryHop() {
        server.respond("GET", "/away", Response.empty(302).withHeader("Location", at("other.test", "/b").toString()));
        server.respond("GET", "/b", Response.of(200, "text/plain", "b"));
        server.respond("GET", "/metadata", Response.empty(302).withHeader("Location", "http://metadata.test/latest"));
        try (var client = client(Redirects.CHECKED)) {
            assertThat(client.send(OutboundRequest.get(at("source.test", "/away"))).body()).asString().isEqualTo("b");

            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/metadata")))).kind())
                    .isEqualTo(Kind.BLOCKED);
        }
    }

    @Test
    void stopsAfterTheLastAllowedRedirect() {
        server.respond("GET", "/loop", Response.empty(302).withHeader("Location", "/loop"));
        try (var client = client(Redirects.CHECKED)) {
            ContentSourceException loop = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/loop"))));

            assertThat(loop.kind()).isEqualTo(Kind.BAD_RESPONSE);
            assertThat(loop).hasMessageContaining(OutboundFailure.TOO_MANY_REDIRECTS);
        }
        assertThat(server.count("GET", "/loop")).isEqualTo(3);
    }

    @Test
    void aRedirectWithoutADestinationIsABadResponse() {
        server.respond("GET", "/nowhere", Response.empty(302));
        try (var client = client(Redirects.CHECKED)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/nowhere")))))
                    .hasMessageContaining(OutboundFailure.NO_REDIRECT_TARGET);
        }
    }

    @Test
    void returnsTheRedirectWhenNotFollowingAndForAPost() {
        server.respond(FakeHttpServer.ANY_METHOD, "/a", Response.empty(302).withHeader("Location", "/b"));
        try (var none = client(Redirects.NONE); var checked = client(Redirects.CHECKED)) {
            OutboundResponse notFollowed = none.send(OutboundRequest.get(at("source.test", "/a")));
            OutboundResponse posted = checked.send(OutboundRequest.post(at("source.test", "/a"),
                    "x=1".getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded"));

            assertThat(notFollowed.status()).isEqualTo(302);
            assertThat(notFollowed.header("location")).isEqualTo("/b");
            assertThat(posted.status()).isEqualTo(302);
            assertThat(server.last("POST", "/a").body()).isEqualTo("x=1");
        }
    }

    @Test
    void aBodyOfExactlyTheCapIsRead() {
        server.respond("GET", "/cap", Response.of(200, "text/plain", "a".repeat(CAP)));
        try (var client = client(Redirects.NONE)) {
            assertThat(client.send(OutboundRequest.get(at("source.test", "/cap"))).body()).hasSize(CAP);
        }
    }

    @Test
    void oneByteOverTheCapFailsWithOrWithoutContentLength() {
        server.respond("GET", "/declared", Response.of(200, "text/plain", "a".repeat(CAP + 1)));
        server.handle("GET", "/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                out.write("a".repeat(CAP + 1).getBytes(StandardCharsets.UTF_8));
            }
        });
        try (var client = client(Redirects.NONE)) {
            ContentSourceException declared = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/declared"))));
            ContentSourceException chunked = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/chunked"))));

            assertThat(declared.kind()).isEqualTo(Kind.TOO_LARGE);
            assertThat(declared).hasMessage("The source at source.test sent more than 64 bytes");
            assertThat(chunked.kind()).isEqualTo(Kind.TOO_LARGE);
            assertThat(client.send(OutboundRequest.get(at("source.test", "/declared")).limitedTo(CAP + 1)).body())
                    .hasSize(CAP + 1);
        }
    }

    @Test
    void refusesACompressedBody() {
        server.respond("GET", "/gzip", Response.of(200, "text/plain", "x").withHeader("Content-Encoding", "gzip"));
        try (var client = client(Redirects.NONE)) {
            ContentSourceException gzip = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/gzip"))));

            assertThat(gzip.kind()).isEqualTo(Kind.BAD_RESPONSE);
            assertThat(gzip).hasMessageContaining(OutboundFailure.COMPRESSED);
        }
    }

    @Test
    void oneDeadlineCoversATrickledBody() {
        server.trickle("GET", "/slow");
        try (var client = client(Redirects.NONE, Duration.ofMillis(800), 2, true)) {
            ContentSourceException slow = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/slow"))));

            assertThat(slow.kind()).isEqualTo(Kind.UNREACHABLE);
            assertThat(slow).hasMessage("Could not reach the source at source.test (request timed out)");
        }
        assertThat(server.bytesTrickled()).isGreaterThan(1);
    }

    @Test
    void oneDeadlineCoversARedirectChain() {
        Duration hop = Duration.ofMillis(400);
        server.respond("GET", "/r1", Response.empty(302).withHeader("Location", "/r2").withDelay(hop));
        server.respond("GET", "/r2", Response.empty(302).withHeader("Location", "/r3").withDelay(hop));
        server.respond("GET", "/r3", Response.of(200, "text/plain", "late").withDelay(hop));
        try (var client = client(Redirects.CHECKED, Duration.ofMillis(1_000), 2, true)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/r1")))))
                    .hasMessageContaining(OutboundFailure.TIMED_OUT);
        }
    }

    @Test
    void aHangingLookupEndsAtTheDeadline() {
        CountDownLatch looking = new CountDownLatch(1);
        var hanging = new OutboundAddressPolicy(true, host -> {
            looking.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[] {InetAddress.ofLiteral("127.0.0.1")};
        });
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = new GuardedHttpClient(profile(Redirects.NONE, Duration.ofMillis(300), 1), hanging,
                failure -> new ContentSourceException(failure.kind(), failure.describe("the source")))) {
            ContentSourceException hung = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok"))));

            assertThat(hung).hasMessageContaining(OutboundFailure.TIMED_OUT);
            release.countDown();
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void failsFastWhenEverySlotIsBusyOrWaitsUntilTheCallersDeadline() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        blockUntilReleased("/held", entered);
        try (var client = client(Redirects.NONE, Duration.ofSeconds(5), 1, true)) {
            CompletableFuture<?> holder = CompletableFuture.runAsync(() -> client.send(OutboundRequest.get(at("source.test", "/held"))));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            ContentSourceException fast = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/other")).failingFastWhenBusy()));
            ContentSourceException waited = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/other"))
                    .endingBy(System.nanoTime() + Duration.ofMillis(200).toNanos())));

            assertThat(fast.kind()).isEqualTo(Kind.RATE_LIMITED);
            assertThat(fast).hasMessage("Home Control is busy talking to the source; try again in a moment");
            assertThat(waited.kind()).isEqualTo(Kind.RATE_LIMITED);
            release.countDown();
            holder.join();
        }
    }

    @Test
    void closingCancelsExchangesInFlight() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        blockUntilReleased("/held", entered);
        var client = client(Redirects.NONE);
        CompletableFuture<ContentSourceException> held = CompletableFuture.supplyAsync(
                () -> failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/held")))));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        client.close();

        assertThat(held.join()).hasMessageContaining(OutboundFailure.CLOSED);
        assertThat(failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok")))))
                .hasMessageContaining(OutboundFailure.CLOSED);
    }

    @Test
    void anInterruptedCallerOpensNoConnection() {
        server.respond("GET", "/ok", Response.of(200, "text/plain", "hello"));
        try (var client = client(Redirects.NONE)) {
            Thread.currentThread().interrupt();
            ContentSourceException interrupted = failureOf(() -> client.send(OutboundRequest.get(at("source.test", "/ok"))));

            assertThat(Thread.interrupted()).isTrue();
            assertThat(interrupted).hasMessageContaining(OutboundFailure.INTERRUPTED);
        }
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void anUnknownHostAndARefusedConnectionAreUnreachable() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (var client = client(Redirects.NONE)) {
            assertThat(failureOf(() -> client.send(OutboundRequest.get(URI.create("http://nowhere.test/x")))))
                    .hasMessage("Could not reach the source at nowhere.test (unknown host)");
            assertThat(failureOf(() -> client.send(OutboundRequest.get(URI.create("http://source.test:" + closedPort + "/x")))))
                    .hasMessage("Could not reach the source at source.test (connection refused)");
        }
    }

    @Test
    void noFailureRevealsThePathQueryOrACause() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        server.respond("GET", "/secret-path/large", Response.of(200, "text/plain", "a".repeat(CAP + 1)));
        server.respond("GET", "/secret-path/gzip", Response.of(200, "text/plain", "x").withHeader("Content-Encoding", "gzip"));
        server.respond("GET", "/secret-path/away", Response.empty(302).withHeader("Location", at("other.test", "/secret-away").toString()));
        server.trickle("GET", "/secret-path/slow");
        try (var client = client(Redirects.SAME_ORIGIN, Duration.ofMillis(600), 2, true);
             var refusing = client(Redirects.SAME_ORIGIN, Duration.ofMillis(600), 2, false)) {
            for (Runnable call : new Runnable[] {
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/large?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/gzip?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/away?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(at("source.test", "/secret-path/slow?token=secret-token"))),
                    () -> client.send(OutboundRequest.get(URI.create("http://source.test:" + closedPort + "/secret-path?token=secret-token"))),
                    () -> refusing.send(OutboundRequest.get(at("source.test", "/secret-path?token=secret-token")))}) {
                ContentSourceException failure = failureOf(call);

                assertThat(failure).hasNoCause();
                assertThat(failure.getMessage()).doesNotContain("secret");
            }
        }
    }

    @Test
    void vettingChecksTheAddressWithoutConnecting() {
        try (var client = client(Redirects.NONE, Duration.ofSeconds(2), 2, false)) {
            assertThat(failureOf(() -> client.vet(OutboundRequest.get(at("source.test", "/media.mp4")))).kind())
                    .isEqualTo(Kind.BLOCKED);
        }
        try (var client = client(Redirects.NONE)) {
            client.vet(OutboundRequest.get(at("source.test", "/media.mp4")));
        }
        assertThat(server.requests()).isEmpty();
    }
}
```

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`. Expected:
  compilation fails (`GuardedHttpClient`, `OutboundRequest`, `OutboundResponse`, `OutboundFailure`, `Statuses` missing).

- [ ] **Step 3: Implement**

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/Statuses.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;

/** What an unsuccessful HTTP status means for a content source; a source overrides only its real exceptions. */
public final class Statuses {

    private Statuses() {
    }

    public static Kind kindOf(int status) {
        return switch (status) {
            case 401, 403 -> Kind.UNAUTHORIZED;
            case 404, 410 -> Kind.NOT_FOUND;
            case 429 -> Kind.RATE_LIMITED;
            default -> status >= 500 ? Kind.SERVER_ERROR : Kind.BAD_RESPONSE;
        };
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/OutboundFailure.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;

import java.util.Locale;

/**
 * Why an exchange failed: its kind, the host and a short reason, never the path, query, headers or a cause. A source
 * turns it into its own exception, usually with {@link #describe(String)}.
 *
 * @param limit the body cap that was exceeded, for {@link Kind#TOO_LARGE}; otherwise 0
 */
public record OutboundFailure(Kind kind, String host, String reason, int limit) {

    public static final String TIMED_OUT = "request timed out";
    public static final String INTERRUPTED = "request interrupted";
    public static final String CLOSED = "client is closed";
    public static final String FAILED = "request failed";
    public static final String UNKNOWN_HOST = "unknown host";
    public static final String REFUSED = "connection refused";
    public static final String ADDRESS_NOT_ALLOWED = "address not allowed";
    public static final String OTHER_ORIGIN = "redirect changes origin; configure the final source URL";
    public static final String TOO_LARGE = "response is too large";
    public static final String COMPRESSED = "response compression is not supported";
    public static final String TOO_MANY_REDIRECTS = "too many redirects";
    public static final String NO_REDIRECT_TARGET = "redirect has no destination";
    public static final String INVALID_REDIRECT = "redirect to a link Home Control does not follow";
    public static final String BUSY = "busy; try again later";

    /** The sentence a person reads, with {@code name} as the source is called in running text, e.g. "the calendar". */
    public String describe(String name) {
        return switch (kind) {
            case BLOCKED -> "Home Control does not connect to " + host + " (" + reason + ")";
            case TOO_LARGE -> capitalized(name) + " at " + host + " sent more than " + size(limit);
            case BAD_RESPONSE -> capitalized(name) + " at " + host + " sent a response Home Control cannot read ("
                    + reason + ")";
            case RATE_LIMITED -> "Home Control is busy talking to " + name + "; try again in a moment";
            default -> "Could not reach " + name + " at " + host + " (" + reason + ")";
        };
    }

    private static String capitalized(String name) {
        return name.isEmpty() ? name : name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private static String size(int bytes) {
        if (bytes >= 1_048_576 && bytes % 1_048_576 == 0) {
            return bytes / 1_048_576 + " MB";
        }
        if (bytes >= 1_024 && bytes % 1_024 == 0) {
            return bytes / 1_024 + " KB";
        }
        return bytes + " bytes";
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/OutboundRequest.java
package dev.andre.homecontrol.sources.http;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One GET or POST for a {@link GuardedHttpClient}. Headers are sent in order; a later header replaces an earlier one
 * of the same name. Printing it shows the method and host only.
 *
 * @param maxBytes    the body cap for this request, or 0 for the profile's
 * @param waitForSlot whether to wait for a free slot (until the deadline) rather than fail at once when all are busy
 * @param notAfter    a {@link System#nanoTime()} the whole exchange must end by, on top of the profile's deadline
 */
public record OutboundRequest(String method, URI uri, Map<String, String> headers, byte[] body, String contentType,
                              int maxBytes, boolean waitForSlot, OptionalLong notAfter) {

    public OutboundRequest {
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new IllegalArgumentException("Only GET and POST are supported");
        }
        Objects.requireNonNull(uri);
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        if (maxBytes < 0) {
            throw new IllegalArgumentException("A body cap cannot be negative");
        }
        Objects.requireNonNull(notAfter);
    }

    public static OutboundRequest get(URI uri) {
        return new OutboundRequest("GET", uri, Map.of(), null, null, 0, true, OptionalLong.empty());
    }

    public static OutboundRequest post(URI uri, byte[] body, String contentType) {
        return new OutboundRequest("POST", uri, Map.of(), body.clone(), contentType, 0, true, OptionalLong.empty());
    }

    public OutboundRequest header(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new OutboundRequest(method, uri, more, body, contentType, maxBytes, waitForSlot, notAfter);
    }

    /** A body cap for this request instead of the profile's, higher or lower. */
    public OutboundRequest limitedTo(int bytes) {
        return new OutboundRequest(method, uri, headers, body, contentType, bytes, waitForSlot, notAfter);
    }

    /** Fails at once with {@link OutboundFailure#BUSY} when every slot is taken. */
    public OutboundRequest failingFastWhenBusy() {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, false, notAfter);
    }

    /** Waits for a slot until {@code nanoTime} and ends the exchange by then at the latest. */
    public OutboundRequest endingBy(long nanoTime) {
        return new OutboundRequest(method, uri, headers, body, contentType, maxBytes, true, OptionalLong.of(nanoTime));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundRequest that && method.equals(that.method) && uri.equals(that.uri)
                && headers.equals(that.headers) && Arrays.equals(body, that.body)
                && Objects.equals(contentType, that.contentType) && maxBytes == that.maxBytes
                && waitForSlot == that.waitForSlot && notAfter.equals(that.notAfter);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, uri, headers, Arrays.hashCode(body), contentType, maxBytes, waitForSlot, notAfter);
    }

    @Override
    public String toString() {
        return "OutboundRequest[" + method + " " + uri.getHost() + "]";
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/OutboundResponse.java
package dev.andre.homecontrol.sources.http;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An answer from a {@link GuardedHttpClient}: any status, its content type, its body (empty for a redirect it
 * followed past) and its headers by lower-case name, first value only.
 */
public record OutboundResponse(int status, String contentType, byte[] body, Map<String, String> headers) {

    public OutboundResponse {
        headers = Map.copyOf(headers);
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OutboundResponse that && status == that.status
                && Objects.equals(contentType, that.contentType) && Arrays.equals(body, that.body)
                && headers.equals(that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, Arrays.hashCode(body), headers);
    }

    @Override
    public String toString() {
        return "OutboundResponse[status=" + status + ", " + body.length + " bytes]";
    }
}
```

```java
// File: src/main/java/dev/andre/homecontrol/sources/http/GuardedHttpClient.java
package dev.andre.homecontrol.sources.http;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The one way a content source reaches the network. It connects only to addresses its {@link OutboundAddressPolicy}
 * approved, looked up once, so a host cannot pass the check at one address and be reached at another. One deadline
 * covers waiting for a slot, DNS, connecting, every redirect and the whole body; each exchange runs on a worker that
 * is cancelled at the deadline. Bodies are capped (Content-Length first) and never decompressed. Failures reach the
 * source's factory as an {@link OutboundFailure}, which names only the host and carries no cause.
 */
public final class GuardedHttpClient implements AutoCloseable {

    /** Which redirects a GET follows: none (the 3xx is returned), within the origin, or anywhere, re-checked. */
    public enum Redirects { NONE, SAME_ORIGIN, CHECKED }

    /**
     * How one source uses the network.
     *
     * @param name     the source as running text calls it, e.g. "the calendar"
     * @param urlRules how a redirect's {@code Location} is parsed
     */
    public record Profile(String name, Redirects redirects, int maxRedirects, int maxBytes, Duration connectTimeout,
                          Duration deadline, int maxConcurrent, HttpUrls.Rules urlRules) {
        public Profile {
            Objects.requireNonNull(name);
            Objects.requireNonNull(redirects);
            Objects.requireNonNull(connectTimeout);
            Objects.requireNonNull(deadline);
            Objects.requireNonNull(urlRules);
            if (maxRedirects < 0 || maxBytes < 0 || maxBytes == Integer.MAX_VALUE || maxConcurrent < 1) {
                throw new IllegalArgumentException("Invalid profile for " + name);
            }
        }
    }

    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);

    private final Profile profile;
    private final OutboundAddressPolicy policy;
    private final Function<OutboundFailure, ? extends RuntimeException> failures;
    private final Semaphore permits;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final CloseableHttpClient http;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Exchange<?>> exchanges = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Exchange<?>> current = new ThreadLocal<>();

    public GuardedHttpClient(Profile profile, OutboundAddressPolicy policy,
                             Function<OutboundFailure, ? extends RuntimeException> failures) {
        this.profile = Objects.requireNonNull(profile);
        this.policy = Objects.requireNonNull(policy);
        this.failures = Objects.requireNonNull(failures);
        permits = new Semaphore(profile.maxConcurrent());
        http = VettedHttpClients.create(host -> {
            Exchange<?> exchange = current.get();
            exchange.check();
            InetAddress[] addresses = policy.addresses(host);
            // A platform resolver can ignore interruption. Never connect after it returns late.
            exchange.check();
            return addresses;
        }, profile.maxConcurrent(), profile.connectTimeout());
    }

    /** Sends the request and returns any status; a redirect is followed as the profile says. */
    public OutboundResponse send(OutboundRequest request) {
        return run(request, () -> exchange(request));
    }

    /** Looks the request's host up through the policy, under the same slots and deadline, without connecting. */
    public void vet(OutboundRequest request) {
        run(request, () -> {
            policy.addresses(host(request.uri()));
            current.get().check();
            return null;
        });
    }

    private <T> T run(OutboundRequest request, Callable<T> work) {
        String host = host(request.uri());
        if (closed.get()) {
            throw failures.apply(unreachable(host, OutboundFailure.CLOSED));
        }
        if (Thread.currentThread().isInterrupted()) {
            // Shutting down: keep the interrupt for the caller and open no new connection.
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        }
        long sent = System.nanoTime();
        long ownDeadline = sent + profile.deadline().toNanos();
        long waitUntil = request.notAfter().orElse(ownDeadline);
        admit(request, host, waitUntil);
        long deadline = request.notAfter().isEmpty() ? ownDeadline
                : earlier(request.notAfter().getAsLong(), System.nanoTime() + profile.deadline().toNanos());
        var exchange = new Exchange<T>(host, deadline);
        exchanges.add(exchange);
        try {
            workers.execute(() -> runOnWorker(exchange, work));
        } catch (RejectedExecutionException _) {
            exchanges.remove(exchange);
            permits.release();
            throw failures.apply(unreachable(host, OutboundFailure.CLOSED));
        }
        try {
            return exchange.result.get(exchange.remaining(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            exchange.cancel();
            Thread.currentThread().interrupt();
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        } catch (TimeoutException _) {
            exchange.cancel();
            throw failures.apply(unreachable(host, OutboundFailure.TIMED_OUT));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failed failed) {
                throw failures.apply(failed.failure);
            }
            throw failures.apply(unreachable(host, OutboundFailure.FAILED));
        }
    }

    private void admit(OutboundRequest request, String host, long waitUntil) {
        try {
            long remaining = waitUntil - System.nanoTime();
            boolean admitted = request.waitForSlot()
                    ? remaining > 0 && permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)
                    : permits.tryAcquire();
            if (!admitted) {
                throw failures.apply(new OutboundFailure(Kind.RATE_LIMITED, host, OutboundFailure.BUSY, 0));
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw failures.apply(unreachable(host, OutboundFailure.INTERRUPTED));
        }
    }

    /** Runs on the worker; its caller hears the outcome once the slot is released. */
    private <T> void runOnWorker(Exchange<T> exchange, Callable<T> work) {
        T value = null;
        // Replaced by the outcome. Left as is only when an Error escapes to the thread's handler,
        // so the caller still hears at once that the request failed instead of waiting out its deadline.
        Exception error = new Failed(unreachable(exchange.host, OutboundFailure.FAILED));
        exchange.worker = Thread.currentThread();
        current.set(exchange);
        try {
            exchange.check();
            value = work.call();
            exchange.check();
            error = null;
        } catch (Failed e) {
            error = e;
        } catch (IOException e) {
            error = new Failed(failureOf(exchange, e));
        } catch (Exception _) {
            error = new Failed(unreachable(exchange.host, exchange.expired() ? OutboundFailure.TIMED_OUT
                    : OutboundFailure.FAILED));
        } finally {
            current.remove();
            exchanges.remove(exchange);
            // Only the worker releases its slot, even when its caller has already given up.
            permits.release();
            if (error == null) {
                exchange.result.complete(value);
            } else {
                exchange.result.completeExceptionally(error);
            }
        }
    }

    private OutboundFailure failureOf(Exchange<?> exchange, IOException e) {
        String host = exchange.host;
        if (closed.get()) {
            return unreachable(host, OutboundFailure.CLOSED);
        }
        if (e instanceof OutboundAddressPolicy.BlockedAddressException) {
            return new OutboundFailure(Kind.BLOCKED, host, OutboundFailure.ADDRESS_NOT_ALLOWED, 0);
        }
        if (e instanceof UnknownHostException) {
            return unreachable(host, OutboundFailure.UNKNOWN_HOST);
        }
        if (exchange.expired() || e instanceof InterruptedIOException) {
            return unreachable(host, OutboundFailure.TIMED_OUT);
        }
        if (e instanceof ConnectException) {
            return unreachable(host, OutboundFailure.REFUSED);
        }
        return unreachable(host, OutboundFailure.FAILED);
    }

    private OutboundResponse exchange(OutboundRequest request) throws IOException {
        Exchange<?> exchange = current.get();
        URI uri = request.uri();
        for (int redirects = 0; ; redirects++) {
            // HttpClient can connect to a literal without asking the DNS hook, so judge literals here as well.
            if (OutboundAddressPolicy.isLiteral(uri.getHost())) {
                policy.addresses(uri.getHost());
            }
            exchange.check();
            OutboundResponse response = once(request, uri, exchange);
            if (!follows(request.method(), response.status())) {
                return response;
            }
            String location = response.header("location");
            if (location == null) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(uri), OutboundFailure.NO_REDIRECT_TARGET, 0));
            }
            if (redirects >= profile.maxRedirects()) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(uri), OutboundFailure.TOO_MANY_REDIRECTS, 0));
            }
            uri = next(uri, location);
        }
    }

    private boolean follows(String method, int status) {
        return profile.redirects() != Redirects.NONE && "GET".equals(method) && REDIRECT_STATUSES.contains(status);
    }

    private URI next(URI from, String location) {
        URI to;
        try {
            to = HttpUrls.parse(from.resolve(location).toString(), profile.urlRules());
        } catch (IllegalArgumentException _) {
            throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host(from), OutboundFailure.INVALID_REDIRECT, 0));
        }
        if (profile.redirects() == Redirects.SAME_ORIGIN && !sameOrigin(from, to)) {
            throw new Failed(new OutboundFailure(Kind.BLOCKED, host(from), OutboundFailure.OTHER_ORIGIN, 0));
        }
        return to;
    }

    private OutboundResponse once(OutboundRequest request, URI uri, Exchange<?> exchange) throws IOException {
        var message = new HttpUriRequestBase(request.method(), uri);
        message.setConfig(RequestConfig.custom()
                .setAuthenticationEnabled(false).setHardCancellationEnabled(true)
                .setConnectionRequestTimeout(timeout(exchange.remaining()))
                .setResponseTimeout(timeout(exchange.remaining())).build());
        request.headers().forEach(message::setHeader);
        if (request.body() != null) {
            message.setEntity(new ByteArrayEntity(request.body(), request.contentType() == null
                    ? ContentType.APPLICATION_OCTET_STREAM : ContentType.parse(request.contentType())));
        }
        exchange.active.set(message);
        try {
            exchange.check();
            var response = CloseableHttpResponse.adapt(http.executeOpen(null, message, null));
            try {
                Map<String, String> headers = headersOf(response.getHeaders());
                String contentType = headers.get("content-type");
                if (follows(request.method(), response.getCode())) {
                    return new OutboundResponse(response.getCode(), contentType, new byte[0], headers);
                }
                int cap = request.maxBytes() > 0 ? request.maxBytes() : profile.maxBytes();
                byte[] body = body(response.getEntity(), response.getHeaders("Content-Encoding"), host(uri), cap);
                exchange.check();
                return new OutboundResponse(response.getCode(), contentType, body, headers);
            } finally {
                // Keep cancellation active through cleanup; never drain a body gracefully.
                message.cancel();
                response.close(CloseMode.IMMEDIATE);
            }
        } finally {
            message.cancel();
            exchange.active.compareAndSet(message, null);
        }
    }

    private static byte[] body(HttpEntity entity, Header[] encodings, String host, int cap) throws IOException {
        if (entity == null) {
            return new byte[0];
        }
        for (Header encoding : encodings) {
            if (!encoding.getValue().strip().equalsIgnoreCase("identity")) {
                throw new Failed(new OutboundFailure(Kind.BAD_RESPONSE, host, OutboundFailure.COMPRESSED, 0));
            }
        }
        if (entity.getContentLength() > cap) {
            throw new Failed(new OutboundFailure(Kind.TOO_LARGE, host, OutboundFailure.TOO_LARGE, cap));
        }
        byte[] body = entity.getContent().readNBytes(cap + 1);
        if (body.length > cap) {
            throw new Failed(new OutboundFailure(Kind.TOO_LARGE, host, OutboundFailure.TOO_LARGE, cap));
        }
        return body;
    }

    private static Map<String, String> headersOf(Header[] all) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : all) {
            headers.putIfAbsent(header.getName().toLowerCase(Locale.ROOT), header.getValue());
        }
        return headers;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second);
    }

    private static int port(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "an unknown host" : uri.getHost();
    }

    private static long earlier(long first, long second) {
        return first - second < 0 ? first : second;
    }

    private static Timeout timeout(long nanos) {
        return Timeout.ofMilliseconds(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    private static OutboundFailure unreachable(String host, String reason) {
        return new OutboundFailure(Kind.UNREACHABLE, host, reason, 0);
    }

    /** A failure found on the worker; the caller turns it into the source's exception. No stack, no cause. */
    private static final class Failed extends RuntimeException {
        private final transient OutboundFailure failure;

        Failed(OutboundFailure failure) {
            super(failure.reason(), null, false, false);
            this.failure = failure;
        }
    }

    private final class Exchange<T> {
        private final String host;
        private final long deadline;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpUriRequestBase> active = new AtomicReference<>();
        private final CompletableFuture<T> result = new CompletableFuture<>();
        // Set once by the worker before it runs; cancel() only reads it to interrupt that thread.
        @SuppressWarnings("java:S3077")
        private volatile Thread worker;

        Exchange(String host, long deadline) {
            this.host = host;
            this.deadline = deadline;
        }

        long remaining() {
            return Math.max(0, deadline - System.nanoTime());
        }

        boolean expired() {
            return cancelled.get() || remaining() == 0;
        }

        void check() {
            if (closed.get()) {
                throw new Failed(unreachable(host, OutboundFailure.CLOSED));
            }
            if (expired() || Thread.currentThread().isInterrupted()) {
                throw new Failed(unreachable(host, OutboundFailure.TIMED_OUT));
            }
        }

        void cancel() {
            cancelled.set(true);
            HttpUriRequestBase request = active.get();
            if (request != null) {
                request.cancel();
            }
            Thread thread = worker;
            if (thread != null) {
                thread.interrupt();
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        exchanges.forEach(exchange -> {
            exchange.cancel();
            exchange.result.completeExceptionally(new Failed(unreachable(exchange.host, OutboundFailure.CLOSED)));
        });
        workers.shutdownNow();
        // Do not wait for platform DNS that can ignore interruption; those bounded workers keep their slots.
        http.close(CloseMode.IMMEDIATE);
    }
}
```

- [ ] **Step 4: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.http.*'`. Expected: all
  pass. A failing case is a finding about the client: fix the client (systematic debugging), not the test's
  expectation, unless the test contradicts the spec.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green.

- [ ] **Step 6: Commit** `feat: add a guarded HTTP client for content sources`.

---

### Task 4: Workflows on the guarded client

**Files:**
- Modify: `sources/workflows/WorkflowHttpClient.java` (rewritten on `GuardedHttpClient`),
  `sources/workflows/WorkflowConfiguration.java`
- Delete: `sources/workflows/WorkflowUrlPolicy.java`, `src/test/.../workflows/WorkflowUrlPolicyTest.java` (its address
  cases are `OutboundAddressPolicyTest`'s, its origin cases `GuardedHttpClientTest`'s),
  `src/test/.../workflows/WorkflowHttpClientWorkerTest.java` if every case it pins is covered by
  `GuardedHttpClientTest` (otherwise move the uncovered case there)
- Modify tests: every `new WorkflowUrlPolicy(allowLoopback, resolver)` → `new OutboundAddressPolicy(allowLoopback,
  resolver)` (the resolver lambda fits `OutboundAddressPolicy.Resolver`)
- Test: `WorkflowHttpClientTest` (details below), `sources/http/SlowBodyDeadlineTest` (workflows added)

**Interfaces:**
- Consumes: `GuardedHttpClient`, `OutboundRequest`, `OutboundResponse`, `OutboundFailure`, `OutboundAddressPolicy`,
  `HttpUrls` (Tasks 2–3).
- Produces: `WorkflowHttpClient(WorkflowProperties, OutboundAddressPolicy)`; `fetch(Request)`, `fetch(Request, long)`,
  `checkMedia(URI)`, `checkMedia(URI, long)`, `close()` keep their signatures and meaning.

- [ ] **Step 1: Write the failing tests** in `WorkflowHttpClientTest`:
  - `noFailureRevealsTheUrlOrAHeaderValue`: a URL `…/secret-path?token=secret-token` and a header
    `X-Key: secret-header`; for a too-large body, a compressed body, a trickled body (timeout), a refused connection
    and a loopback address with `allowLoopback` false, the `WorkflowException`'s message contains no `secret` and it
    has no cause.
  - `aBlockedAddressSaysSo`: `allowLoopback` false and a loopback fake → detail `address not allowed` (today:
    `request failed`).
  In `SlowBodyDeadlineTest`, add `workflowsGiveUpOnATrickledBody`: a `WorkflowHttpClient` with a 1-second request
  timeout fetching the trickling path fails with a `FETCH`-stage `WorkflowException` whose detail is
  `request timed out`, and `server.bytesTrickled() > 1`.

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.workflows.WorkflowHttpClientTest'
  --tests 'dev.andre.homecontrol.sources.http.SlowBodyDeadlineTest'`. Expected: `aBlockedAddressSaysSo` fails (detail
  `request failed`); the others fail to compile until the constructor takes `OutboundAddressPolicy`.

- [ ] **Step 3: Implement**

```java
// File: src/main/java/dev/andre/homecontrol/sources/workflows/WorkflowHttpClient.java
package dev.andre.homecontrol.sources.workflows;

import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.HttpUrls;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static dev.andre.homecontrol.sources.workflows.WorkflowException.Stage;

/** A workflow's GETs, same-origin redirects only, through the guarded client: one deadline, one slot, one body cap. */
public final class WorkflowHttpClient implements AutoCloseable {

    static final HttpUrls.Rules CALL_URLS = new HttpUrls.Rules(true, false, true, false, 8_192);
    private static final Set<String> DENIED_HEADERS = Set.of("host", "cookie", "connection", "content-length",
            "transfer-encoding", "te", "trailer", "upgrade", "keep-alive", "expect", "accept-encoding", "proxy");

    private final GuardedHttpClient http;

    public WorkflowHttpClient(WorkflowProperties properties, OutboundAddressPolicy policy) {
        http = new GuardedHttpClient(new GuardedHttpClient.Profile("the workflow source",
                GuardedHttpClient.Redirects.SAME_ORIGIN, properties.maxRedirects(), properties.maxBytes(),
                properties.connectTimeout(), properties.requestTimeout(), properties.maxConcurrentFetches(), CALL_URLS),
                policy, failure -> new WorkflowException(Stage.FETCH, failure.reason()));
    }

    /** One GET with its URL and header values already expanded. Printing it shows neither. */
    public record Request(String url, List<WorkflowDraft.Header> headers) {
        public Request {
            headers = headers == null ? null : Collections.unmodifiableList(new java.util.ArrayList<>(headers));
        }

        @Override public String toString() { return "Request"; }
    }

    /** Fails at once with "busy" when every fetch slot is taken. */
    public byte[] fetch(Request request) {
        return fetch(outbound(request).failingFastWhenBusy());
    }

    /** Waits for a fetch slot until {@code deadline}; the fetch ends at the deadline or the request timeout. */
    public byte[] fetch(Request request, long deadline) {
        return fetch(outbound(request).endingBy(deadline));
    }

    public void checkMedia(URI uri) {
        checkMedia(OutboundRequest.get(parse(uri, Stage.BUILD)).failingFastWhenBusy());
    }

    public void checkMedia(URI uri, long deadline) {
        checkMedia(OutboundRequest.get(parse(uri, Stage.BUILD)).endingBy(deadline));
    }

    private void checkMedia(OutboundRequest request) {
        try {
            http.vet(request);
        } catch (WorkflowException failure) {
            throw new WorkflowException(Stage.BUILD, failure.detail());
        }
    }

    private byte[] fetch(OutboundRequest request) {
        OutboundResponse response = http.send(request);
        if (response.status() != 200) {
            throw new WorkflowException(Stage.FETCH, "server returned HTTP " + response.status());
        }
        return response.body();
    }

    private static OutboundRequest outbound(Request fetch) {
        if (fetch == null || fetch.headers() == null || fetch.headers().size() > 16) {
            throw new WorkflowException(Stage.FETCH, "invalid request settings");
        }
        OutboundRequest request = OutboundRequest.get(parse(fetch.url(), Stage.FETCH)).header("Accept", "application/json");
        for (var header : fetch.headers()) {
            validateHeader(header);
            request = request.header(header.name(), header.value());
        }
        return request;
    }

    private static URI parse(URI uri, Stage stage) {
        return parse(uri == null ? null : uri.toString(), stage);
    }

    private static URI parse(String value, Stage stage) {
        try {
            return HttpUrls.parse(value, CALL_URLS);
        } catch (HttpUrls.InvalidUrlException _) {
            throw new WorkflowException(stage, "invalid HTTP URL");
        }
    }

    private static void validateHeader(WorkflowDraft.Header header) {
        if (header == null || header.name() == null || !header.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new WorkflowException(Stage.FETCH, "invalid request header");
        }
        String name = header.name().toLowerCase(Locale.ROOT);
        if (DENIED_HEADERS.contains(name) || name.startsWith("proxy-") || header.value() == null
                || header.value().chars().anyMatch(c -> c == '\r' || c == '\n' || c == 0)) {
            throw new WorkflowException(Stage.FETCH, "invalid request header");
        }
    }

    @Override public void close() {
        http.close();
    }
}
```

Check the `checkMedia` stage for an invalid URL against today's code (`bounded(Stage.BUILD, …)` around
`policy.parse`, which threw a `FETCH`-stage "invalid HTTP URL" that `bounded` then reported as a `BUILD` "request
failed"); keep whatever `WorkflowHttpClientTest` pins today, and ledger a ruling if the detail changes.
`WorkflowConfiguration` builds `new WorkflowHttpClient(properties, new OutboundAddressPolicy(properties.allowLoopback()))`
and no longer declares a `WorkflowUrlPolicy` bean; any bean or test that injected it takes the policy it needs.

- [ ] **Step 4: Update the workflow tests' expectations** only where the spec's visible change 5 alters a transport
  detail: `request failed` becomes `address not allowed`, `unknown host` or `connection refused` where that is the
  cause; `invalid HTTP URL` for a redirect's bad `Location` becomes `redirect to a link Home Control does not follow`.
  Every other detail (`server returned HTTP 404`, `response is too large`, `too many redirects`, `redirect changes
  origin; configure the final source URL`, `redirect has no destination`, `response compression is not supported`,
  `busy; try again later`, `request timed out`, `client is closed`, `invalid request header`, `invalid request
  settings`) stays.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green; `WorkflowUrlPolicy` gone.

- [ ] **Step 6: Commit** `refactor: fetch workflows through the guarded client`.

---

### Task 5: Calendars on the guarded client

**Files:**
- Modify: `sources/sports/calendar/CalendarFetcher.java` (rewritten on `GuardedHttpClient`),
  `sources/sports/SportsConfiguration.java`, `sources/sports/calendar/SportsCalendars.java` if it still takes a
  policy
- Delete: `sources/sports/calendar/CalendarUrlPolicy.java`, `CalendarUrlPolicyTest.java` (address cases are
  `OutboundAddressPolicyTest`'s; parse cases moved to `CalendarLinksTest` in Task 2)
- Modify tests: `new CalendarUrlPolicy(allowLoopback[, resolver])` → `new OutboundAddressPolicy(allowLoopback[,
  resolver])` in `CalendarFetcherTest`, `CalendarFetcherFailureTest`, `CalendarScheduleTest`, `SportsCalendarsTest`,
  `SlowBodyDeadlineTest`
- Test: `CalendarFetcherTest`, `CalendarFetcherFailureTest`

**Interfaces:**
- Consumes: Tasks 2–3.
- Produces: `CalendarFetcher(SportsProperties.Calendar, OutboundAddressPolicy)`, `String fetch(URI)`, `close()`.

- [ ] **Step 1: Write the failing tests**
  - `CalendarFetcherTest.aRedirectChainSharesOneDeadline` (visible change 2): request timeout 1 s; three hops, each
    delayed 400 ms (`Response.withDelay`), the last with a calendar → `CalendarFetchException` `UNREACHABLE` whose
    message ends `(request timed out)`. Today each hop gets a full second and the fetch succeeds.
  - `CalendarFetcherTest.refusesAnIpv4MappedLoopbackAddress` (visible change 3): a policy with `allowLoopback` false
    whose resolver answers `::ffff:127.0.0.1` → `BLOCKED`, and the fake receives nothing.
  - `CalendarFetcherFailureTest.noFailureRevealsTheSecretLink`: a link
    `http://calendar.test:<port>/secret-path/cal.ics?token=secret-token`; for a too-large body, a compressed body, a
    trickled body, a refused connection, a redirect loop and a blocked address, the message contains no `secret` and
    the exception has no cause.

- [ ] **Step 2: Run them.** `scripts/gradle.sh test --tests 'dev.andre.homecontrol.sources.sports.calendar.*'`.
  Expected: the new tests fail (the chain succeeds; the mapped address is let through; `CalendarFetcher` does not yet
  take an `OutboundAddressPolicy`).

- [ ] **Step 3: Implement**

```java
// File: src/main/java/dev/andre/homecontrol/sources/sports/calendar/CalendarFetcher.java
package dev.andre.homecontrol.sources.sports.calendar;

import dev.andre.homecontrol.core.content.ContentSourceException.Kind;
import dev.andre.homecontrol.sources.http.GuardedHttpClient;
import dev.andre.homecontrol.sources.http.OutboundAddressPolicy;
import dev.andre.homecontrol.sources.http.OutboundRequest;
import dev.andre.homecontrol.sources.http.OutboundResponse;
import dev.andre.homecontrol.sources.http.Statuses;
import dev.andre.homecontrol.sources.sports.SportsProperties;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches calendar links through the guarded client: every hop's address checked and pinned, redirects to any
 * origin re-checked, one deadline for the whole chain, and the body capped.
 */
public class CalendarFetcher implements AutoCloseable {

    /** The schedule fetches calendars one after another; a few more slots cover the setup page's checks. */
    private static final int MAX_CONNECTIONS = 4;
    private static final Pattern CHARSET = Pattern.compile("charset=\"?([^;\"]+)\"?", Pattern.CASE_INSENSITIVE);

    private final GuardedHttpClient http;

    public CalendarFetcher(SportsProperties.Calendar properties, OutboundAddressPolicy policy) {
        http = new GuardedHttpClient(new GuardedHttpClient.Profile("the calendar", GuardedHttpClient.Redirects.CHECKED,
                properties.maxRedirects(), properties.maxBytes(), properties.connectTimeout(),
                properties.requestTimeout(), MAX_CONNECTIONS, CalendarLinks.RULES),
                policy, failure -> new CalendarFetchException(failure.kind(), failure.describe("the calendar")));
    }

    public String fetch(URI url) {
        OutboundResponse response = http.send(OutboundRequest.get(url)
                .header("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.5")
                .header("User-Agent", "HomeControl"));
        int status = response.status();
        if (status == 200) {
            return new String(response.body(), charsetOf(response.contentType()));
        }
        String host = url.getHost();
        Kind kind = Statuses.kindOf(status);
        throw new CalendarFetchException(kind, switch (kind) {
            case UNAUTHORIZED -> host + " refused access to the calendar";
            case NOT_FOUND -> host + " has no calendar at that link";
            default -> host + " answered HTTP " + status;
        });
    }

    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    if (Charset.isSupported(m.group(1).strip())) {
                        return Charset.forName(m.group(1).strip());
                    }
                } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
                    // fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public void close() {
        http.close();
    }
}
```

The status message names the first host of the chain (`url.getHost()`), where today's names the hop that answered;
ledger a ruling if a test pins the hop's host. `SportsConfiguration` builds `new CalendarFetcher(properties.calendar(),
new OutboundAddressPolicy(properties.calendar().allowLoopback()))` and drops the `CalendarUrlPolicy` bean.

- [ ] **Step 4: Update the calendar tests' expectations** only where the spec's visible change 5 alters a transport
  message: "Could not reach cal.example" → "Could not reach the calendar at cal.example (…)"; "Could not find x" →
  "Could not reach the calendar at x (unknown host)"; the BLOCKED sentence → "Home Control does not connect to x
  (address not allowed)"; "The calendar is larger than 5 MB" → "The calendar at x sent more than 5 MB"; "The calendar
  link redirected too many times" and "x redirected to a link Home Control does not follow" → the `BAD_RESPONSE`
  sentences. Status sentences ("refused access", "has no calendar at that link", "answered HTTP n") stay.

- [ ] **Step 5: Run the build.** `scripts/gradle.sh build`. Expected: green; `CalendarUrlPolicy` gone.

- [ ] **Step 6: Commit** `refactor: fetch calendars through the guarded client`.

---

### Task 6: The decision record and the guide

**Files:**
- Create: `docs/adr/0005-outbound-http-for-content-sources.md`
- Modify: `docs/adr/README.md` (index), `docs/dev/architecture.md` (the `sources` row)

- [ ] **Step 1: Write the ADR** in the format of `docs/adr/0003-workflow-chains.md`:
  - **Decision:** every content source reaches the network through `sources.http.GuardedHttpClient`; it connects only
    to addresses `OutboundAddressPolicy` approved (looked up once, pinned); any-local, link-local and multicast are
    always refused, LAN allowed, loopback per source (Jellyfin yes; the others through their allow-loopback
    settings); one deadline covers the whole exchange; bodies are capped and never decompressed; failures name only
    the source and host and carry no cause; `ContentSourceException.Kind` is the one set of error kinds.
  - **Consequences:** a new source cannot open its own HTTP client (PR 2 makes that an ArchUnit rule); a source that
    must reach loopback needs a setting; error messages are uniform, so a source adds advice rather than rewording
    transport failures; all source traffic is HTTP/1.1.
- [ ] **Step 2: Update the index and the guide.** `docs/adr/README.md` lists 0005. In `docs/dev/architecture.md`, the
  `sources` row's sentence on `sources.http` becomes: "`sources.http` is shared: `GuardedHttpClient`, the one way a
  source reaches the network (pinned to the addresses `OutboundAddressPolicy` approves, one deadline, a body cap,
  host-only failures), and `HttpUrls`, the one parser for outbound links."
- [ ] **Step 3: Run the checks.** `scripts/gradle.sh build`, `scripts/gradle.sh compileE2eJava`,
  `scripts/e2e.sh -Pe2eBrowsers=chromium`. Expected: green.
- [ ] **Step 4: Commit** `docs: record the outbound HTTP decision for content sources`.
