# Security model

Kaillux has two distinct security surfaces: the game protocol, which is
unauthenticated by design, and the admin/HTTP surface, which is authenticated.

## Game protocol

Kaillera clients do not present a password or a cryptographic identity. The
server therefore treats the source address as the only stable identity:

- the connect handshake and the data-port session must come from the same
  address;
- admin chat commands verify that the command arrived from the peer address that
  performed the handshake;
- access rules, temporary bans, and admin grants are keyed by address.

Everything else is policy: allowed addresses, allowed emulators, allowed games,
name lengths, ping limits, connection types, flood intervals, and admission
caps. None of it makes the protocol confidential or tamper-proof. A network
attacker who can spoof UDP source addresses and guess session ports can inject
traffic. Deploy behind a network you trust, or use a relay/proxy that constrains
the traffic.

## Admin credentials

The admin API uses HTTP Basic authentication with a single account:

- `admin.username` defaults to `admin`;
- `admin.password` comes from configuration or the `ADMIN_PASSWORD` environment
  variable.

If no password is configured, the server generates a 24-byte random password,
prints it once to standard error, and does not write it to the application log.
The password is hashed in memory with BCrypt.

The server warns at startup when the username is the default or the password is
one of a small set of common values. With the `prod` or `production` profile
active, or with `admin.security.failOnInsecureCredentials=true`, that warning
becomes a startup failure. This prevents a deployment from silently exposing the
admin API with a published default.

The admin UI keeps the Basic authorization header in `sessionStorage`, so it is
scoped to the tab and cleared when the session ends. It does not use cookies.

## HTTP surface

Security is stateless and CSRF protection is disabled, because every admin
request carries credentials explicitly rather than relying on an ambient cookie.
If cookie-based sessions are added later, CSRF protection must be re-enabled.

| Path | Access |
|---|---|
| `/api/admin/**`, `/api/v1/admin/**` | Authenticated. |
| `/metrics` | Public. |
| `/healthz`, `/healthz/liveness`, `/healthz/readiness` | Public. |
| `/actuator/health`, `/actuator/info` | Public. |
| Other `/actuator/**` endpoints | Authenticated. |
| `/admin/`, static assets | Public; the UI itself asks for credentials. |
| `/api-docs`, `/swagger-ui.html` | Public by default. |

A missing or invalid credential returns HTTP 401 without a
`WWW-Authenticate` challenge. This is deliberate: a browser challenge would
open the browser's own login dialog and leave `fetch()` waiting, which breaks
the dashboard's ability to report a rejected password.

The server serves plain HTTP. If the admin surface must cross an untrusted
network, put a TLS-terminating reverse proxy in front of it and restrict access
there. The bundled Compose file binds port 8080 to `127.0.0.1` for this reason.

## Admission and abuse controls

The server has several independent limits that together make a naive flood
expensive:

- `server.max-users` — logged-in users;
- `server.max-connecting` — in-progress handshakes;
- `server.max-connecting-per-address` — handshakes from one source;
- `server.hello-rate-per-second` — HELLO messages from one source;
- `server.max-ping` — high-latency logins;
- `server.chat-flood-time`, `server.create-game-flood-time` — action frequency;
- keepalive and idle timeouts — abandoned sessions.

The per-address HELLO limit and the connecting cap are tracked in memory and are
per instance. The map of tracked addresses is bounded and swept; it is not a
distributed rate limiter.

## What is not protected

- The game protocol has no encryption or authentication.
- The master-list touch does not authenticate the server to the master.
- Relay mode forwards traffic without adding authentication or filtering.
- The admin API has a single account; there are no roles, audit log, or key
  rotation.
- Configuration files may contain the admin password in plain text. Protect file
  permissions and prefer the environment variable in container deployments.

See [Harden a public server](../how-to/harden-a-public-server.md) for the
operational checklist.
