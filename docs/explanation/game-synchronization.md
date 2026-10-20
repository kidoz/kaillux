# Game synchronization

Kaillux does not emulate games. Each client runs the emulator locally and only
exchanges controller input through the server. The server's job is to make sure
every player's emulator receives the same input for the same frame, in the same
order.

## Input is framed by connection type

The owner's connection type is fixed when the room is created and becomes the
room's `actionsPerMessage` value:

| Connection type | Value | Actions per message | Nominal frames per second |
|---|---:|---:|---:|
| LAN | 1 | 1 | 60 |
| Excellent | 2 | 2 | 30 |
| Good | 3 | 3 | 20 |
| Average | 4 | 4 | 15 |
| Low | 5 | 5 | 12 |
| Bad | 6 | 6 | 10 |

A slower connection sends more input actions per UDP datagram, which reduces
packet rate at the cost of latency. Because every player in a room must use the
same connection type, the server can use one frame size for all of them.

Each client's payload implies how many bytes one action occupies. For MAME32k
0.64 the value is 2 bytes per action. The server never hard-codes that number;
it derives it from each accepted payload:

```text
bytesPerAction = payload.length / actionsPerMessage
```

A payload is accepted only if it is non-empty, a whole multiple of
`actionsPerMessage`, and no larger than `game.buffer-size`. A malformed payload
is rejected rather than truncated, because truncation would shift every later
frame for every player.

## Per-player ring buffers

When a game starts, the server allocates one `PlayerActionQueue` per player.

```text
             player 1 queue             player 2 queue
          +------------------+       +------------------+
 bytes -> |  ring buffer     |       |  ring buffer     | <- bytes
          |  write position  |       |  write position  |
          +------------------+       +------------------+
               ^      ^                  ^      ^
               |      |                  |      |
          reader 1  reader 2         reader 1  reader 2
          (one read position per player per queue)
```

Every player has a write position in their own queue. Every player also has a
read position in every queue, because each client assembles a full frame by
reading one action from every player. Absolute 64-bit positions (rather than
modulo indices) distinguish a full buffer from an empty one and let a writer
refuse to overwrite data a reader has not consumed.

Writers wait up to `game.timeout-millis` for space. Readers wait up to the same
timeout for data. `game.buffer-size` bounds the queue. If a single payload can
never fit, the write is rejected immediately instead of waiting.

A player marked desynchronized stops constraining capacity: its frozen read
position would otherwise eventually block every writer.

## Frame assembly

When player P sends a `GameData` message, `KailleraGameImpl.addData`:

1. Validates the payload and enqueues it in P's queue.
2. Allocates a response of `numPlayers * payload.length` bytes.
3. For each of the `actionsPerMessage` actions, and for each player in player
   order, reads `bytesPerAction` bytes from that player's queue.
4. Queues the combined frame to P as a `GameDataEvent`, which the renderer sends
   back as a `GameData` notification.

Because every client sends its own input, every client triggers its own
combined response. The combined payload is ordered as:

```text
[action 1: player 1][action 1: player 2] ... [action 2: player 1] ...
```

This is why the client must know the player order and the bytes-per-action
value. The server does not add emulator-specific structure.

## Timeouts and desynchronization

Reading from a queue can time out. The server counts consecutive timeouts per
lagged player, per assembler. This detail matters: one client's wait on player A
must not advance the desynchronization counter for player B.

When the timeout count for a player reaches `game.desynch-timeouts`:

- the player's queue is marked unsynchronized;
- a `PlayerDesynchEvent` is emitted so the player is told they lagged;
- the player stops constraining other writers.

If fewer than two players remain synchronized, the whole room leaves the
synchronized state: every queue is marked unsynchronized and no further game
data is delivered until the room is restarted.

A dropped packet (a cache miss on the client data cache) marks that player
desynchronized immediately. A player who drops from the game is also removed
from the synchronized set, but the room can continue while at least two players
remain synchronized.

If the game is closed or replaced, an in-flight `addData` checks that it is
still assembling against the current queue array. A response built against the
old state is discarded instead of being delivered into a restarted game.

## Auto-fire detection

A game owner can enable auto-fire detection with `/detectautofire 1-5`, or
disable it with `/detectautofire 0`. The server scans each player's input for
repeated patterns whose delay is below a threshold and whose repetition count is
above a threshold:

| Sensitivity | Max delay | Min repetitions |
|---:|---:|---:|
| 0 | detection off | — |
| 1 (least sensitive) | 2 | 13 |
| 2 | 3 | 11 |
| 3 | 4 | 9 |
| 4 | 5 | 7 |
| 5 (most sensitive) | 6 | 5 |

The game-wide default is `game.default-auto-fire-sensitivity`. Detection uses a
separate scanner per player on virtual threads and announces a warning in the
game when a pattern is detected. Sensitivity can only be changed while the room
is waiting, not during play.

## What this design does not do

- It does not roll back or resimulate game state. That is the client's job.
- It does not guarantee delivery; UDP packets can be lost. It guarantees
  consistent ordering of the input that does arrive.
- It does not hide latency. Waiting for a slow player delays every player's
  frame assembly, which is why timeout and desync settings exist.
- It does not enforce a particular bytes-per-action layout. The layout comes
  from the emulator.

Related reference:
[Game data and connection types](../reference/game-data-and-connection-types.md),
[Configuration properties](../reference/configuration.md).
