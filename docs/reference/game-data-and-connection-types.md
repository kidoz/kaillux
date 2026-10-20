# Game data and connection types

During play, clients exchange input through `GameData` (`0x12`) and
`GameCache` (`0x13`) messages. The connection type controls how much input is
packed into each message.

## Connection types

| Type | Name | Actions per message | Nominal frames per second |
|---:|---|---:|---:|
| 1 | LAN | 1 | 60 |
| 2 | Excellent | 2 | 30 |
| 3 | Good | 3 | 20 |
| 4 | Average | 4 | 15 |
| 5 | Low | 5 | 12 |
| 6 | Bad | 6 | 10 |

The client declares its type at login (`0x03`). The owner's type is captured
when the room is created and becomes the room's `actionsPerMessage`. Every
player in a room must use the same type; the server rejects a start otherwise.

`server.allowed-connection-types` can reject types at login for normal users.

## Payload sizing

The server derives the action size from the payload:

```text
bytesPerAction = payload.length / actionsPerMessage
```

The payload must be a whole multiple of `actionsPerMessage`. The server rejects
a payload that is empty, misaligned, or larger than `game.buffer-size`.

The combined response for one action frame contains every player's action:

```text
bytesPerFrameIn  = bytesPerAction
bytesPerFrameOut = bytesPerAction * numPlayers
```

For a full message:

```text
inboundPayload  = actionsPerMessage * bytesPerAction
outboundPayload = actionsPerMessage * bytesPerAction * numPlayers
```

`bytesPerAction` is emulator-specific. The archived MAME32k 0.64 capture uses
2 bytes per action, but other emulators may use a different size. The server
does not assume a value; it infers it from the first valid payload of each
message.

## Frame ordering

The combined response is ordered by action, then by player:

```text
action 1: [player 1 bytes][player 2 bytes]...[player N bytes]
action 2: [player 1 bytes][player 2 bytes]...[player N bytes]
...
```

The player order is the room's join order, numbered from 1. Each client must use
the same order, which is why the server tells each player its number in the
`Start Game` notification and in `Player Information`.

## Buffer sizing

`game.buffer-size` is the size of each per-player ring buffer, in bytes. It must
be large enough for at least one full message payload:

```text
game.buffer-size >= actionsPerMessage * bytesPerAction
```

The default is 1024 bytes, which is far above the typical emulator payload. If
you lower it, verify against the largest game-data message your clients send.

`game.timeout-millis` and `game.desynch-timeouts` control how long the server
waits for a lagging player before marking them desynchronized. See
[Game synchronization](../explanation/game-synchronization.md).

## Auto-fire detection

The game owner enables detection with `/detectautofire 1-5` and disables it
with `/detectautofire 0`. The default is
`game.default-auto-fire-sensitivity` (0 = off).

| Sensitivity | Max delay | Min repetitions |
|---:|---:|---:|
| 0 | detection off | — |
| 1 (least sensitive) | 2 | 13 |
| 2 | 3 | 11 |
| 3 | 4 | 9 |
| 4 | 5 | 7 |
| 5 (most sensitive) | 6 | 5 |

The scanner looks for a repeated input pattern whose delay is at or below the
max-delay threshold and whose repetition count reaches the minimum. Detection
runs per player, on virtual threads, and announces a warning in the room.

The archived `scratch.txt` contains an older sensitivity table (max delay 2/4/6/
8/10 and repetitions 25/20/15/10/5). That table is historical; the values above
match the current `AutoFireScanner`.

## Historical note

The archived capture was recorded in 2005 from MAME32k 0.64 and Kaillera 0.9
clients. The protocol structure is still relevant, but the exact frames-per-
second and bytes-per-action values are properties of the client and emulator,
not of Kaillux. See
[Archived protocol captures](archive/README.md).
