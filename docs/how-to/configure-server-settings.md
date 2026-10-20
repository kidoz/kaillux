# Configure server settings

Kaillux reads Spring Boot properties from `application.properties` and from
standard Spring external configuration sources. This guide covers the changes
operators make most often. For every property and its default, see
[Configuration properties](../reference/configuration.md).

## Choose where settings live

Settings are resolved in this order, with later sources overriding earlier
ones:

1. packaged `src/main/resources/application.properties`;
2. an external configuration file passed with
   `--spring.config.location=file:/path/` (or
   `--spring.config.additional-location=file:/path/`);
3. environment variables;
4. command-line arguments.

Property names use kebab-case. The environment variable is the same name in
upper snake case:

```text
server.max-users  ->  SERVER_MAX_USERS
admin.password    ->  ADMIN_PASSWORD
relay.backend-host -> RELAY_BACKEND_HOST
```

For a packaged install, `/etc/kaillux/application.properties` is the external
file. For Docker, put overrides in the `environment` block of `compose.yaml`.

A property change requires a restart. Only `access.cfg` supports hot reload.

## Change the connect port

```properties
controllers.connect.port=27888
```

Update the firewall, the master-list advertisement, and every client's server
address. The data-port range does not have to change unless it overlaps the new
port.

## Change the data-port range and capacity

```properties
controllers.v086.port-range-start=27889
controllers.v086.extra-ports=10
server.max-users=25
```

The server allocates `server.max-users + controllers.v086.extra-ports + 1`
consecutive UDP ports starting at `port-range-start`. With the values above it
uses 27889–27924. Open exactly that range in the firewall. If the range would
run past 65535, reduce the user limit or move the start.

Keep a small `extra-ports` margin so a new client can be assigned a port while a
disconnected one is still being cleaned up.

## Bind IPv4, IPv6, or both

```properties
controllers.bind-addresses=0.0.0.0,::
```

This is a comma-separated list of literal addresses or hostnames. To avoid
dual-stack bind conflicts in a container, set only IPv4:

```properties
controllers.bind-addresses=0.0.0.0
```

The connect controller binds every address in the list on the connect port; the
data controller binds the same addresses across the data-port range.

## Limit who may connect

```properties
server.max-users=25
server.max-connecting=10
server.max-connecting-per-address=8
server.hello-rate-per-second=5
server.max-ping=250
server.allowed-connection-types=1,2,3,4,5,6
```

- `max-users` caps logged-in users. Elevated and admin clients bypass it.
- `max-connecting` caps in-progress handshakes; 0 disables the cap.
- `max-connecting-per-address` caps handshakes from one source; 0 disables it.
- `hello-rate-per-second` limits HELLO messages per source; 0 disables it.
- `max-ping` rejects normal users above the threshold (milliseconds) at login.
  It also sets the handshake timeout (`max-ping * 15` ms) and the maintenance
  interval (`max-ping * 3` ms, with a one-second floor).
- `allowed-connection-types` is a comma-separated subset of 1–6. Normal users
  outside it are rejected at login.

Address allow/deny rules and access levels are separate; see
[Configure access control](configure-access-control.md).

## Change timeouts and game behavior

```properties
server.keep-alive-timeout=190
server.idle-timeout=900
game.buffer-size=1024
game.timeout-millis=1250
game.desynch-timeouts=4
game.default-auto-fire-sensitivity=0
```

`keep-alive-timeout` and `idle-timeout` are in seconds. `idle-timeout` applies
only to normal users; 0 disables it. `timeout-millis` is how long input
assembly waits for a lagging player, and `desynch-timeouts` is how many
consecutive waits are tolerated before that player is marked desynchronized.
Raising the timeout improves tolerance for jitter but adds latency for everyone.

## Limit games and names

```properties
server.max-games=0
server.allow-single-player=true
server.allow-multiple-connections=true
server.max-user-name-length=45
server.max-client-name-length=100
server.max-chat-length=150
server.max-game-name-length=100
server.max-quit-message-length=75
server.chat-flood-time=2
server.create-game-flood-time=2
```

`max-games=0` means unlimited; the cap applies to normal users, not elevated or
admin users. Flood times are in seconds (0 disables the interval). Name and
message limits are character counts.

## Set admin credentials

```properties
admin.username=admin
admin.password=${ADMIN_PASSWORD:}
admin.security.failOnInsecureCredentials=false
```

Prefer the `ADMIN_PASSWORD` environment variable over a plaintext file. If no
password is set, the server generates one and prints it once to the console.
Set `failOnInsecureCredentials=true` (or activate the `prod` profile) to refuse
startup with a default username or a known-weak password.

## Describe the server to master lists

```properties
master-list.server-name=My Kaillux Server
master-list.server-location=Berlin, DE
master-list.server-website=https://example.com
master-list.server-connect-address=kaillux.example.com
master-list.touch-kaillera=true
master-list.touch-emulinker=true
```

Set `server-connect-address` to the address clients should use. See
[Register with master servers](register-with-master-servers.md).

## Enable relay mode

Relay settings live under the `relay.` prefix and require
`relay.enabled=true`. See [Enable relay mode](enable-relay-mode.md) for the full
procedure.

## Verify a change

After restarting, check the startup log and the admin API:

```bash
curl -s -u 'admin:password' http://127.0.0.1:8080/api/admin/server-info
```

The response shows the connect port, current and maximum users, maximum games,
and thread-pool counters. The startup log prints the exact data-port range:

```text
Listening on UDP ports: 27889 to 27924 ...
```
