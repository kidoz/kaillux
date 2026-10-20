# Architecture

Kaillux is a Spring Boot application that speaks the Kaillera UDP protocol. It
has no database and no persistent game state: the server holds the current
lobby, users, and game rooms in memory and writes only logs and configuration
files.

## System context

```text
Emulator client                 Kaillux                         Emulator client
+-------------+   UDP 27888   +---------------------------+   UDP 27888   +-------------+
| Kaillera    |-------------->| ConnectController         |<--------------| Kaillera    |
| client      |               |  assigns a data port      |               | client      |
|             |   UDP 27889+  |                           |   UDP 27889+  |             |
|             |-------------->| V086Controller            |<--------------|             |
|             |               |  relays game input        |               |             |
+-------------+               +---------------------------+               +-------------+
                                       |        |        |
                                       |        |        +--> Admin UI / REST (TCP 8080)
                                       |        +-----------> Prometheus metrics (TCP 8080)
                                       +--------------------> Master list servers (HTTP)
```

The server is the rendezvous point. Clients never send game input to each
other directly; every client sends its input to the server, and the server
sends the combined frame back to each player.

## Why two ports

Kaillera separates connection setup from gameplay:

1. Every client knows the single connect port, 27888 by default.
2. `ConnectController` answers a `HELLO` handshake with a port assignment.
3. The client reconnects on that assigned port, which belongs to
   `V086Controller`.

This lets one public port act as a stable entry point while each session gets
its own UDP flow. It also means a firewall must allow both the connect port and
the data-port range. See [Network ports](../reference/network-ports.md).

## Runtime components

| Component | Responsibility |
|---|---|
| `ConnectController` | Listens on the connect port. Parses `HELLO`, `TOO`, `PING`, and `HELLOD00D` messages, applies admission limits and address rules, and allocates a data port. |
| `V086Controller` | Owns one `V086ClientHandler` per logged-in client and the UDP sockets for the data-port range. Parses `V086Bundle` packets. |
| `ActionRouter` | Maps inbound message IDs to command actions and outbound events to event renderers. |
| `KailleraServerImpl` | Domain root: users, games, announcements, access checks, and event dispatch. |
| `KailleraUserImpl` | Per-user state machine (connecting, idle, playing), ping, keepalive, and outbound event queue. |
| `KailleraGameImpl` | Per-game state machine (waiting, synchronizing, playing), player input queues, timeouts, and desync handling. |
| `FileBasedAccessManager` | Loads `access.cfg`, watches it for changes, resolves DNS patterns, and keeps temporary bans, silences, and admin grants. |
| `MasterListUpdaterImpl` | Periodically reports the server to the Kaillera and EmuLinker master lists. |
| `AdminRestController` | Serves the admin REST API and feeds the dashboard. |
| `GameMetricsCollector` | Publishes Micrometer counters, gauges, and timers to Prometheus. |
| `KailleraRelayController` | Optional relay mode: proxies UDP traffic to a backend Kaillera server. |

`EmuLinkerConfig` wires these components. Most controllers and background
services implement `SmartLifecycle`, so startup and shutdown are ordered:

| Phase | Component | Reason |
|---|---|---|
| 0 | `FileBasedAccessManager` | Access rules must exist before any connection is evaluated. |
| 10 | `KailleraServerImpl` | Core domain and maintenance thread. |
| 20 | `V086Controller` | Binds the data-port range before the connect port advertises it. |
| 30 | `ConnectController` | Starts accepting public handshakes last among the UDP listeners. |
| 40 | `MasterListUpdaterImpl` | Advertises the server only after it is fully running. |

## Request flow

An inbound chat message illustrates the path through the system:

```text
UDP datagram
    |
    v
V086ClientHandler          parse V086Bundle
    |
    v
ActionRouter.getAction(0x07)
    |
    v
ChatCommandAction          validate and call the domain model
    |
    v
KailleraServerImpl         apply policy, then fire an event
    |
    v
ActionRouter.getServerEventHandler(ChatEvent)
    |
    v
ChatEventRenderer          build protocol messages
    |
    v
V086ClientHandler.send     bundle and send UDP datagrams
```

Actions are Spring beans. `ActionRouter` collects the annotated beans and
indexes them by message ID, so adding a protocol message means adding an action
and an annotation, not editing a switch statement.

The same split applies to game data. `GameDataCommandAction` parses input and
calls `KailleraGameImpl.addData`. The game queues the input, waits until it has
one action from every player, and fires a `GameDataEvent` for the player whose
input triggered assembly. `GameDataEventRenderer` sends that player the
combined frame. Because every client sends its own input, every client receives
a combined response.

## Package layout

All production code uses the `su.kidoz` prefix.

| Package | Contents |
|---|---|
| `su.kidoz.config` | Spring configuration, typed property classes, security, OpenAPI, relay conditional wiring. |
| `su.kidoz.kaillera.controller.connectcontroller` | Connect-port protocol and handshake messages. |
| `su.kidoz.kaillera.controller.v086` | Main protocol controllers, client handlers, port allocation, actions, events, and message classes. |
| `su.kidoz.kaillera.model` | Domain interfaces and events. `model.impl` holds the state machines. |
| `su.kidoz.kaillera.access` | Access rules, patterns, parser, stores, and hot reload. |
| `su.kidoz.kaillera.admin` | Admin REST API, DTOs, and health probes. |
| `su.kidoz.kaillera.master` | Master-list reporting and the server-list statistics collector. |
| `su.kidoz.kaillera.relay` | Optional UDP relay controllers. |
| `su.kidoz.kaillera.metrics` | Micrometer game metrics. |
| `su.kidoz.kaillera.service` | Service layer used by the admin API. |
| `su.kidoz.net` | UDP server and relay primitives. |
| `su.kidoz.util` | Executor, buffers, caches, wildcard matching, and localization. |

The admin UI lives in `src/main/resources/static/admin/` and is a separate
React/Vite project. Its sources are excluded from the server jar; the build
compiles the bundle into `static/admin/` before packaging.

## What the architecture optimizes for

- **Low per-packet overhead.** Game input is small and frequent. The protocol
  bundles messages into one UDP datagram and the server forwards combined frames
  rather than running a separate thread or connection per message.
- **Cheap concurrency.** Every blocking operation runs on a Java 25 virtual
  thread, so the server can keep thousands of client sessions without a large
  platform-thread pool. See [Threading model](threading-model.md).
- **Operational simplicity.** There is no database to operate. Configuration is
  properties plus one text file; the admin API and metrics expose runtime state.
- **Compatibility.** The wire format follows Kaillera 0.86 and accepts clients
  that identify as 0.83 and later. The server deliberately parses and validates
  messages but keeps relay mode transparent.

Related pages: [Connection lifecycle](connection-lifecycle.md),
[Game synchronization](game-synchronization.md),
[Threading model](threading-model.md).
