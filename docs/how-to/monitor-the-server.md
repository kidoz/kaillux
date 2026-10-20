# Monitor the server

Kaillux exposes health probes, Prometheus metrics, an admin API, and logs.
This guide shows how to use each and what to watch.

## Health probes

| Endpoint | Purpose | Healthy response |
|---|---|---|
| `/healthz` | Overall health | `{"groups":["liveness","readiness"],"status":"UP"}` |
| `/healthz/liveness` | Process is alive | `{"status":"UP"}` |
| `/healthz/readiness` | Ready to accept traffic | `{"status":"UP"}` |

All three return HTTP 200 when healthy and HTTP 503 when not. They require no
authentication, which makes them suitable for Kubernetes probes, load balancers,
and container health checks.

```bash
curl -si http://127.0.0.1:8080/healthz
```

## Prometheus metrics

Scrape `http://127.0.0.1:8080/metrics`. The endpoint is public, so restrict it
at the network or proxy layer if the server is publicly reachable.

Minimal Prometheus configuration:

```yaml
scrape_configs:
  - job_name: kaillux
    static_configs:
      - targets: ["127.0.0.1:8080"]
```

Kaillux adds these application metrics:

| Metric | Type | Meaning |
|---|---|---|
| `kaillera_games_total` | Counter | Games created. |
| `kaillera_games_started_total` | Counter | Games that reached the playing state. |
| `kaillera_games_completed_total` | Counter | Games that ended. |
| `kaillera_games_active` | Gauge | Games currently existing. |
| `kaillera_games_playing` | Gauge | Games currently in the playing state. |
| `kaillera_games_duration_seconds` | Timer | Play duration from start to completion. |
| `kaillera_games_players_synced_total` | Counter | "All players ready" events. |
| `kaillera_games_players_desynced_total` | Counter | Player desynchronization events. |
| `kaillera_games_players_dropped_total` | Counter | Player drop events. |

Spring Boot and Micrometer also publish JVM, process, HTTP server, and system
metrics. The exact set depends on the runtime. See
[Metrics](../reference/metrics.md) for the naming rules and the full list of
application metrics.

Useful checks:

- `up{job="kaillux"} == 0` — scrape failed.
- `kaillera_games_players_desynced_total` increasing quickly — network or
  timeout problems.
- `kaillera_games_players_dropped_total` increasing — players leaving or
  disconnecting.
- `jvm_threads_live_threads` or process CPU climbing without users — a stuck or
  overloaded instance.

## Admin API

The admin API gives point-in-time detail that metrics do not:

```bash
AUTH='admin:password'
curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/server-info
curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/event-metrics
```

`server-info` includes request counts, connect counts, protocol errors, denials,
uptime, and executor counters. `event-metrics` shows each user's event queue
size, dropped events, capacity, and utilization. A high utilization or nonzero
dropped count for one user indicates that the client is not consuming outbound
traffic fast enough.

If relay mode is enabled, `GET /api/admin/relay` reports active connections,
bytes relayed, parse errors, uptime, and per-port counters.

## Logs

By default, logs go to the console with a pattern like:

```text
10-21-26 02:09:23.753  INFO s.k.k.c.c.ConnectController: Ready to accept connections on port 27888
```

The bundled `logback.xml` defines a rolling file appender (`kaillux.log`, 10 MB
per file, five days, 100 MB total) but leaves it disabled. To enable it,
uncomment the `ASYNC_FILE` appender reference in the root logger.

When running under systemd, use journald:

```bash
journalctl -u kaillux -f
journalctl -u kaillux --since "1 hour ago"
```

Key messages to watch:

- `Listening on UDP ports: ... Make sure these ports are open in your firewall!`
- `Connection from ... denied: ...` — admission limits.
- `AccessManager denied connection from ...` — address rule.
- `login denied: ...` — ping, connection type, name, or emulator rule.
- `Failed to touch ... master list` — outbound HTTP problem.
- `player desynched` / `game desynched` — input timing problems.

Raise logging for a package only when needed; game data logging at debug level
is very verbose. The default configuration already logs `su.kidoz` at DEBUG and
silences the high-volume game data actions.

## A baseline alert set

- `/healthz` returns non-200 for two consecutive checks.
- The container or process restarts unexpectedly.
- Connected users stay at `server.max-users` while `deniedFull` rises in
  `server-info`.
- `kaillera_games_players_desynced_total` grows faster than games completed.
- `event-metrics` reports sustained dropped events for any user.
- Disk usage grows because of logs (if file logging is enabled).
