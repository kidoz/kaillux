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

- `DatagramChannel.receive` blocks; the socket timeout is one second so the
  loop can observe shutdown.
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

The lock order matters. Login performs user notifications after releasing the
connection lock where possible, and forced disconnects for reconnects happen
after releasing it, to avoid inverting the lock order between a user's monitor
and the connection lock.

Game input assembly deliberately runs outside the game write lock. If it held
the lock while waiting for every player's queue, the other players' receive
threads could not enqueue their input, and the wait could never finish.

## Event queues

Each user owns a bounded event queue. Domain components fire events; the user's
event loop renders and sends them. This decouples packet processing from
outbound I/O and keeps a slow or blocked client from stalling another client's
processing.

When the queue is full, events are dropped and counted. `GET
/api/admin/event-metrics` reports queue size, dropped events, and utilization
per user. A sustained high drop count for one user is a sign that its client is
not consuming traffic fast enough.

## Shutdown

Most components implement `SmartLifecycle`, so Spring stops them in phase
order. UDP servers set a stop flag and close their channels; the receive loops
then exit within the one-second socket timeout. Virtual threads are interrupted
or allowed to finish. The access manager and maintenance tasks observe stop
flags and exit their loops.
