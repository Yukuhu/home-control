# ADR: Outbound HTTP for content sources

**Date:** 2026-09-30
**Status:** Accepted
**Context:** Roadmap workstream 2C (outbound HTTP for sources), first pull request.
**Spec:** `docs/superpowers/specs/2026-09-30-phase-2c-outbound-http-design.md`.

## Decision

- Every content source reaches the network through `sources.http.GuardedHttpClient`, built once per source from a
  profile: its name, redirect mode, body cap, deadline and number of concurrent requests. Device adapters and
  discovery keep their own transports, because LAN device protocols trust the network differently.
- The client connects only to addresses `OutboundAddressPolicy` approved, looked up once and pinned, so a host cannot
  pass the check at one address and be reached at another. Any-local, link-local (cloud metadata lives there) and
  multicast addresses are always refused, the LAN is allowed, and loopback only for a source that allows it: Jellyfin
  always, the calendars and workflows through their allow-loopback settings. An IPv4-mapped IPv6 address counts as the
  IPv4 address it carries.
- One deadline covers waiting for a slot, DNS, connecting, every redirect and the whole body. Bodies are capped,
  Content-Length first, and never decompressed; an error body is read only when the source asks for it.
- A failure names the source and the host and carries no cause, because transport exceptions can carry the URL, and
  with it an API key or a secret calendar path.
- `ContentSourceException.Kind` is the one set of error kinds every source uses. `HttpUrls` is the one parser for
  outbound links; each caller words its own refusal.

## Consequences

- A new source does not open an HTTP client of its own; the second pull request of 2C makes that an ArchUnit rule.
- A source that must reach this machine needs a setting that allows loopback.
- Transport failures read the same in every source. A source adds its own advice to them rather than rewording them.
- All source traffic uses HTTP/1.1, as Apache HttpClient's classic API speaks it.
