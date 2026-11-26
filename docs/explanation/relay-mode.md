# Relay mode

Relay mode lets Kaillux sit between clients and another Kaillera server. It is
for the common case where players cannot reach a server directly because of NAT,
a firewall, or an address that is not publicly routable.

## The problem

Kaillera uses UDP and tells clients which data port to use. A client behind NAT
can often send a packet to a public server, but a server behind NAT cannot
accept inbound traffic on an arbitrary port. A relay with a public address can
accept both the handshake and the data traffic, then forward it.

## Traffic flow

```text
Client                    Kaillux relay                     Backend server
   |                       listen :27887                       :27888
   |-- HELLO0.83 ---------->|                                  |
   |                        |-- HELLO0.83 -------------------->|
   |                        |<------------- HELLOD00D27889 ----|
   |<----- HELLOD00D27889 -|                                  |
   |                        |  spawn V086 relay on :27889      |
   |-- protocol to :27889 ->|-- protocol to backend:27889 ----->|
   |<-----------------------|<---------------------------------|
```

The relay is deliberately transparent. It parses messages to validate them and
to update counters, then forwards the original bytes unchanged. It does not
rewrite message numbers, buffer content, or player data.

## Components

`KailleraRelayController` implements the connect-port side:

- listens on `relay.listen-port`, bound to `relay.bind-address`;
- forwards each `HELLO` to `relay.backend-host:relay.backend-port`;
- reads `HELLOD00D` from the backend and starts a `V086RelayController` on the
  same numeric port locally;
- forwards the backend's reply to the client unchanged;
- forwards `TOO` when the backend is full.

`V086RelayController` implements the data-port side:

- binds the port assigned by the backend;
- accepts client datagrams and forwards them to the backend's corresponding
  port;
- returns backend datagrams to the client that owns the flow;
- tracks the last client and server message numbers for diagnostics.

`UDPRelay` provides the shared NIO receive loop, per-client flow tracking,
idle-client cleanup, byte counters, parse-error counters, and lifecycle
management.

## Port requirements

Relay mode adds a second port range to the deployment:

| Port | Direction | Requirement |
|---|---|---|
| `relay.listen-port` (default 27887) | Public UDP | Clients connect here. |
| Backend-assigned data ports | Public UDP | The relay must be able to bind the same port numbers locally, and clients must be able to reach them. |

The relay accepts only assignments inside `relay.data-port-range-start` to
`relay.data-port-range-end` (default `27889`–`65535`) and keeps at most
`relay.max-relay-ports` data relays. An assignment outside the range, or one
that collides with `relay.listen-port` or the backend connect port, is refused.
Set the range to the backend's actual data-port window rather than leaving it
wide open.

The data-port range published by the relay must cover every port the backend
can assign. If the backend uses the default Kaillux sizing, that is
`backend max-users + backend max-connecting + backend extra-ports + 1` ports
starting at the backend's `port-range-start`. A relay cannot forward a data port
it cannot bind.

The backend must be reachable from the relay on both its connect port and its
data-port range.

## Connection and idle handling

- `relay.max-connections` caps concurrent flows per relay controller.
- One source address may hold a fifth of that table (at least four flows, at
  most fifty), so a single host cannot fill it from many source ports.
- Nothing is allocated for a flow until its first datagram has been examined:
  a datagram that the relay would reject never gets a socket or a thread.
- A flow is tracked by source address and port. It expires after fifteen
  seconds without traffic if the client sent only that one datagram, as a
  spoofed source does, and after five minutes otherwise.
- `relay.idle-timeout-seconds` (default 300) stops a V086 relay that has seen
  no traffic; `relay.cleanup-interval-seconds` (default 60) controls how often
  the check runs.
- Setting `relay.idle-timeout-seconds=0` disables that cleanup.

The backend sees every relayed client as coming from the relay's address. The
backend's per-address limits therefore apply to all relayed clients together:
its `server.max-connecting-per-address`, its `server.hello-rate-per-second`, a
temporary ban of one relayed user, and any `user` rule that matches the relay's
address.

When a V086 relay is stopped, clients using it must reconnect through the
handshake.

## What relay mode does not change

- It does not disable the local game server. The same process can host games on
  27888 and relay to a backend on 27887. Keep the two port sets disjoint.
- It does not provide encryption, authentication, or access control of its own.
  The backend's rules still decide who may play. Address-based rules on the
  backend see the relay's address, not the client's, unless the deployment
  accounts for that.
- It does not support TCP; the Kaillera protocol is UDP only.
- It does not make an unreachable backend reachable. The relay needs a working
  route to the backend on every data port it forwards.

## Observability

`GET /api/admin/relay` returns configuration, running state, active and total
connections, bytes relayed, parse errors, and the active V086 relay ports with
their message counters. The endpoint reports `enabled: false` when relay mode is
off. See [Admin REST API](../reference/admin-rest-api.md).

For setup steps, see [Enable relay mode](../how-to/enable-relay-mode.md).
