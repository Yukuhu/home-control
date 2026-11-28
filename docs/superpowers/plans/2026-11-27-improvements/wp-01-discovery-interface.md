# WP-01: Discovery on the real LAN interface — Implementation Plan

> **For agentic workers:** work task by task, test first. Steps use checkbox (`- [ ]`) syntax: tick them in the commit
> that does them, and update this package's row in [the index](README.md) in the pull request that lands it.

**Goal:** On Debian, Ubuntu and Raspberry Pi OS hosts, where the host name resolves to 127.0.1.1, a Shield and Cast
devices show up under Setup again, because mDNS listens on the interface the host routes multicast through. A household
whose box sits on several networks picks the right one with `HOME_CONTROL_DISCOVERY_INTERFACE`, and the start log says
which address and interface discovery uses.

**Findings:**

- [DEV-1](../../reviews/2026-11-27-improvement-catalogue.md#dev-1): all of it. The bind address is chosen by routing,
  a loopback or foreign `getLocalHost()` result never wins, `home-control.discovery.interface` overrides the choice,
  `net.mdns.interface` keeps working, the choice is logged at INFO, and Android TV discovery uses `MdnsService.host()`.
- [OPS-1](../../reviews/2026-11-27-improvement-catalogue.md#ops-1) (merged into DEV-1): its details. Link-local
  addresses are passed over, the INFO line names address and interface, and the unit tests cover a 127.0.1.1 result
  and a multi-homed list. JmmDNS stays a follow-up (see Out of scope).
- [TEST-1](../../reviews/2026-11-27-improvement-catalogue.md#test-1) (merged into DEV-1): its tests come first.
  `aHostnameThatResolvesToLoopbackFallsBackToAnInterface` covers 127.0.1.1, 127.0.0.1 and ::1, and the test that pins
  the bypass (`resolvedContainerHostnameBypassesInterfaceFallback`) goes.

**Approach:** `MdnsBrowser.bindAddress` becomes `chooseBinding`, a pure function. It takes the setting, the legacy
`net.mdns.interface` value, the host's interface addresses and two lookups: the routed source address and
`getLocalHost()`. It returns an `MdnsBinding` (address, interface name, and how it was chosen), and `start()` passes the
address to `JmDNS.create` and logs `binding.describe()`. The order is: the setting, then `net.mdns.interface` (as
today: an address or a host name, used as given), then the address the kernel routes jmDNS's group from, then the host
name's address, then the first multicast-capable interface. The route comes from a `DatagramSocket` connected to
jmDNS's own `DNSConstants.MDNS_GROUP:MDNS_PORT`: a UDP connect only picks a route, it sends nothing. The route and
host-name candidates count only when they are an address of an interface of this host that is up, multicast-capable,
IPv4, not loopback, not link-local and not point-to-point. So 127.0.1.1, a stale `/etc/hosts` entry and a VPN tunnel
holding the default route never win, and `selectFallback` passes over point-to-point interfaces too. Passing `null` to
jmDNS stays ruled out: its own substitute takes `NetworkTopologyDiscovery`'s first address, which can be docker0 or
IPv6 (DEV-1).

Why this shape: with a 127.x bind address jmDNS 3.6.5 binds its send socket to (that address, 5353) and joins the group
on the matching interface (`JmDNSImpl.openMulticastSocket`). Its queries then never reach the LAN, so discovery
depends on devices announcing themselves. Its own loopback correction (`HostInfo.newHostInfo`) runs only for a `null`
address, which `MdnsBrowser` never passes. One routed address plus an explicit override fixes the main hosts without
JmmDNS's duplicate events and its answers on docker0 and VPN interfaces. Android TV's `MdnsDiscovery.toDevice` takes
the `MdnsService` and uses `host()`, as `CastDiscovery.toDevice` already does, so an AAAA-first answer no longer yields
an `fe80::` host that breaks the reconnect match in the `AndroidTvAdapter` constructor.

**Depends on / touches:** depends on nothing. It touches WP-20 (OPS-4 shows the mDNS address and interface on Setup;
it keeps the `MdnsBinding` that `start()` chooses, and adds the accessor it needs), WP-18 (OPS-10's network
troubleshooting page and DOCS-1's checked configuration guide take over the rows and the paragraph written here),
WP-22 (DEV-3, DEV-4 and DEV-V2 build device identity and re-pointing on the discovered host that Task 3 corrects) and
WP-31 (ARCH-7 restricts `DatagramSocket` to adapters and discovery; the probe lives in `discovery`). No ADR changes;
the code stays in the app module per ADR 0006, and there is still one jmDNS instance (the `MdnsBrowser` javadoc's
"discovery is one service").

**Effort:** S overall. PR 1: S. PR 2: S (a few lines, two new tests and three rewritten ones).

## Before you start

- Re-check the findings against current main. At 6a253e4, whose code is identical to 3932f9b
  (`git diff --stat 3932f9b HEAD -- src protocols core` is empty), every finding holds:
  - `MdnsBrowser.start()` calls `JmDNS.create(bindAddress(System.getProperty("net.mdns.interface"),
    InetAddress::getLocalHost, MdnsBrowser::interfaceFallback))` and logs only `"Listening for {}"`.
  - `MdnsBrowser.bindAddress` falls back only on `UnknownHostException`, so a loopback result is returned as is.
    `selectFallback` filters loopback, but only on that path.
  - `MdnsBrowserTest.resolvedContainerHostnameBypassesInterfaceFallback` pins the bypass, with 10.0.0.8.
  - `MdnsDiscovery.toDevice(String, InetAddress[], int)` uses `addresses[0].getHostAddress()`. The `AndroidTvAdapter`
    constructor matches `session.host().equalsIgnoreCase(found.host())`.
  - `HomeControlProperties.Discovery` has only `enabled`, and `HomeControlConfiguration.mdnsBrowser` passes only it.
  - `gradle/libs.versions.toml` still pins jmdns 3.6.5.

  Repeat these checks with `git log 3932f9b..origin/main -- src/main/java/dev/andre/homecontrol/discovery
  src/main/java/dev/andre/homecontrol/adapters/androidtv src/main/java/dev/andre/homecontrol/config` and by reading the
  two methods. If the jmDNS version moved, re-read `HostInfo.newHostInfo` and `JmDNSImpl.openMulticastSocket` with
  `javap -c`.
- Owner decisions. Ask these up front. None of them changes a product rule, a name that never changes, or an ADR:
  1. **Route first, or host name first?** Recommended: route first, as DEV-1 says. It fixes the 127.0.1.1 hosts and
     the hosts whose name resolves to docker0, a VPN or a second NIC. The cost is a host with two Ethernet networks
     whose name pointed at the TVs' network while the default route uses the other one: it now picks the routed one,
     says so in the log, and needs the setting. Host name first would leave the wrong-NIC case broken.
  2. **What does a setting that names nothing do?** Recommended: discovery stays off, with a WARN that names the
     setting and its value (`Could not start mDNS discovery (…); use manual entry`), and the app runs. Failing startup
     would take the whole remote down when a NIC is renamed after an OS upgrade. Falling back to the automatic choice
     would silently ignore what the household asked for.
  3. **Property name.** Recommended: `home-control.discovery.interface` with `HOME_CONTROL_DISCOVERY_INTERFACE`, the
     name the catalogue and the analysis promise. `interface` is a Java keyword, so the record component carries Spring
     Boot's `@Name("interface")` (`org.springframework.boot.context.properties.bind.Name`, made for such names). The
     alternative, `home-control.discovery.network-interface`, needs no annotation. If `@Name` turns out not to bind on
     a record component in Boot 4.1, the Task 2 binding test fails and this is the fallback.
  4. **`net.mdns.interface`.** Recommended: it keeps working with its current meaning (an address or host name, used as
     given), below the new setting. The INFO line says when it chose the address, and `docs/user/configuration.md`
     calls it the old setting. It does not go into `LegacyPropertyNames`: it is jmDNS's JVM system property, not a
     Spring key, and `LegacyPropertyNamesBindingTest` walks record components by name, which `interface` cannot be.
     This keeps the "existing installs upgrade in place" rule.
  5. **Hardware check.** The 127.0.1.1 outcome was inferred, not reproduced (analysis, "Open questions and limits").
     Recommended: not a merge gate. After PR 1 merges, the owner starts the image on a Raspberry Pi OS or Debian host
     with host networking. The start line should name the LAN interface, and a Shield should appear under Setup. The
     result goes in PR 1's "How it was tested", or in the index row as "unverified on hardware".

## Global constraints

- **A LAN appliance.** Nothing new listens or calls out. The route probe is a UDP socket that is connected, read and
  closed in one try-with-resources block; it sends no packet and joins no group. jmDNS stays the only multicast socket
  for mDNS, bound to one address.
- **State is JSON under `/data`.** No `/data` file changes, so nothing migrates. The setting is configuration only.
- **Existing installs upgrade in place.** `net.mdns.interface`, `home-control.discovery.enabled` and the legacy
  `shield.discovery-enabled` keep working. With discovery off, `start()` still returns before it enumerates an
  interface or probes a route.
- **Architecture.** jmDNS and the probe stay in `dev.andre.homecontrol.discovery`
  (`networkLibrariesStayInAdaptersSourcesAndDiscovery`). `discovery` does not depend on `config`:
  `HomeControlConfiguration` hands the setting to `MdnsBrowser`'s constructor as a `String`. The adapters keep reaching
  discovery only through `MdnsBrowser.Listener` and `MdnsService`.
- **Tests.** They go in the package and module of the code they test (`discovery`, `config`, `adapters.androidtv`,
  all in the root module). The selection is pure, so it needs no fake and no recording. No test opens a multicast
  socket. The one test that calls `start()` uses a setting that names nothing, so it fails before `JmDNS.create`.
- **SonarCloud.** The mDNS group comes from `DNSConstants.MDNS_GROUP` and `MDNS_PORT`, so no IP literal appears in main
  code (java:S1313). If an IP literal is still needed, use the narrowest `@SuppressWarnings("java:S1313")` with a
  one-line reason.
- `scripts/gradle.sh build` is green at the end of every task. Nothing a browser shows changes, so `scripts/e2e.sh` is
  not needed. Run `scripts/e2e.sh -Pe2eBrowsers=chromium` only if a template or script was touched by accident.
- Commits follow Conventional Commits (`scripts/check-commits.sh`), with scopes `discovery` and `androidtv` as in the
  log. Stage only the task's files with `git add <paths>`, never `git add -A`. Each commit ends with the session's
  `Co-Authored-By` and `Claude-Session` lines.

## Review focus

1. **A loopback host name never becomes the bind address.** Pinned by
   `MdnsBrowserTest.aHostnameThatResolvesToLoopbackFallsBackToAnInterface` (127.0.1.1, 127.0.0.1, ::1) and
   `aHostNameThatIsNoAddressOfThisHostDoesNotWin`.
2. **The routed interface wins over enumeration order, and a VPN tunnel or link-local source does not.** Pinned by
   `theInterfaceTheHostRoutesMulticastThroughWins` (docker0 listed before eth0), `aRouteThroughAVpnTunnelIsPassedOver`
   (tun0 listed first) and `aLinkLocalRouteSourceIsPassedOver`.
3. **Precedence: setting, then `net.mdns.interface`, then the automatic choice, and the documented environment
   variable binds.** Pinned by `theSettingWinsOverNetMdnsInterface`, `netMdnsInterfaceStillWinsOverTheAutomaticChoice`
   and `HomeControlPropertiesTest.theInterfaceSettingBindsFromItsEnvironmentVariable`. The wiring in
   `HomeControlConfiguration.mdnsBrowser` is one line: read it.
4. **A bad setting leaves discovery off with a reason, and the app runs.** Pinned by
   `aSettingThatNamesNoInterfaceOrAddressIsRefused`, `aSettingWithAnotherHostsAddressIsRefused`,
   `aSettingNamingAnInterfaceThatIsDownIsRefused` and `startSaysWhyDiscoveryIsOffWhenTheSettingNamesNothing`.
5. **The log says where discovery listens.** Pinned by `theChoiceIsDescribedForTheLog`. Check that `start()` logs
   `binding.describe()` at INFO, and that `routedSource()` only connects and closes.
6. **Android TV reports the IPv4 address it was announced with.** Pinned by
   `MdnsDiscoveryTest.prefersAnIpv4AddressWhenTheIpv6OneComesFirst` and
   `AndroidTvAdapterTest.anAnnouncementThatListsIpv6FirstStillReconnects`.

## File structure

- **Create**
  - `src/test/java/dev/andre/homecontrol/config/HomeControlPropertiesTest.java`
- **Modify**
  - `src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java`: `chooseBinding`, `MdnsBinding`,
    `InterfaceAddress` with name and point-to-point, `interfaces()`, `routedSource()`, the setting, the INFO line.
  - `src/main/java/dev/andre/homecontrol/config/HomeControlProperties.java`: `Discovery.networkInterface`.
  - `src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java`: `mdnsBrowser` passes the setting.
  - `src/main/resources/application.yaml`: `home-control.discovery.interface: ""` with its comment.
  - `src/main/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscovery.java`: `toDevice(MdnsService)`.
  - `compose.yaml`: the commented `environment:` example names the variable.
  - `docs/user/configuration.md`, `docs/user/devices.md`, `docs/dev/architecture.md`.
- **Test**
  - `src/test/java/dev/andre/homecontrol/discovery/MdnsBrowserTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscoveryTest.java`
  - `src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapterTest.java`
  - Unchanged, and must stay green: `DiscoveryStartTest`, `CastDiscoveryTest`, `CastPropertiesTest`,
    `CastModuleSwitchTest`, `ApplicationYamlTest`, `LegacyPropertyNamesBindingTest`, `BluetoothDeploymentTest`
    (it reads `compose.yaml`).

## Pull requests

| PR | Title | Tasks | Effort |
| --- | --- | --- | --- |
| 1 | `fix(discovery): listen for mDNS on the LAN interface, not on loopback` | 1, 2 | S |
| 2 | `fix(androidtv): reach a discovered Android TV at its IPv4 address` | 3 | S |

The two PRs are independent and can merge in either order. Task 1 alone already fixes the 127.0.1.1 hosts, so PR 1
leaves main releasable after either of its commits.

## Tasks

### Task 1: Choose the mDNS address by routing, never a loopback host name, and log it

**Files:** Modify `src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java`, `docs/user/devices.md`. Test
`src/test/java/dev/andre/homecontrol/discovery/MdnsBrowserTest.java`.

**Interfaces** (all package-private, in `MdnsBrowser`):

- `record InterfaceAddress(String name, InetAddress address, boolean up, boolean multicast, boolean loopback,
  boolean pointToPoint)` gains `name` and `pointToPoint`, plus `boolean usable()`: up, multicast, not loopback, not
  point-to-point, IPv4, neither a loopback nor the wildcard address.
- New `record MdnsBinding(InetAddress address, String interfaceName, Chosen chosen)` with
  `enum Chosen { LEGACY_SETTING, ROUTE, HOST_NAME, FIRST_INTERFACE }`, each with a phrase for the log, and
  `String describe()`.
- `static MdnsBinding chooseBinding(String legacySetting, List<InterfaceAddress> interfaces, AddressLookup routed,
  AddressLookup localHost) throws IOException` replaces `bindAddress`.
- `static Optional<InterfaceAddress> selectFallback(List<InterfaceAddress>)` returns the candidate (it carries the
  name), filters with `usable()`, and still prefers site-local.
- The constant `LEGACY_SETTING = "net.mdns.interface"` is new. Private `interfaces()` replaces `interfaceFallback()`,
  and private `routedSource()` is new.

- [ ] **Step 1: Write the failing test.** In `MdnsBrowserTest`, replace the helper `candidate(...)` with `lan(name,
  address)`, `loopback()` (`lo`, 127.0.0.1) and `tunnel(name, address)` (point-to-point). Add the lookups `at(address)`,
  `NO_ROUTE` (throws `SocketException("Network is unreachable")`), `UNRESOLVED` (throws `UnknownHostException`) and
  `UNUSED` (throws `AssertionError`), and a helper `automatic(routed, localHost, interfaces...)` that calls
  `chooseBinding(null, List.of(interfaces), routed, localHost)`. Then add these tests:

  ```java
  @ParameterizedTest
  @ValueSource(strings = {"127.0.1.1", "127.0.0.1", "::1"})
  void aHostnameThatResolvesToLoopbackFallsBackToAnInterface(String resolved) throws Exception {
      assertThat(automatic(NO_ROUTE, at(resolved), loopback(), lan("eth0", "192.168.1.42")))
              .isEqualTo(new MdnsBrowser.MdnsBinding(InetAddress.ofLiteral("192.168.1.42"), "eth0",
                      MdnsBrowser.MdnsBinding.Chosen.FIRST_INTERFACE));
  }

  @Test
  void theInterfaceTheHostRoutesMulticastThroughWins() throws Exception {
      MdnsBrowser.MdnsBinding binding = automatic(at("192.168.1.20"), UNUSED,
              loopback(), lan("docker0", "172.17.0.1"), lan("eth0", "192.168.1.20"));

      assertThat(binding.interfaceName()).isEqualTo("eth0");
      assertThat(binding.chosen()).isEqualTo(MdnsBrowser.MdnsBinding.Chosen.ROUTE);
  }

  @Test
  void theChoiceIsDescribedForTheLog() {
      assertThat(new MdnsBrowser.MdnsBinding(InetAddress.ofLiteral("192.168.1.20"), "eth0",
              MdnsBrowser.MdnsBinding.Chosen.ROUTE).describe())
              .isEqualTo("mDNS discovery on 192.168.1.20 (eth0, the interface the host routes multicast through)");
  }
  ```

  Also add:
  - `aRouteThroughAVpnTunnelIsPassedOver`: `automatic(at("10.8.0.2"), UNRESOLVED, loopback(), tunnel("tun0",
    "10.8.0.2"), lan("eth0", "192.168.1.20"))` gives eth0 and `FIRST_INTERFACE`. tun0 is listed first, so the test also
    proves that `selectFallback` passes over point-to-point interfaces.
  - `aHostNameOnALanInterfaceIsUsedWhenNothingIsRouted`: `automatic(NO_ROUTE, at("192.168.1.30"), lan("eth0",
    "192.168.1.20"), lan("eth1", "192.168.1.30"))` gives eth1 and `HOST_NAME`.
  - `aHostNameThatIsNoAddressOfThisHostDoesNotWin`: `automatic(NO_ROUTE, at("10.0.0.8"), lan("eth0",
    "192.168.1.20"))` gives eth0 and `FIRST_INTERFACE`. It replaces
    `resolvedContainerHostnameBypassesInterfaceFallback`, which is deleted.
  - `aLinkLocalRouteSourceIsPassedOver`: `automatic(at("169.254.10.2"), UNRESOLVED, lan("eth0", "169.254.10.2"),
    lan("wlan0", "192.168.1.20"))` gives wlan0.
  - `netMdnsInterfaceStillWinsOverTheAutomaticChoice`, renamed from
    `explicitBindAddressTakesPriorityOverHostnameAndFallback`: `chooseBinding("10.0.0.7", List.of(lan("eth0",
    "10.0.0.7")), UNUSED, UNUSED)` equals `new MdnsBinding(10.0.0.7, "eth0", LEGACY_SETTING)`.
  - Keep `unresolvedContainerHostnameFallsBackToMulticastCapableIpv4` and
    `noEligibleInterfacePreservesDiscoveryStartupFailure`, with names on their candidates, `NO_ROUTE`, and the same
    expected address and message (`No multicast-capable IPv4 interface is available`).
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.discovery.MdnsBrowserTest'`. Compilation fails with `cannot find symbol` for `MdnsBinding`
  and `chooseBinding`, and with the wrong arity for `InterfaceAddress`.
- [ ] **Step 3: Implement.** In `MdnsBrowser`:
  - `chooseBinding` uses `legacySetting` first, if it is non-null: `InetAddress.getByName` as today, with the interface
    name looked up in the list, or `unknown interface`. Next comes `onALanInterface(routed, …, ROUTE)`, then
    `onALanInterface(localHost, …, HOST_NAME)`, then `selectFallback(interfaces)` as `FIRST_INTERFACE`, and finally the
    existing `UnknownHostException("No multicast-capable IPv4 interface is available")`.
  - `onALanInterface` catches the lookup's `IOException` and returns `Optional.empty()`: no route and an unresolvable
    name both mean "try the next way". It returns the binding only when an interface address `equals` the result,
    `usable()` holds, and the address is not link-local.
  - `interfaces()` is the old enumeration loop. It adds `network.getName()` and `network.isPointToPoint()`, and still
    skips an interface that throws `SocketException`.
  - `routedSource()`:

    ```java
    /** The address the kernel would send an mDNS query from. A UDP connect only picks the route: nothing is sent. */
    private static InetAddress routedSource() throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            InetAddress group = InetAddress.ofLiteral(DNSConstants.MDNS_GROUP);
            probe.connect(new InetSocketAddress(group, DNSConstants.MDNS_PORT));
            return probe.getLocalAddress();
        }
    }
    ```

  - `start()` becomes:

    ```java
    MdnsBinding binding = chooseBinding(System.getProperty(LEGACY_SETTING), interfaces(),
            MdnsBrowser::routedSource, InetAddress::getLocalHost);
    jmdns = JmDNS.create(binding.address());
    listeners.keySet().forEach(type -> jmdns.addServiceListener(type, new JmdnsListener(type)));
    log.info("{}; listening for {}", binding.describe(), listeners.keySet());
    ```

    The existing `catch (IOException e)` WARN stays as it is. Its message now also carries `chooseBinding`'s reasons.
  - The `Chosen` phrases are "set by net.mdns.interface", "the interface the host routes multicast through", "the
    address of this host's name" and "the first multicast-capable interface". `describe()` returns
    `"mDNS discovery on " + address.getHostAddress() + " (" + interfaceName + ", " + phrase + ")"`.
- [ ] **Step 4: Run the tests.** Run `scripts/gradle.sh test --tests 'dev.andre.homecontrol.discovery.*'` and
  expect green. Then run `scripts/gradle.sh build`.
- [ ] **Step 5: Update docs.** Add a paragraph under "Discovery does not work" in `docs/user/devices.md`, after the
  existing one. It says that the start log names where discovery listens, for example
  `mDNS discovery on 192.168.1.20 (eth0, the interface the host routes multicast through)`, and that
  `Could not start mDNS discovery (…)` gives the reason it did not start. Keep the heading, which README's
  "discovery problems" points at.
- [ ] **Step 6: Commit.** `fix(discovery): listen for mDNS on the interface the host routes multicast through`. In the
  body, say that a 127.x bind address made jmDNS send its queries from a loopback-bound socket, so active queries
  never reached the LAN.

### Task 2: `home-control.discovery.interface` chooses the interface

**Files:** Modify `src/main/java/dev/andre/homecontrol/config/HomeControlProperties.java`,
`src/main/java/dev/andre/homecontrol/HomeControlConfiguration.java`,
`src/main/java/dev/andre/homecontrol/discovery/MdnsBrowser.java`, `src/main/resources/application.yaml`, `compose.yaml`,
`docs/user/configuration.md`, `docs/user/devices.md` and `docs/dev/architecture.md`. Create
`src/test/java/dev/andre/homecontrol/config/HomeControlPropertiesTest.java`. Test `MdnsBrowserTest`.

**Interfaces:**

- `HomeControlProperties.Discovery(@DefaultValue("true") boolean enabled, @Name("interface") String networkInterface)`.
  Its compact constructor turns `null` into `""` and strips the value.
- `public MdnsBrowser(boolean enabled, String networkInterface)` is new. `MdnsBrowser(boolean enabled)` stays and
  delegates with `""`, so `MdnsDiscovery(boolean)` and the Cast tests do not change.
- `chooseBinding(String setting, String legacySetting, List<InterfaceAddress>, AddressLookup, AddressLookup)` gains
  the first parameter. `Chosen.SETTING` ("set by home-control.discovery.interface") is new, and so is the constant
  `SETTING = "home-control.discovery.interface"`.

- [ ] **Step 1: Write the failing test.**
  - `HomeControlPropertiesTest` removes the real system sources in `@BeforeEach`, as `LegacyPropertyNamesTest` does.
    It binds with `Binder.get(environment).bindOrCreate("home-control", HomeControlProperties.class)` and has three
    tests:
    - `theInterfaceSettingBindsFromItsEnvironmentVariable`: a `SystemEnvironmentPropertySource` with
      `HOME_CONTROL_DISCOVERY_INTERFACE=eth0` gives `discovery().networkInterface()` equal to `"eth0"`.
    - `theInterfaceSettingIsTrimmed`: `home-control.discovery.interface= 192.168.1.20 ` gives `"192.168.1.20"`.
    - `withoutTheSettingTheHostChooses`: an empty environment gives `""`, and `enabled()` is `true`.
  - `MdnsBrowserTest`. The `automatic` helper and the legacy test pass `""` as the setting. New tests, with `UNUSED`
    for both lookups unless a test says otherwise:
    - `theSettingNamesAnInterface`: `"eth1"` with `lan("eth0", "192.168.1.20")`, `lan("eth1", "fe80::1")` and
      `lan("eth1", "10.0.30.5")` gives `new MdnsBinding(10.0.30.5, "eth1", SETTING)`. The interface's IPv4 address is
      taken.
    - `theSettingGivesAnAddressOfThisHost`: `"10.0.30.5"`, with the same interfaces, gives eth1 and `SETTING`.
    - `theSettingWinsOverNetMdnsInterface`: `chooseBinding("eth1", "192.168.1.20", …)`, with the same interfaces,
      gives eth1.
    - `aBlankSettingLeavesTheChoiceToTheHost`: `"  "` with `lan("eth0", "192.168.1.20")` and the routed lookup
      `at("192.168.1.20")` gives `ROUTE`.
    - `aSettingThatNamesNoInterfaceOrAddressIsRefused`: `"eth9"` throws `UnknownHostException` with the message
      `home-control.discovery.interface=eth9 is neither an interface of this host nor an address`.
    - `aSettingWithAnotherHostsAddressIsRefused`: `"192.168.1.99"` gives the message
      `home-control.discovery.interface=192.168.1.99 is not an address of this host`.
    - `aSettingNamingAnInterfaceThatIsDownIsRefused`: eth1's one IPv4 address is built with `up` false, and the
      message is
      `home-control.discovery.interface=eth1 names an interface that is down, cannot multicast or has no IPv4 address`.
    - `startSaysWhyDiscoveryIsOffWhenTheSettingNamesNothing(CapturedOutput output)`, with
      `@ExtendWith(OutputCaptureExtension.class)` as in `ReconnectorTest`. It runs
      `try (MdnsBrowser browser = new MdnsBrowser(true, "no-such-nic0")) { browser.start(); }`. The output contains
      `Could not start mDNS discovery (home-control.discovery.interface=no-such-nic0 is neither an interface of this
      host nor an address); use manual entry`. It reads the real interface list, but it opens no socket.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.discovery.MdnsBrowserTest' --tests 'dev.andre.homecontrol.config.HomeControlPropertiesTest'`.
  Compilation fails: there is no `networkInterface()`, no two-argument constructor and no five-argument
  `chooseBinding`.
- [ ] **Step 3: Implement.**
  - In `HomeControlProperties`, add the component with `@Name("interface")` and the compact constructor. Update the
    record's javadoc: "and on which interface".
  - `HomeControlConfiguration.mdnsBrowser` becomes `new MdnsBrowser(properties.discovery().enabled(),
    properties.discovery().networkInterface())`. The `MdnsBrowser` constructor's javadoc names
    `home-control.discovery.*`.
  - `start()` passes the field `networkInterface` as the setting. `chooseBinding` calls `configured(setting.strip(),
    interfaces)` when the setting is not blank:
    - When an interface has exactly that name, take its addresses that are up, multicast and IPv4, site-local first.
      If it has none, refuse with "names an interface that is down, cannot multicast or has no IPv4 address". The
      explicit choice may name a point-to-point interface.
    - Otherwise, parse `InetAddress.ofLiteral(setting)`. An `IllegalArgumentException` means "is neither an interface
      of this host nor an address". The address must `equals` an interface address, or it is refused with "is not an
      address of this host". There is no DNS lookup for the new setting.
  - In `application.yaml`, under `home-control.discovery`:

    ```yaml
        # Blank: the interface the host routes multicast through. Otherwise an interface name such as eth0 or one of
        # this host's addresses, for a host on several networks. The start log names the choice.
        interface: ""
    ```

  - In `compose.yaml`, extend the commented `environment:` example with
    `#   HOME_CONTROL_DISCOVERY_INTERFACE: eth0`. Add one comment line: set it only when the start log names the wrong
    network.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.config.*' --tests 'dev.andre.homecontrol.ApplicationYamlTest'`, then `scripts/gradle.sh
  build`. All green.
- [ ] **Step 5: Update docs.**
  - `docs/user/configuration.md`: a row after `home-control.discovery.enabled`. Property:
    `home-control.discovery.interface`. Default: blank. Meaning: "The network mDNS discovery listens on: an interface
    name such as `eth0` or one of this host's addresses (`HOME_CONTROL_DISCOVERY_INTERFACE`). Blank: the interface
    the host routes multicast through. The start log names the choice. `net.mdns.interface`, the old JVM setting,
    still works when this one is blank".
  - `docs/user/devices.md`, under "Discovery does not work": when the logged interface is not the TVs' network (a VPN,
    a Docker bridge, a second network card), set `HOME_CONTROL_DISCOVERY_INTERFACE` under `environment:` in
    `compose.yaml`, or in the app's settings on CasaOS. A value that matches nothing leaves discovery off, and the log
    says why.
  - `docs/dev/architecture.md`, the `discovery` row: "mDNS listens on one address, chosen by
    `MdnsBrowser.chooseBinding`: `home-control.discovery.interface`, then `net.mdns.interface`, then the address the
    host routes multicast through, the host name's address, or the first multicast-capable interface; the start log
    names it."
- [ ] **Step 6: Commit.** `feat(discovery): choose the mDNS interface with home-control.discovery.interface`.

### Task 3: Android TV discovery takes the announced IPv4 address

**Files:** Modify `src/main/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscovery.java`. Test
`src/test/java/dev/andre/homecontrol/adapters/androidtv/MdnsDiscoveryTest.java` and
`src/test/java/dev/andre/homecontrol/adapters/androidtv/AndroidTvAdapterTest.java`.

**Interfaces:** `static Optional<DiscoveredDevice> toDevice(MdnsBrowser.MdnsService service)` replaces
`toDevice(String name, InetAddress[] addresses, int port)`.

- [ ] **Step 1: Write the failing test.**
  - `MdnsDiscoveryTest.prefersAnIpv4AddressWhenTheIpv6OneComesFirst`. It goes through the existing package-private
    `resolved`, so it compiles before the change:

    ```java
    try (MdnsDiscovery discovery = new MdnsDiscovery(false)) {
        discovery.resolved(new MdnsBrowser.MdnsService(MdnsDiscovery.SERVICE_TYPE, "Living Room Shield",
                List.of(InetAddress.ofLiteral("fe80::1"), InetAddress.ofLiteral("192.168.1.50")), 6466, Map.of()));

        assertThat(discovery.devices())
                .containsExactly(new DiscoveredDevice("androidtv", "Living Room Shield", "192.168.1.50", 6466));
    }
    ```

  - `AndroidTvAdapterTest.anAnnouncementThatListsIpv6FirstStillReconnects`. It is a copy of
    `aDeviceThatAnnouncesItselfIsReconnectedAtOnce` whose announcement lists `fe80::1` before `127.0.0.1`. With
    one-minute retries, only a host match reconnects within the 10 s await. This pins the reconnect match in the
    `AndroidTvAdapter` constructor.
- [ ] **Step 2: Run it to see it fail.** Run `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.androidtv.MdnsDiscoveryTest' --tests
  'dev.andre.homecontrol.adapters.androidtv.AndroidTvAdapterTest'`. The first test fails because the host was
  `fe80:0:0:0:0:0:0:1`. The second fails with a `ConditionTimeoutException`.
- [ ] **Step 3: Implement.** `resolved(service)` calls `toDevice(service)`, and `toDevice` maps the service:

  ```java
  /** Pure mapping so it can be tested without multicast; {@link MdnsBrowser.MdnsService#host()} puts IPv4 first. */
  static Optional<DiscoveredDevice> toDevice(MdnsBrowser.MdnsService service) {
      if (service.port() <= 0) {
          return Optional.empty();
      }
      return service.host().map(host ->
              new DiscoveredDevice(AndroidTvSettings.ADAPTER_ID, service.name(), host, service.port()));
  }
  ```

  Drop the `java.net.InetAddress` import. Rewrite `mapsAResolvedServiceToADevice`, `ignoresAServiceWithNoAddress` and
  `ignoresAServiceWithNoPort` to build an `MdnsService`.
- [ ] **Step 4: Run the tests.** Run the Step 2 command, then `scripts/gradle.sh test --tests
  'dev.andre.homecontrol.adapters.*'`, then `scripts/gradle.sh build`. All green.
- [ ] **Step 5: Update docs.** None. The guides do not mention which address is taken.
- [ ] **Step 6: Commit.** `fix(androidtv): reach a discovered Android TV at its IPv4 address`.

## Done when

- [DEV-1](../../reviews/2026-11-27-improvement-catalogue.md#dev-1) is resolved in full, with the details of
  [OPS-1](../../reviews/2026-11-27-improvement-catalogue.md#ops-1) and
  [TEST-1](../../reviews/2026-11-27-improvement-catalogue.md#test-1). Only JmmDNS is left out, with its reason below.
- With no setting, `chooseBinding` never returns a loopback, link-local, point-to-point or foreign address. The
  routed interface wins, and every case in Review focus has its test.
- `home-control.discovery.interface` and `HOME_CONTROL_DISCOVERY_INTERFACE` take an interface name or an address of
  this host, and win over `net.mdns.interface`, which still works. A value that matches nothing leaves discovery off
  with a WARN that names it, and the rest of the app works.
- Every start with discovery on logs one INFO line with the address, the interface and how they were chosen.
- `MdnsDiscovery` reports an announced IPv4 address, and an IPv6-first announcement reconnects a known Shield.
- `docs/user/configuration.md`, `docs/user/devices.md`, `docs/dev/architecture.md`, `application.yaml` and
  `compose.yaml` describe the setting and the log line.
- `scripts/gradle.sh build` is green after each commit, and `scripts/check-commits.sh` accepts the commits.
- The owner's hardware check (decision 5) is recorded in PR 1, or the index row says "unverified on hardware".
- This package's row in [the index](README.md) is updated.

## Out of scope

- **JmmDNS, one jmDNS per interface.** OPS-1 suggests it as a follow-up for hosts that really are multi-homed. DEV-1's
  trade-off rejects it for now: it would also answer on docker0 and VPN interfaces and doubles events. The explicit
  setting covers these hosts. Revisit it only if field reports show that one interface is not enough.
- **mDNS over IPv6, and Android TV devices that announce only a link-local IPv6 address.** The browser stays
  IPv4-only, as `selectFallback` already was.
- **SSDP.** `SsdpDiscovery.joinEverywhere` already joins on every up, multicast, non-loopback interface. Its sockets
  are not part of these findings.
- **Showing the binding on Setup.** That is WP-20 (OPS-4), which adds the accessor it needs.
- **The network troubleshooting page and the machine-checked configuration guide.** These are WP-18 (OPS-10,
  DOCS-1). They take over the row and the paragraph written here.
- **Device identity across address changes, and Cast re-pointing.** That is WP-22 (DEV-3, DEV-4, DEV-V2).
- **jmDNS's reverse lookup of the bound address for its host name** (`JmDNS.create(address)` with no name), and
  **skipping jmDNS when no module listens.** Neither is in the findings, and both are unchanged.
