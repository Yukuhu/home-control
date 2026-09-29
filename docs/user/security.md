# Security

What Home Control protects, and how.

Without a login password, anyone who can reach the port can control the TV. This is deliberate
for a LAN-only tool. Set a password in **Setup → Account**, or when you connect a content source
(see [Content sources and login](sources.md#content-sources-and-login)); from then on it guards
every page. It stays until you remove it there, which is possible once no connected source needs
it. Either way, do not expose this app to the internet without putting an authenticating reverse
proxy in front of it.

## Secrets

Secrets live in `/data/secrets.json`, encrypted at rest: the login password hash, the content
sources' tokens and keys, and the devices' own credentials (the pairing keys of LG and Samsung
TVs, and the Android TV keystore's password). The key is either:

- a random `/data/secret.key` created next to it the first time a secret is stored (the
  default), or
- the `HOME_CONTROL_SECRET` environment variable, if set.

**Honest limit:** with the default key file, copying the whole `/data` directory (a backup, a
migration) copies the key along with the encrypted secrets — anyone with that copy can decrypt
them. Set `HOME_CONTROL_SECRET` if that risk matters to you; a copy of `/data` alone is then
useless without it. Once you start the app with `HOME_CONTROL_SECRET` set, changing or losing
that value stops the app from starting until the original value is restored — there is no
partial recovery.

**Forgotten login password:** start the app with `HOME_CONTROL_RESET_LOGIN=true`. The first
start with it removes the login password and the content sources' credentials, and logs that it
did; the TV pairings stay. The reset runs only once, so a setting left in place does no harm, but
remove it again: only a start without it allows another reset later. Then set a new password in
**Setup → Account** and reconnect your content sources. Do not delete `secrets.json` for this:
it also holds the TV pairings.

Behind an HTTPS reverse proxy, set `HOME_CONTROL_SECURE_COOKIE=true` so the login cookie is
marked `Secure`. If the proxy rewrites the `Host` header, also set
`HOME_CONTROL_TRUSTED_ORIGINS` (see [Configuration](configuration.md)) to the origin your browser actually
sees, or requests will be refused as cross-site.

## Allowed hosts and origins

The app only answers to host names that cannot be pointed at it by someone else's DNS
(DNS rebinding): IP addresses, `localhost`, single-label names such as `nas`, and names
ending in `.local`, `.lan`, `.home.arpa` or `.internal`. Any other name gets
`421 Misdirected Request`. If you reach it under a real domain, for example through a
reverse proxy, add that name to `HOME_CONTROL_ALLOWED_HOSTS` (or its origin to
`HOME_CONTROL_TRUSTED_ORIGINS`). Changes (POST and other non-read requests) from another
site's page are refused with `403`, whether or not a login exists.
