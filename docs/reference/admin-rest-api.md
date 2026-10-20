# Admin REST API

The admin API is served by `AdminRestController` at two equivalent base paths:

```text
/api/admin
/api/v1/admin
```

Responses are JSON. There is no pagination; list endpoints return every matching
item.

## Authentication

Every `/api/admin/**` and `/api/v1/admin/**` request requires HTTP Basic
authentication. Use `admin.username` and `admin.password` (or
`ADMIN_USERNAME`/`ADMIN_PASSWORD`).

```bash
curl -u 'admin:password' http://127.0.0.1:8080/api/admin/server-info
```

A missing or invalid credential returns HTTP 401 without a
`WWW-Authenticate` header.

The OpenAPI document is at `/api-docs` and Swagger UI at `/swagger-ui.html`.
Both are public by default.

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/server-info` | Server status and counters. |
| GET | `/users` | All connected users. |
| GET | `/games` | All active game rooms. |
| GET | `/controllers` | Protocol controllers. |
| GET | `/event-metrics` | Event-queue metrics for every user. |
| GET | `/users/{userId}/events` | Event-queue metrics for one user. |
| POST | `/users/{userId}/kick` | Disconnect one user. |
| GET | `/relay` | Relay status and statistics. |

## GET `/server-info`

```json
{
  "serverName": "Kaillux Kaillera Server",
  "version": "1.4.0",
  "build": 0,
  "running": true,
  "connectPort": 27888,
  "uptimeMinutes": 42,
  "userCount": 3,
  "maxUsers": 25,
  "gameCount": 1,
  "maxGames": 0,
  "stats": {
    "requestCount": 120,
    "connectCount": 9,
    "protocolErrors": 0,
    "deniedFull": 1,
    "deniedOther": 2
  },
  "threadPool": {
    "active": 5,
    "poolSize": 5,
    "maxPoolSize": 2147483647,
    "taskCount": 5
  }
}
```

| Field | Meaning |
|---|---|
| `serverName` | Product name from release info. |
| `version`, `build` | Release version and build number. |
| `running` | Always `true` when the API responds. |
| `connectPort` | UDP connect port. |
| `uptimeMinutes` | Minutes since the connect controller started. |
| `userCount`, `maxUsers` | Current and maximum logged-in users. |
| `gameCount`, `maxGames` | Current games and the cap (0 = unlimited). |
| `stats.requestCount` | Connect-port requests received. |
| `stats.connectCount` | Successful handshakes. |
| `stats.protocolErrors` | Unsupported protocol requests. |
| `stats.deniedFull` | Denials caused by admission limits. |
| `stats.deniedOther` | Address or other denials. |
| `threadPool.active` | Tasks currently running. |
| `threadPool.poolSize` | Tasks submitted so far (virtual threads have no fixed pool). |
| `threadPool.maxPoolSize` | Always `2147483647`. |
| `threadPool.taskCount` | Same counter as `poolSize`. |

## GET `/users`

```json
[
  {
    "id": 1,
    "name": "Player1",
    "status": "Playing",
    "connectionType": "Good",
    "ping": 42,
    "address": "203.0.113.10:27889",
    "connectTime": 1763980000000
  }
]
```

`status` is `Playing`, `Idle`, or `Connecting`. `connectionType` is `Lan`,
`Excellent`, `Good`, `Average`, `Low`, or `Bad`. `connectTime` is epoch
milliseconds.

## GET `/games`

```json
[
  {
    "id": 1,
    "rom": "Street Fighter II",
    "owner": "Player1",
    "status": "Playing",
    "players": 2
  }
]
```

`status` is `Waiting`, `Playing`, or `Synchronizing`.

## GET `/controllers`

```json
[
  {
    "version": "v086",
    "bufferSize": 2048,
    "numClients": 3,
    "clientTypes": ["0.83"]
  }
]
```

## GET `/event-metrics`

```json
[
  {
    "userId": 1,
    "userName": "Player1",
    "queueSize": 12,
    "droppedEvents": 0,
    "queueCapacity": 2000,
    "queueUtilization": 0.6
  }
]
```

`queueUtilization` is a percentage of the fixed capacity of 2000 events. A
nonzero `droppedEvents` or sustained high utilization means the client's event
loop is not keeping up.

## GET `/users/{userId}/events`

Returns one object in the same shape as an element of `/event-metrics`.

| Status | Body |
|---|---|
| 200 | Event metrics. |
| 404 | User not found. |
| 401 | Unauthorized. |

## POST `/users/{userId}/kick`

Request body:

```json
{"reason": "Excessive lag"}
```

`reason` is optional. If missing or blank, the server uses
"Kicked by administrator". The reason appears in the user's disconnect message.

```bash
curl -u 'admin:password' -X POST \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Excessive lag"}' \
  http://127.0.0.1:8080/api/admin/users/1/kick
```

Success response:

```json
{"success": true, "message": "User Player1 has been kicked"}
```

| Status | Body |
|---|---|
| 200 | `ActionResultDTO`. |
| 404 | User not found. |
| 401 | Unauthorized. |
| 500 | `{"success": false, "message": "Failed to kick user: ..."}`. |

Kicking disconnects the session; it does not ban the address. The admin API has
no ban endpoint.

## GET `/relay`

When relay mode is disabled:

```json
{
  "enabled": false,
  "running": false,
  "listenPort": 0,
  "backendHost": "",
  "backendPort": 0,
  "activeConnections": 0,
  "totalConnections": 0,
  "bytesRelayed": 0,
  "parseErrors": 0,
  "uptimeMinutes": 0,
  "v086Relays": []
}
```

When enabled, the same fields describe the relay:

| Field | Meaning |
|---|---|
| `enabled` | `relay.enabled` configuration value. |
| `running` | Relay controller lifecycle state. |
| `listenPort` | Relay connect port. |
| `backendHost`, `backendPort` | Target backend. |
| `activeConnections` | Clients currently tracked by the connect relay. |
| `totalConnections` | Clients handled since startup. |
| `bytesRelayed` | Total bytes forwarded. |
| `parseErrors` | Messages that failed protocol parsing. |
| `uptimeMinutes` | Relay uptime in minutes. |
| `v086Relays` | Active data-port relays. |

Each `v086Relays` entry:

```json
{
  "port": 27889,
  "activeConnections": 2,
  "lastClientMsgNum": 1234,
  "lastServerMsgNum": 5678
}
```

## Errors

| Status | Meaning |
|---|---|
| 400 | Malformed JSON or missing required path/body value. |
| 401 | Missing or invalid Basic credentials. |
| 404 | The user ID does not exist. |
| 500 | The action failed; see the `message` field. |

The API does not implement rate limiting, roles, or audit logging. See
[Security model](../explanation/security-model.md).
