# Configuration properties

Kaillux is configured with Spring Boot properties. The values below are the
effective defaults in `src/main/resources/application.properties`.

Property names use kebab-case. The environment variable is the same name in
upper snake case with dots and dashes replaced by underscores:

```text
controllers.connect.port  ->  CONTROLLERS_CONNECT_PORT
master-list.touch-kaillera -> MASTER_LIST_TOUCH_KAILLERA
```

Lists are comma-separated in both forms. Changes require a restart; only
`access.cfg` is reloaded at runtime. See
[Configure server settings](../how-to/configure-server-settings.md).

## Controllers

| Property | Default | Description |
|---|---|---|
| `controllers.bind-addresses` | `0.0.0.0,::` | Addresses to bind. Each address gets its own UDP channel. Use `0.0.0.0` alone to disable IPv6. |
| `controllers.connect.port` | `27888` | Public UDP port for the initial handshake. |
| `controllers.connect.buffer-size` | `1024` | Receive buffer for connect-port datagrams, in bytes. |
| `controllers.v086.port-range-start` | `27889` | First data port. |
| `controllers.v086.extra-ports` | `10` | Spare data ports beyond `server.max-users`. The allocated count is `max-users + extra-ports + 1`. |
| `controllers.v086.buffer-size` | `2048` | Receive buffer for data-port datagrams, in bytes. |
| `controllers.v086.client-types` | `0.83` | Client protocol versions routed to the v0.86 controller. |

## Server

| Property | Default | Description |
|---|---|---|
| `server.max-ping` | `250` | Maximum login ping in milliseconds for normal users. Also drives the maintenance interval (`max(1000, max-ping * 3)` ms) and the handshake timeout (`max-ping * 15` ms). |
| `server.allowed-connection-types` | `1,2,3,4,5,6` | Connection types normal users may use. |
| `server.max-users` | `25` | Maximum logged-in users. Elevated and admin users bypass it. 0 disables the cap. |
| `server.max-connecting` | `10` | Maximum in-progress handshakes. 0 disables the cap. |
| `server.max-connecting-per-address` | `8` | Maximum in-progress handshakes from one source address. 0 disables the cap. |
| `server.hello-rate-per-second` | `5` | Maximum HELLO messages per source per second. 0 disables the limit. |
| `server.max-games` | `0` | Maximum game rooms for normal users; 0 means unlimited. Elevated and admin users bypass it. |
| `server.allow-single-player` | `true` | Allow a normal user to start a room alone. |
| `server.allow-multiple-connections` | `true` | Allow several normal users from the same address. When false, a second name from the same address is rejected. Elevated and admin users are exempt. |
| `server.keep-alive-timeout` | `190` | Seconds without a keepalive before a logged-in user is disconnected. |
| `server.idle-timeout` | `900` | Seconds without activity before an idle normal user is disconnected. 0 disables it. |
| `server.chat-flood-time` | `2` | Minimum seconds between chat messages from one user. 0 disables the interval. |
| `server.create-game-flood-time` | `2` | Minimum seconds between game creations by one user. 0 disables the interval. |
| `server.max-user-name-length` | `45` | Maximum user name length in characters. |
| `server.max-client-name-length` | `100` | Maximum emulator/client name length in characters. |
| `server.max-chat-length` | `150` | Maximum chat message length in characters. |
| `server.max-game-name-length` | `100` | Maximum game (ROM) name length in characters. |
| `server.max-quit-message-length` | `75` | Maximum quit message length in characters. |

Several limits above apply only to normal access: `max-ping`,
`allowed-connection-types`, `max-games`, `allow-single-player`,
`allow-multiple-connections`, the name and client-name lengths, chat and
create-game flood control, and chat length. `ELEVATED` and `ADMIN` users bypass
them.

## Game

