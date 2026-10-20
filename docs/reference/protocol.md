# Kaillera protocol

Kaillux implements the Kaillera connection protocol and the v0.86 game
protocol. This page describes the wire format implemented by the protocol
classes in `su.kidoz.kaillera.controller`.

The original third-party protocol analysis is kept in
[the archive](archive/kailleraprotocol.txt). Where the archive and the code
disagree, this page follows the code; see
[Implementation notes](#implementation-notes).

## Transport

- UDP only.
- Connect port: `controllers.connect.port`, default 27888.
- Data ports: `controllers.v086.port-range-start` and the following
  `server.max-users + controllers.v086.extra-ports` ports.
- All multi-byte integers are little-endian.
- Strings are null-terminated byte sequences. An empty string is a single
  `0x00`.
- String encoding is UTF-8 by default. Override it with
  `-Demulinker.charset=<name>`.

## Data types

| Notation | Meaning |
|---|---|
| `1B`, `2B`, `4B` | Unsigned integer of 1, 2, or 4 bytes, little-endian. |
| `NB` | Null-terminated string. |
| `0xNN` | Fixed byte value. |

## Connect-port protocol

These messages are plain null-terminated strings, not v086 messages.

| Direction | Message | Meaning |
|---|---|---|
| Client → Server | `HELLO<version>\0` | Request a session. Example: `HELLO0.83`. |
| Server → Client | `HELLOD00D<port>\0` | Accept and assign a data port. Example: `HELLOD00D27889`. |
| Server → Client | `TOO\0` | Refuse the connection. |
| Client → Server | `PING\0` | Connectivity check. |
| Server → Client | `PONG\0` | Reply to `PING`. |

The version string in `HELLO` must match a registered client type. Kaillux maps
`controllers.v086.client-types` (default `0.83`) to the v0.86 controller.

## Bundles

A v086 UDP payload is a bundle:

```text
+---------------+-----------+-----------+-----+-----------+
| Message count | Message 1 | Message 2 | ... | Message N |
|    (1 byte)   |           |           |     |           |
+---------------+-----------+-----------+-----+-----------+
```

Each message has a 5-byte header followed by its body:

```text
+----------------+----------------+--------+-----------------+
| Message number | Message length | Msg ID |  Message body   |
|    (2 bytes)   |    (2 bytes)   |(1 byte)|    (variable)   |
+----------------+----------------+--------+-----------------+
```

- Message number is a per-direction sequence number.
- Message length includes the message ID byte and the body, but not the 4-byte
  number/length header.
- Minimum body size is 1 byte, so the minimum message length is 2.
- A bundle holds at most 32 messages.
- Receivers use the last processed message number to skip duplicates and
  out-of-order repeats.

Example single-message bundle for User Quit:

```text
01 | 01 00 | 04 00 | 01 | 00 00 FF
^    ^       ^       ^    ^
|    |       |       |    +-- empty string + 2B 0xFFFF
|    |       |       +------- type 0x01
|    |       +--------------- length 4 (type + 3 body bytes)
|    +----------------------- message number 1
+---------------------------- one message in the bundle
```

## Message catalog

### 0x01 User Quit

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Unknown | `2B` = `0xFFFF` |
| Message | `NB` |

**Server notification**

| Field | Type |
|---|---|
| User name | `NB` |
| User ID | `2B` |
| Message | `NB` |

### 0x02 User Joined

Server notification only.

| Field | Type |
|---|---|
| User name | `NB` |
| User ID | `2B` |
| Ping | `4B` |
| Connection type | `1B` (1–6) |

### 0x03 User Information (login)

Sent by the client on its data port.

| Field | Type |
|---|---|
| User name | `NB` |
| Emulator/client name | `NB` |
| Connection type | `1B` (1–6) |

### 0x04 Server Status

Sent at login.

| Field | Type |
|---|---|
| Validation byte | `1B` = `0x00` |
| Number of users | `4B` |
| Number of games | `4B` |
| User records | variable |
| Game records | variable |

User record:

| Field | Type |
|---|---|
| User name | `NB` |
| Ping | `4B` |
| Player status | `1B` (0 = playing, 1 = idle) |
| User ID | `2B` |
| Connection type | `1B` (1–6) |

Game record:

| Field | Type |
|---|---|
| Game (ROM) name | `NB` |
| Game ID | `4B` |
| Emulator/client name | `NB` |
| Owner name | `NB` |
| Players | `NB` (for example `2/2`) |
| Game status | `1B` (0 = waiting, 1 = playing, 2 = synchronizing) |

### 0x05 Server to Client ACK

| Field | Type |
|---|---|
| Validation byte | `1B` = `0x00` |
| Value 1 | `4B` = `0` |
| Value 2 | `4B` = `1` |
| Value 3 | `4B` = `2` |
| Value 4 | `4B` = `3` |

### 0x06 Client to Server ACK

Same layout as `0x05`.

### 0x07 Global Chat

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Message | `NB` |

**Server notification**

| Field | Type |
|---|---|
| User name | `NB` |
| Message | `NB` |

### 0x08 Game Chat

Same layout as `0x07`, scoped to the sender's game room.

### 0x09 Keep Alive

| Direction | Field | Type |
|---|---|---|
| Client request | Empty string | `NB` |

### 0x0A Create Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Game name | `NB` |
| Empty string | `NB` |
| Unknown | `4B` = `0xFFFFFFFF` |

**Server notification**

| Field | Type |
|---|---|
| Owner name | `NB` |
| Game name | `NB` |
| Emulator/client name | `NB` |
| Game ID | `4B` |

### 0x0B Quit Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Unknown | `2B` = `0xFFFF` |

**Server notification**

| Field | Type |
|---|---|
| User name | `NB` |
| User ID | `2B` |

### 0x0C Join Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Game ID | `4B` |
| Empty string | `NB` |
| Unknown | `4B` = `0` |
| Unknown | `2B` = `0xFFFF` |
| Connection type | `1B` (1–6) |

**Server notification**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Game ID | `4B` |
| User name | `NB` |
| Ping | `4B` |
| User ID | `2B` |
| Connection type | `1B` (1–6) |

### 0x0D Player Information

Server notification listing the other players in the room.

| Field | Type |
|---|---|
| Empty string | `NB` |
| Number of players | `4B` (not including the recipient) |
| Player records | variable |

Each player record:

| Field | Type |
|---|---|
| User name | `NB` |
| Ping | `4B` |
| User ID | `2B` |
| Connection type | `1B` (1–6) |

The count may be zero; the first player in a room receives an empty list.

### 0x0E Update Game Status

| Field | Type |
|---|---|
| Empty string | `NB` |
| Game ID | `4B` |
| Game status | `1B` (0 = waiting, 1 = playing, 2 = synchronizing) |
| Number of players | `1B` |
| Maximum players | `1B` |

### 0x0F Kick User from Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| User ID | `2B` |

### 0x10 Close Game

**Server notification**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Game ID | `4B` |

### 0x11 Start Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Unknown | `2B` = `0xFFFF` |
| Unknown | `1B` = `0xFF` |
| Unknown | `1B` = `0xFF` |

**Server notification**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Frame delay / value | `2B` |
| Your player number | `1B` |
| Total players | `1B` |

The original protocol analysis describes the 2-byte field as a frame delay used
by the client to decide how many frames to buffer.

### 0x12 Game Data

**Client request and server notification**

| Field | Type |
|---|---|
| Validation byte | `1B` = `0x00` |
| Data length | `2B` |
| Game data | `Data length` bytes |

The payload contains one or more input actions per player. The server returns a
combined payload containing every player's actions. See
[Game data and connection types](game-data-and-connection-types.md).

### 0x13 Game Cache

**Client request and server notification**

| Field | Type |
|---|---|
| Validation byte | `1B` = `0x00` |
| Cache position | `1B` (0–255) |

The cache maps a position to a previously sent game-data payload. The sender
uses a cached position instead of resending identical input.

### 0x14 Drop Game

**Client request**

| Field | Type |
|---|---|
| Empty string | `NB` |
| Unknown | `1B` = `0x00` |

**Server notification**

| Field | Type |
|---|---|
| User name | `NB` |
| Player number | `1B` |

### 0x15 Ready to Play

| Direction | Field | Type |
|---|---|---|
| Client request | Empty string | `NB` |
| Server notification | Empty string | `NB` |

The request means this client is ready. The notification means every player is
ready and the game can start.

### 0x16 Connection Rejected

| Field | Type |
|---|---|
| User name | `NB` |
| User ID | `2B` |
| Message | `NB` |

### 0x17 Server Information Message

| Field | Type |
|---|---|
| Source | `NB` (normally `server`) |
| Message | `NB` |

Used for login messages, announcements, and command responses.

## Connection state sequence

```text
Client                          Server
  |-- HELLO0.83 ------------------>|
  |<-------------- HELLOD00D27889 -|
  |  (connect to UDP 27889)        |
  |-- 0x03 User Information ------>|
  |<-- 0x05 Server ACK ------------|
  |-- 0x06 Client ACK ------------>|
  |         (repeat ACK exchange)  |
  |<-- 0x04 Server Status ---------|
  |<-- 0x02 User Joined -----------|
  |<-- 0x17 Information -----------|
  |                                |
  |-- 0x0A Create Game ----------->|
  |<-- 0x0A Create Game -----------|
  |<-- 0x0E Game Status -----------|
  |-- 0x11 Start Game ------------>|
  |<-- 0x11 Start Game ------------|
  |-- 0x15 Ready ----------------->|
  |<-- 0x15 All Ready -------------|
  |-- 0x12 Game Data ------------->|
  |<-- 0x12 Game Data -------------|
```

## Implementation notes

The archived analysis and the current codec differ in a few places. This page
documents the codec:

- **`0x04` user records.** The codec writes `ping`, `player status`, `user ID`,
  then `connection type`. The archived analysis lists `connection type` before
  `user ID`.
- **32-bit IDs.** The codec writes 4-byte game IDs as a 2-byte ID plus a 2-byte
  value. For IDs below 65536 and a zero high word, this is the same byte
  sequence as a little-endian 4-byte ID.
- **`0x0C` join notification.** The codec treats the first 4 bytes as the game
  ID, followed directly by the user name. The archived analysis describes a
  separate server-side pointer field.
- **Validation bytes.** Most messages require a leading `0x00`. `0x12` and
  `0x13` skip validation on parse for speed but write `0x00`.
- **Message count.** The codec caps a bundle at 32 messages; the archived
  analysis recommends keeping three slots free for gameplay traffic.

When interoperating with a non-Kaillux server or client, test these fields
explicitly. See [Connection lifecycle](../explanation/connection-lifecycle.md)
for server behavior and [Master server protocol](master-server-protocol.md) for
the server-list HTTP format.
