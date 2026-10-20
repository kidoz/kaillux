# Metrics

Kaillux exposes Prometheus metrics through Spring Boot Actuator and Micrometer
at:

```text
GET /metrics
```

The endpoint is public in the default security configuration and returns the
Prometheus text exposition format.

## Naming

Micrometer names use dots, for example `kaillera.games.started`. Prometheus
converts them:

- dots become underscores: `kaillera_games_started`;
- counters gain `_total`: `kaillera_games_started_total`;
- timers gain a unit suffix and publish `_count`, `_sum`, and `_max`:
  `kaillera_games_duration_seconds_count`.

One application metric needs special attention: the Micrometer counter
`kaillera.games.created` is published as `kaillera_games_total`, not
`kaillera_games_created_total`, because Prometheus treats `_created` as a
reserved suffix. The metric names below are the names in the Prometheus output.

## Application metrics

Defined by `GameMetricsCollector`:

| Metric | Type | Description |
|---|---|---|
| `kaillera_games_total` | Counter | Games created. |
| `kaillera_games_started_total` | Counter | Games that reached the playing state. |
| `kaillera_games_completed_total` | Counter | Games that ended. |
| `kaillera_games_active` | Gauge | Games currently existing. |
| `kaillera_games_playing` | Gauge | Games currently in the playing state. |
| `kaillera_games_duration_seconds` | Timer | Game play duration; published as `_count`, `_sum`, and `_max`. |
| `kaillera_games_players_synced_total` | Counter | "All players ready" synchronization events. |
| `kaillera_games_players_desynced_total` | Counter | Player desynchronization events (timeouts and dropped packets). |
| `kaillera_games_players_dropped_total` | Counter | Player drop events. |

The application metrics have no custom tags. Every instance reports a single
series per metric.

## Standard metrics

Spring Boot and Micrometer also publish runtime metrics, including:

| Area | Examples |
|---|---|
| JVM memory | `jvm_memory_used_bytes`, `jvm_memory_max_bytes` |
| JVM threads | `jvm_threads_live_threads`, `jvm_threads_daemon_threads` |
| JVM GC | `jvm_gc_pause_seconds_count`, `jvm_gc_pause_seconds_sum` |
| Process | `process_cpu_usage`, `process_uptime_seconds`, `process_start_time_seconds` |
| System | `system_cpu_count`, `system_load_average_1m` |
| HTTP server | `http_server_requests_seconds_count`, `http_server_requests_seconds_sum` |
| Executor | `executor_active_threads` (if instrumented) |

The exact set depends on the Spring Boot and Micrometer versions. Use
`curl -s http://127.0.0.1:8080/metrics | grep '^# HELP'` to list what a running
instance exposes.

## Scrape configuration

```yaml
scrape_configs:
  - job_name: kaillux
    metrics_path: /metrics
    static_configs:
      - targets: ["kaillux.internal:8080"]
```

The endpoint has no authentication. Restrict it at the network, firewall, or
reverse-proxy layer when the server is internet-facing.

## Interpreting the metrics

- **Created vs started vs completed.** A game is created in `WAITING`, started
  when all players ready up, and completed when it closes. A large gap between
  `kaillera_games_total` and `kaillera_games_started_total` can mean players
  join and leave without starting.
- **Active vs playing.** `active` includes waiting rooms; `playing` includes
  only rooms exchanging input.
- **Synced vs desynced.** `players_synced_total` counts all-ready transitions.
  A rising `players_desynced_total` relative to games indicates network or
  timeout problems.
- **Dropped players.** `players_dropped_total` increments when a player leaves
  a running game, including intentional drops.
- **Duration.** `kaillera_games_duration_seconds_sum / _count` gives the mean
  completed game duration.

## Health versus metrics

Health probes (`/healthz`, `/healthz/liveness`, `/healthz/readiness`) report
only up/down state. They do not include counts or saturation. Use metrics and
the admin API for capacity and performance.

See [Monitor the server](../how-to/monitor-the-server.md) for an alerting
baseline and [Admin REST API](admin-rest-api.md) for point-in-time detail.
