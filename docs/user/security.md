# Security

What Home Control protects, and how.

Device-only deployments have no authentication: anyone who can reach the port can control the
TV. This is deliberate for a LAN-only tool. Connecting a content source (see
[Content sources and login](sources.md#content-sources-and-login)) adds a login password that
then guards every page. Either way, do not expose this app to the internet without putting an
authenticating reverse proxy in front of it.

## Secrets

Secrets (session tokens, the login password hash) live in `/data/secrets.json`, encrypted at
rest. The key is either:

- a random `/data/secret.key` created next to it the first time a secret is stored (the
  default), or
- the `HOME_CONTROL_SECRET` environment variable, if set.

**Honest limit:** with the default key file, copying the whole `/data` directory (a backup, a
migration) copies the key along with the encrypted secrets — anyone with that copy can decrypt
them. Set `HOME_CONTROL_SECRET` if that risk matters to you; a copy of `/data` alone is then
useless without it. Once you start the app with `HOME_CONTROL_SECRET` set, changing or losing
that value stops the app from starting until the original value is restored — there is no
partial recovery.

**Forgotten login password:** stop the container, delete `/data/secrets.json`, and reconnect
your content sources. This clears every stored secret and login password; there is no other
way to reset just the password.

Behind an HTTPS reverse proxy, set `HOME_CONTROL_SECURE_COOKIE=true` so the login cookie is
marked `Secure`. If the proxy rewrites the `Host` header, also set
`HOME_CONTROL_TRUSTED_ORIGINS` (see [Configuration](configuration.md)) to the origin your browser actually
sees, or requests will be refused as cross-site.
