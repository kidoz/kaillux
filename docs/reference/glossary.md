# Glossary

**Access level** — The privilege assigned to a connecting IP address:
`NORMAL`, `ELEVATED`, or `ADMIN`. Defined in `access.cfg` or granted temporarily
with `/tempadmin`.

**Access rule** — A line in `access.cfg` that grants an access level, allows or
denies an address, or filters games and emulators. See
[Access configuration file](access-config-file.md).

**Auto-fire detection** — A heuristic that looks for repeated, evenly spaced
input patterns that indicate a turbo/auto-fire controller. The game owner sets
the sensitivity with `/detectautofire`.

**Bundle** — A single UDP payload containing one or more Kaillera messages. The
first byte is the message count. See [Kaillera protocol](protocol.md).

**Connect port** — The public UDP port that accepts the initial `HELLO`
handshake. Default: 27888.

**Connection type** — A client-declared quality class from 1 (LAN) to 6 (Bad).
It determines how many input actions are sent per UDP message and how much
latency the client tolerates. See
[Game data and connection types](game-data-and-connection-types.md).

**Data port** — A UDP port, dynamically assigned from the configured range,
that carries a client's protocol session after the handshake. Default range
starts at 27889.

**Desync** — A game state in which a player stops supplying input within the
timeout window. After the configured number of consecutive timeouts, the player
is marked desynchronized and the remaining players continue if at least two are
still synchronized.

**Emulator** — The client program that runs the game, such as MAME, Kawaks, or a
Project64 variant. In the protocol, the emulator name is part of login and game
creation. `access.cfg` can allow or deny emulator names.

**Game room** — A lobby created by a user, identified by a game ID, with a game
(ROM) name, an owner, and one or more players. Also called a game.

**HELLO / HELLOD00D** — The connect-port handshake. The client sends
`HELLO0.83`; the server replies with `HELLOD00D<port>` or `TOO` if it refuses
the connection.

**Kaillera** — The original network protocol and client ecosystem for
emulator multiplayer. Kaillux implements protocol version 0.86 and accepts
clients that identify as 0.83.

**Kaillux** — This server implementation, formerly derived from EmuLinker.

**Keepalive** — A periodic message from a client (message `0x09`) that keeps
its session alive. The server disconnects a user after
`server.keep-alive-timeout` seconds without one.

**Master list** — A public directory of Kaillera servers. Kaillux can report to
the Kaillera master list and to an EmuLinker-compatible master.

**Netsync** — The synchronization phase before gameplay. Each client downloads
or exchanges game state through the client protocol, then sends a ready signal
(`0x15`). When every player is ready, the game enters the playing state.

**Ping** — Round-trip time in milliseconds, estimated from the ACK exchange
during login. `server.max-ping` rejects high-latency clients at login (normal
users only) and also sets the handshake timeout and maintenance interval.

**Relay mode** — An optional mode in which Kaillux forwards UDP traffic to a
backend Kaillera server instead of hosting games itself. It is used to reach
servers from behind NAT or firewalls. See [Relay mode](../explanation/relay-mode.md).

**Server list** — The text format returned by the Kaillera master server's
`raw_server_list` endpoint. See
[Master server protocol](master-server-protocol.md).

**Synchronized (`synched`)** — A game state in which the server considers every
active player able to contribute input within the timeout window. A game must
be synchronized to exchange game data.

**Temporary rule** — A runtime ban, silence, or admin grant stored in memory,
not in `access.cfg`. It expires after the given number of minutes and can be
cleared with `/clear`.
