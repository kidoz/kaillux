# Enable relay mode

Relay mode makes Kaillux proxy UDP traffic to a backend Kaillera server. Use it
when clients cannot reach the backend directly because of NAT or a firewall.
The backend keeps running; the relay only forwards traffic.

## 1. Choose ports

Decide on three port sets:

- the relay connect port (default 27887);
- the backend connect port (default 27888);
- the backend data-port range, for example 27889–27924.

The relay binds the backend's assigned data ports locally, so those port numbers
must be free on the relay host and reachable by clients. They must also not
overlap a locally hosted Kaillux data range. If the same process hosts games and
relays, give the local server a different `controllers.v086.port-range-start`
than the backend uses.

## 2. Configure the relay

```properties
relay.enabled=true
relay.listen-port=27887
relay.backend-host=192.168.1.10
relay.backend-port=27888
relay.max-connections=100
relay.buffer-size=2048
relay.idle-timeout-seconds=300
relay.cleanup-interval-seconds=60
```

Set the same values with environment variables if you prefer:

```bash
RELAY_ENABLED=true
RELAY_LISTEN_PORT=27887
RELAY_BACKEND_HOST=192.168.1.10
RELAY_BACKEND_PORT=27888
```

`backend-host` is resolved when the relay starts. Use a stable DNS name or a
fixed address.

## 3. Open the firewall

Open the relay connect port and the entire backend data-port range for UDP. For
example, with the defaults:

```bash
sudo firewall-cmd --permanent --add-port=27887/udp
sudo firewall-cmd --permanent --add-port=27889-27924/udp
sudo firewall-cmd --reload
```

The range must cover every port the backend can assign:
`backend max-users + backend extra-ports + 1`. See
[Network ports](../reference/network-ports.md).

## 4. Docker Compose

In `compose.yaml`, uncomment the relay port mapping and relay settings:

```yaml
ports:
  - "27887:27887/udp"
  # The published data range must cover the backend's assigned ports.
  - "27889-27924:27889-27924/udp"
environment:
  RELAY_ENABLED: "true"
  RELAY_BACKEND_HOST: "backend.example.com"
  RELAY_BACKEND_PORT: "27888"
```

`relay.listen-port` defaults to 27887 inside the container, so the mapping must
use the same number.

## 5. Start and verify

Restart Kaillux. The log should include:

```text
Relay mode enabled: listening on port 27887, forwarding to backend.example.com:27888
Relay cleanup task started: interval=60s, idleTimeout=300s
```

When a client connects, the relay logs the backend's port assignment and starts
a data-port relay:

```text
Backend assigned port 27889 for client 203.0.113.10:54321
Started V086 relay on port 27889 -> backend.example.com/203.0.113.10:27889
```

Check the admin API:

```bash
curl -s -u 'admin:password' http://127.0.0.1:8080/api/admin/relay
```

A working relay reports `"enabled": true`, `"running": true`, active
connections, bytes relayed, parse errors, and one entry per active data port.

## 6. Point clients at the relay

Configure clients with the relay's address and the relay connect port (27887 by
default), not the backend's 27888. The handshake still works because the relay
forwards it and returns the backend's assigned port.

## Disable relay mode

Set `relay.enabled=false` and restart. The relay controllers are not created.
The local game server continues to work as before.

For the design and limitations, see [Relay mode](../explanation/relay-mode.md).