| Property | Default | Description |
|---|---|---|
| `game.buffer-size` | `1024` | Per-player input ring buffer size in bytes. A single game-data payload must fit in it. |
| `game.timeout-millis` | `1250` | How long input assembly waits for a lagging player, per read, before timing out. |
| `game.desynch-timeouts` | `4` | Consecutive timeouts tolerated before a player is marked desynchronized. |
| `game.default-auto-fire-sensitivity` | `0` | Default auto-fire detection sensitivity, 0 (off) to 5 (most sensitive). Room owners can change it while waiting. |

## Access control

The access file is not a Spring property. It is resolved by
`FileBasedAccessManager` from the working directory first, then from the
classpath. See [Access configuration file](access-config-file.md).

## Master list

| Property | Default | Description |
|---|---|---|
| `master-list.server-name` | `New Kaillux Server` | Name shown in the server list. |
| `master-list.server-location` | `Unknown` | Free-text location. |
| `master-list.server-website` | `https://github.com/kidoz/kaillux` | Website URL. |
| `master-list.server-connect-address` | empty | Address clients should use. Set the public address behind NAT. |
| `master-list.touch-kaillera` | `true` | Report to the Kaillera master list. |
| `master-list.touch-emulinker` | `true` | Report to the EmuLinker-compatible master. |
| `master-list.emulinker-master-url` | `http://master.emulinker.org/touch.php` | EmuLinker master endpoint. |

## Admin and security

| Property | Default | Description |
|---|---|---|
| `admin.username` | `admin` (or `ADMIN_USERNAME`) | Admin API and dashboard username. |
| `admin.password` | `${ADMIN_PASSWORD:}` | Admin password. When blank, a random password is generated and printed once to the console. |
| `admin.security.failOnInsecureCredentials` | `false` | Refuse startup when the username is the default or the password is a known-weak value. The `prod`/`production` profiles imply this. |

## Relay

| Property | Default | Description |
|---|---|---|
| `relay.enabled` | `false` | Enable relay mode. Requires a restart. |
| `relay.listen-port` | `27887` | Public UDP port for relayed handshakes. |
| `relay.backend-host` | `localhost` | Backend server hostname or address. Resolved at startup. |
| `relay.backend-port` | `27888` | Backend connect port. |
| `relay.max-connections` | `100` | Maximum concurrent relayed clients per relay controller. |
| `relay.buffer-size` | `2048` | Relay UDP buffer size in bytes, 512–65536. |
| `relay.idle-timeout-seconds` | `300` | Stop a data-port relay after this many seconds without traffic. 0 disables cleanup. |
| `relay.cleanup-interval-seconds` | `60` | How often to check for idle relays, 10–3600. |

## Management endpoints

| Property | Default | Description |
|---|---|---|
| `management.endpoints.web.exposure.include` | `prometheus,health,info` | Actuator endpoints exposed over HTTP. |
| `management.endpoint.prometheus.access` | `read-only` | Prometheus endpoint access mode. |
| `management.endpoint.health.probes.enabled` | `true` | Enable liveness and readiness groups. |
| `management.endpoints.web.base-path` | `/` | Serve actuator endpoints at the root. |
| `management.endpoints.web.path-mapping.prometheus` | `metrics` | Expose Prometheus at `/metrics`. |
| `management.endpoints.web.path-mapping.health` | `healthz` | Expose health at `/healthz`. |

## OpenAPI

| Property | Default | Description |
|---|---|---|
| `springdoc.api-docs.path` | `/api-docs` | OpenAPI JSON path. |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI path. |
| `springdoc.swagger-ui.operationsSorter` | `method` | Sort operations by HTTP method. |
| `springdoc.swagger-ui.tagsSorter` | `alpha` | Sort tags alphabetically. |
| `springdoc.swagger-ui.doc-expansion` | `list` | Collapse operations into a list. |

## Logging

Logging is configured in `src/main/resources/logback.xml`, not in
`application.properties`. The default logs to the console at DEBUG for
`su.kidoz` packages and INFO for Spring. A rolling file appender writing
`kaillux.log` is defined but disabled. See
[Monitor the server](../how-to/monitor-the-server.md).
