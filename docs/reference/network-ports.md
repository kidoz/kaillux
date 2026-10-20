# Network ports

Kaillux uses fixed ports for discovery and administration and a contiguous
range for per-client game sessions.

## Port table

| Port | Protocol | Default | Purpose | Exposure |
|---|---|---|---|---|
| 8080 | TCP | `server.port` (Spring Boot) | Admin dashboard, admin API, health, metrics, OpenAPI | Private or behind a TLS proxy |
| 27887 | UDP | `relay.listen-port` | Relay mode handshake | Public only when relay mode is enabled |
| 27888 | UDP | `controllers.connect.port` | Kaillera handshake (`HELLO`, `HELLOD00D`, `TOO`, `PING`) | Public |
| 27889–27924 | UDP | `controllers.v086.port-range-start` + count | Per-client Kaillera sessions | Public |
| 5173 | TCP | Vite default | Admin UI dev server (`just admin-dev`) | Developer machine only |

The HTTP port is Spring Boot's standard `server.port`. The default is 8080 when
not set.

## Data-port range size

The server allocates:

```text
port count = server.max-users + controllers.v086.extra-ports + 1
first port = controllers.v086.port-range-start
last port  = first port + port count - 1
```

With the defaults (`25 + 10 + 1 = 36`), the range is 27889–27924 inclusive.

Each connected client consumes one port. The `extra-ports` margin lets a new
client connect while a disconnected client's port is being released. If you
increase `server.max-users`, increase the published range as well:

| `server.max-users` | Extra ports | Ports needed | Range from 27889 |
|---:|---:|---:|---|
| 25 | 10 | 36 | 27889–27924 |
| 50 | 10 | 61 | 27889–27949 |
| 100 | 10 | 111 | 27889–27999 |

The range must end at or below 65535. If it does not, reduce the user limit or
move `port-range-start` lower.

Setting `server.max-users=0` disables the admission cap, but the data-port range
still bounds concurrent sessions. If you use 0, size the range for the real
concurrency you expect rather than leaving the default 36 ports.

## Firewall examples

Defaults:

```bash
# firewalld
sudo firewall-cmd --permanent --add-port=27888/udp
sudo firewall-cmd --permanent --add-port=27889-27924/udp
sudo firewall-cmd --reload

# ufw
sudo ufw allow 27888/udp
sudo ufw allow 27889:27924/udp
```

Relay mode adds the relay port:

```bash
sudo ufw allow 27887/udp
```

The HTTP port is TCP; the Kaillera ports are UDP. Do not publish the HTTP port
as UDP or the Kaillera ports as TCP.

## Docker mappings

The bundled `docker-run` recipe maps:

```bash
-p 8080:8080
-p 27888:27888/udp
-p 27889-27924:27889-27924/udp
```

`compose.yaml` binds the HTTP port to `127.0.0.1:8080`. If you change the user
limit, update the published UDP range to match the formula above.

Relay mode additionally publishes `27887:27887/udp` and must publish the
backend's data-port range.

## Bind addresses

`controllers.bind-addresses` controls which local addresses receive UDP
traffic. The default `0.0.0.0,::` binds IPv4 and IPv6. In a container, set
`CONTROLLERS_BIND_ADDRESSES=0.0.0.0` to avoid dual-stack bind conflicts; the
Docker image already does this.

Binding only one family means clients using the other family cannot connect,
even if the firewall allows the port.

## Common port problems

| Symptom | Likely cause |
|---|---|
| Handshake succeeds, session does not | Data-port range blocked or not published. |
| Some clients connect, later ones do not | Data range too small for the user limit. |
| Works on IPv4, not IPv6 (or vice versa) | Bind address list or firewall missing the family. |
| Relay clients fail on a port the backend assigned | Relay host cannot bind that port, or the range is not published. |
| `Address already in use` at startup | Connect port or a relay port is occupied. |
| A local server and relay conflict | Their data-port ranges overlap. Give them disjoint ranges. |
