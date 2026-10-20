# Connection lifecycle

A Kaillera session passes through several distinct states. This page explains
what the server does at each transition and which configuration values affect
it.

## 1. Handshake on the connect port

The client sends a null-terminated string to UDP port 27888:

```text
HELLO0.83\0
```

`ConnectController` parses it and checks, in order:

1. **Protocol support.** The version string must map to a controller. Kaillux
   registers the v0.86 controller for the client types listed in
   `controllers.v086.client-types` (default `0.83`).
2. **Address permission.** `FileBasedAccessManager.isAddressAllowed` applies
   temporary bans first, then the `ipaddress` rules in `access.cfg`.
3. **Admission limits.** `ConnectionAdmission.admit` enforces
   `server.max-users`, `server.max-connecting`,
   `server.max-connecting-per-address`, and `server.hello-rate-per-second`.

If any check fails, the server replies `TOO\0` and the connection ends. If the
checks pass, `V086Controller.newConnection` creates a `KailleraUserImpl` in
`STATUS_CONNECTING`, starts its receive thread, and allocates a data port. The
server replies:

```text
HELLOD00D27889\0
```

The number is the assigned data port. The allocation is pulled from a fixed
range of `server.max-users + controllers.v086.extra-ports + 1` ports starting at
`controllers.v086.port-range-start`. The extra port exists so a slot is
available while a disconnected user is being cleaned up.

The connect port also answers `PING` with `PONG`. This is independent of the
login ping measurement.

## 2. Login on the data port

The client now sends protocol messages to the assigned data port. The first is
`UserInformation` (`0x03`), containing the user name, emulator name, and
connection type.

`LoginCommandAction` stores those values, records the client's address, and
starts the speed test. The speed test is a short burst of server ACKs
(`0x05`) and client ACKs (`0x06`); the client's ping is derived from the
round-trip times. After more than three client ACKs, the server records the
best round-trip time as the user's ping and calls `KailleraServerImpl.login`.

Login validation then runs under the connection lock:

- the user must not already be logged in;
- the access level must be at least `NORMAL`;
- ping must not exceed `server.max-ping` (normal users only, and ping may not be
  negative);
- the connection type must be in `server.allowed-connection-types` (normal
  users only);
- the name must be non-empty, no longer than `server.max-user-name-length`, and
  free of control, direction-override, and zero-width characters;
- the emulator name must not exceed `server.max-client-name-length`;
- the user's status must still be `CONNECTING`;
- the address used on the data port must match the address that performed the
  handshake;
- the emulator name must be allowed by `access.cfg` (normal users only).

A second login from the same address with the same name replaces the earlier
session. When `server.allow-multiple-connections=false`, a second login from the
same address under a different name is rejected.

On success the user moves to `STATUS_IDLE`, a `ConnectedEvent` is queued, and a
fixed login notification sequence runs: server status, user-joined events,
server information messages, and any admin announcement from `access.cfg`. All
of this is delivered through the user's event queue, not by blocking the login
thread.

## 3. Lobby behavior

In the lobby a user can chat, create a game, join a game, or keep the session
alive. The server enforces:

- chat length (`server.max-chat-length`) and flood interval
  (`server.chat-flood-time`);
- game name length (`server.max-game-name-length`);
- create-game flood interval (`server.create-game-flood-time`);
- game allowance rules in `access.cfg`;
- the maximum number of games (`server.max-games`, where 0 means unlimited).

A keepalive (`0x09`) updates the user's last-keepalive timestamp. The
maintenance task disconnects a logged-in user after
`server.keep-alive-timeout` seconds without one, and an idle normal user after
`server.idle-timeout` seconds without activity. A user who has not completed
login is removed after `max-ping * 15` milliseconds.

## 4. Game room

Creating a game makes the user the owner and puts the room in `WAITING`.
Joining a room adds the player and notifies the other players. A room can be
started only by its owner.

Before the game starts, the server requires:

- at least two players, unless `server.allow-single-player=true` or the owner
  has elevated/admin access;
- every player using the same connection type as the owner;
- every player using the same emulator name as the room.

Starting moves the room to `SYNCHRONIZING` and creates one input queue per
player. Each client performs its own netplay synchronization and then sends a
ready signal (`0x15`). When every queue is ready, the room moves to `PLAYING`
and the server sends `AllReady` (`0x15`) to the players.

## 5. Playing, dropping, and quitting

During play, each client sends its input (`0x12` or cached input `0x13`). The
server combines input frames and returns them. A player who misses input
timeouts or drops a packet is marked desynchronized. If fewer than two players
remain synchronized, the room itself leaves the synchronized state.

A player who drops returns to the idle lobby state but remains listed in the
room. A player who quits leaves the room; if the owner quits, the room closes.
Closing a room resets every player's game reference and status, stops auto-fire
scanning, and clears the room.

See [Game synchronization](game-synchronization.md) for the input path and
[Kaillera protocol](../reference/protocol.md) for the message formats.
