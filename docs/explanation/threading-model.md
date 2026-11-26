# Threading model

Kaillux uses Java 25 virtual threads for nearly all concurrency. The design goal
is to keep the code straightforward—blocking reads and queue waits are allowed—
while supporting many simultaneous clients with modest memory use.

## What runs on which thread

| Work | Threading |
|---|---|
| Connect-port receive loop | One receive loop per bound address (`ChannelHandler`), run on the virtual-thread executor. `controllers.bind-addresses` defaults to IPv4 and IPv6, so there are two loops. |
| Client data-port receive loop | One `V086ClientHandler` per connected client, each a `UDPServer` that owns its `DatagramChannel` and receive loop. |
| Per-user event dispatch | One `KailleraUserImpl` runnable per user, executing on the virtual-thread executor. It drains that user's event queue and sends protocol messages. |
| Access-file maintenance | One virtual thread that sleeps for 60 seconds, purges expired temporary rules, refreshes DNS, and checks `access.cfg` for changes. |
| Server maintenance | One virtual thread that checks keepalive, idle, ban, and emulator state, and removes users whose handshake timed out. It sleeps for `max(1000, max-ping * 3)` milliseconds. |
| Master-list updates | One virtual thread that sleeps for 60 seconds between touches. |
| Auto-fire scanners | One virtual thread per player being scanned, created only when detection is enabled. |
| Relay cleanup | One scheduled daemon thread (when relay mode is enabled). |
| UDP relay receive loops | One selector thread per relay controller and one client handler per relayed flow. |
| Admin REST and health | Spring MVC request threads (platform threads by default). |

`EmuLinkerExecutor` wraps `Executors.newVirtualThreadPerTaskExecutor()`. Its
`getActiveCount()` and `getPoolSize()` methods exist for diagnostics and the
admin API; they are not a bounded pool. `getMaximumPoolSize()` returns
`Integer.MAX_VALUE`.

## Why virtual threads

A Kaillera client is mostly idle between packets. With platform threads, each
client would tie up stack memory and an OS thread. Virtual threads make the
per-client receive loop and event loop cheap enough to keep thousands of
sessions open.

Blocking operations are expected:

- `DatagramChannel.receive` blocks; shutdown closes the channel, which makes
  the blocked receive return. The socket has no read timeout because it has no
  effect on a blocking `DatagramChannel`.
- `PlayerActionQueue` readers and writers wait on monitors with timeouts.
- The maintenance tasks sleep for long intervals.

None of these need a special asynchronous API. Virtual threads park the carrier
thread instead of blocking the OS thread.

## Locks and ordering

The domain model uses explicit locks rather than relying on coarse global
synchronization:

- `KailleraServerImpl.connectionLock` serializes connection, login, and quit
  transitions.
- `KailleraServerImpl.gameLock` serializes game creation, joining, and closing.
- `KailleraGameImpl` uses a `ReentrantReadWriteLock` for game state and player
  lists.
- `PlayerActionQueue` synchronizes each queue independently.

The lock order is user monitor, then `connectionLock`, then game locks. The
user's `login()` and `quit()` hold its monitor and enter the server; admin and
maintenance callers of `server.quit()` take the user monitor first for the same
reason. Forced disconnects for reconnects happen after releasing the connection
lock.

Game input assembly runs on a serial worker owned by the game, not on the
sender's UDP receive thread. It reads the per-player queues without the game
write lock, and a lagging player can therefore never stall another client's
keepalive, chat, or input processing. A round whose membership changes is
aborted: the queue snapshot is discarded and the room returns to the waiting
state.

## Event queues

Each user owns a bounded event queue. Domain components fire events; the user's
event loop renders and sends them. This decouples packet processing from
outbound I/O and keeps a slow or blocked client from stalling another client's
processing.

When the queue is full, events are dropped and counted; critical events wait
briefly before giving up. After 50 consecutive drops the session is closed, so
a client that cannot keep up reconnects and receives fresh state rather than
staying permanently stale. `GET /api/admin/event-metrics` reports queue size,
dropped events, and utilization per user.

## Shutdown

Most components implement `SmartLifecycle`, so Spring starts and stops them in
phase order. Running state is latched before a worker is submitted and cleared
by `stop()`, so a stop that races with startup is not lost. UDP servers close
their channels, which wakes a blocked receive. Virtual threads are interrupted
or allowed to finish. The access manager and maintenance tasks observe stop
flags and exit their loops.
