# Harden a public server

This checklist covers the settings that matter when Kaillux is reachable from
the internet. It assumes you have already read
[Security model](../explanation/security-model.md).

## 1. Protect the admin surface

- Set a long, unique `ADMIN_PASSWORD`; do not use `admin`, `password`,
  `changeme`, or `123456`.
- Set a non-default `ADMIN_USERNAME` if your deployment allows it.
- Make insecure credentials fail startup:

  ```properties
  admin.security.failOnInsecureCredentials=true
  ```

  Activating the `prod` or `production` Spring profile has the same effect.

- Bind the HTTP listener to a private interface or put it behind a firewalled
  reverse proxy. The Compose file binds 8080 to `127.0.0.1`; keep that pattern.
- Terminate TLS at the proxy. Kaillux serves plain HTTP.
- Restrict `/api-docs`, `/swagger-ui.html`, `/metrics`, and `/healthz` at the
  proxy if they should not be public. The application leaves them open.
- Do not reuse the admin password anywhere else.

## 2. Restrict who may connect

In `access.cfg`:

```text
# Optional: allow only your networks, then deny the rest.
ipaddress,ALLOW,192.168.0.0*|10.*
ipaddress,DENY,*
```

If the server is meant to be public, keep `ipaddress,ALLOW,*` but use the other
controls below. Deny known abusers with `ipaddress,DENY,<address>` or temporary
`/ban`.

Set a sensible user cap:

```properties
server.max-users=50
```

## 3. Cap handshake and connection abuse

```properties
server.max-connecting=10
server.max-connecting-per-address=4
server.hello-rate-per-second=3
```

These limits are independent. `max-connecting` protects the server as a whole
during handshakes; the per-address cap and HELLO rate stop one source from
occupying every slot. Values that are too low can reject legitimate clients
behind a shared NAT, so tune them against real traffic.

## 4. Reject poor or unwanted clients

```properties
server.max-ping=250
server.allowed-connection-types=1,2,3,4,5,6
server.max-user-name-length=30
server.max-client-name-length=100
server.max-chat-length=150
server.chat-flood-time=2
server.create-game-flood-time=2
server.keep-alive-timeout=190
server.idle-timeout=900
```

Use `emulator` and `game` rules to block clients or ROMs you do not want:

```text
emulator,DENY,SomeKnownAbusiveClient*
emulator,ALLOW,*
game,DENY,*Prohibited ROM*
game,ALLOW,*
```

Elevated and admin addresses bypass most normal-user limits by design. Audit
`user,ADMIN,...` and `user,ELEVATED,...` rules regularly.

## 5. Limit game resources

```properties
server.max-games=0
game.buffer-size=1024
game.timeout-millis=1250
game.desynch-timeouts=4
```

`max-games=0` means unlimited. If memory or CPU is constrained, set a finite
cap. Lower game timeouts reduce the impact of lagging clients but increase
desyncs; measure before changing them.

## 6. Run with least privilege

- Run as an unprivileged account. The Arch service already does this and enables
  systemd hardening options.
- Restrict `access.cfg` and any file containing the admin password to the
  service account (`chmod 600`).
- Keep the JVM and OS patched.
- If file logging is enabled, monitor disk usage and rotation.

## 7. Expose only required ports

| Port | Protocol | Exposure |
|---|---|---|
| `controllers.connect.port` (27888) | UDP | Public, required by clients. |
| Data-port range | UDP | Public, required by clients. |
| Admin/HTTP (8080) | TCP | Private or behind a TLS proxy. |
| `relay.listen-port` (27887) | UDP | Only if relay mode is used. |

Do not expose the admin port, metrics, or Swagger UI directly to the internet.

## 8. Watch for abuse

- Enable and review logs for repeated denials, flood warnings, and desyncs.
- Scrape `/metrics` from inside the network and alert on unusual desync, drop,
  or connection-denial rates.
- Use `GET /api/admin/server-info` to watch `deniedFull` and `deniedOther`.
- Use `GET /api/admin/event-metrics` to find clients whose outbound queue is
  filling, which can indicate a stalled or abusive client.
- Review `access.cfg` after every incident rather than only adding temporary
  bans, which are lost on restart.

## 9. Keep admin actions deliberate

The admin API can kick users but cannot ban them. Temporary bans and silences
come from chat commands and expire. For a persistent response, add an
`ipaddress,DENY` line or a `user` rule and save the file.
