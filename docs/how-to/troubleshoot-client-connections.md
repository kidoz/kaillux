# Troubleshoot client connections

Use this guide when a Kaillera client cannot reach, log in to, or play on a
Kaillux server. Start with the server logs and the admin dashboard; most
failures produce an explicit reason there.

## The client cannot reach the server

**Check the address and port.** The client must use the connect port
(`controllers.connect.port`, default 27888/udp), not a data port. Relay users
connect to `relay.listen-port` (default 27887).

**Check UDP reachability.** UDP has no connection handshake, so test with a real
protocol message. From the client host:

```bash
printf 'HELLO0.83\0' | nc -u -w 2 <server> 27888
```

A reachable server answers with `HELLOD00D<port>` or `TOO`. No output usually
means the packet was dropped or the reply was blocked.

Firewalls must allow both the connect port and the data-port range. A client
that completes the handshake but cannot continue usually has the data range
blocked.

**Check the bind addresses.** If the server binds only `0.0.0.0`, it does not
accept IPv6. If it binds only `::`, IPv4 clients may fail. The default is both.
Look for this startup line:

```text
Ready to accept connections on port 27888 (addresses: [...])
```

## The server replies `TOO`

`TOO` is the generic refusal. The log distinguishes the reason:

```text
Connection from 203.0.113.10 denied: logged-in user limit reached
Connection from 203.0.113.10 denied: connecting-user limit reached
Connection from 203.0.113.10 denied: per-address connecting limit reached
Connection from 203.0.113.10 denied: hello rate limit reached
AccessManager denied connection from 203.0.113.10
Client requested an unhandled protocol ...
```

| Cause | Fix |
|---|---|
| `server.max-users` reached | Raise the limit or wait. Elevated/admin addresses bypass it. |
| `server.max-connecting` reached | Raise the cap or investigate stuck handshakes. |
| Per-address cap reached | Raise `max-connecting-per-address` or check for a client retry loop. |
| HELLO rate exceeded | Raise `hello-rate-per-second` or check for a flood. |
| Address denied | Review `access.cfg` and temporary bans. |
| Unhandled protocol | The client's version is not in `controllers.v086.client-types`. |

If the data-port range is exhausted, `V086Controller.newConnection` fails and
the server sends `TOO`. This can happen when `extra-ports` is too small for the
user count and disconnect rate.

## The client connects but login is rejected

The server log and the client message identify the failed validation:

| Log | Cause | Fix |
|---|---|---|
| `login denied: Ping X > Y` | Latency above `server.max-ping` | Raise the limit or improve the route. Elevated/admin bypass it. |
| `login denied: Connection ... Not Allowed` | Connection type outside `server.allowed-connection-types` | Add the type or ask the client to choose another. |
| `login denied: UserName Length > ...` | Name too long | Shorten the name. |
| `login denied: Illegal characters in UserName` | Control, zero-width, or direction-override characters | Use printable characters. |
| `login denied: Emulator restricted` | `emulator,DENY` rule | Allow the emulator or use a supported one. |
| `login denied: Emulator Name Length > ...` | Emulator string too long | Raise `max-client-name-length`. |
| `login denied: Connect address does not match login address` | Handshake and data traffic came from different addresses | Usually a NAT or multi-homed routing problem; check the network path. |
| `login denied: Address already logged in as ...` | Second user from the same address with a different name and `allow-multiple-connections=false` | Enable multiple connections or use one client. |

A reconnect with the same name and address replaces the old session; that is
normal.

## The game will not start

| Client message / log | Cause |
|---|---|
| `Only owner may start` | Only the room owner can start. |
| `needs at least 2 players` | `server.allow-single-player=false`, and only one normal user is in the room. |
| `All players must use the same connection type` | A player joined with a different connection type. |
| `All players must use the same emulator` | A player joined with a different emulator/client string. |
| `StartGameErrorSynchronizing` / `...StatusIsPlaying` | The room is already starting or playing. |

All players must use the same emulator and connection type. Ask the odd player
out to rejoin with matching settings.

## Gameplay lags or desynchronizes

Look for:

```text
Timeout #1
player desynched: Lagged!
game desynched: less than 2 players synched!
```

Causes and adjustments:

- `game.timeout-millis` is too low for the players' latency; raise it.
- `game.desynch-timeouts` is too low; raise it to tolerate more consecutive
  waits.
- A player is on an unsuitable connection type; ask them to reconnect with a
  slower setting (more actions per packet).
- Packet loss between client and server; check the network and the relay path.
- `game.buffer-size` is too small for the emulator's payload; raise it.
- One client is not sending input; their queue stops advancing and everyone
  waits.

Raising timeouts reduces desyncs but increases input latency for every player.

## The admin dashboard rejects the password

- Confirm `ADMIN_PASSWORD` was set in the environment of the **server process**.
- If no password was set, look for the generated password on the console where
  the process started. It is printed once and is not in the log file.
- The username defaults to `admin`; override it with `ADMIN_USERNAME`.
- HTTP 401 is expected for wrong credentials. The API does not send a browser
  login challenge.
- Restarting the server with a different password invalidates the old one.

## Health endpoints fail

```bash
curl -si http://127.0.0.1:8080/healthz
```

- Connection refused: the HTTP server did not start, or the port is different.
- 503: Spring health reports a down component; check the application log.
- Works locally but not remotely: the HTTP listener or firewall is restricted.

## Collect diagnostics

```bash
# Listening UDP sockets
ss -lunp | grep -E '2788|279'

# Recent server logs
journalctl -u kaillux --since "10 minutes ago"   # systemd
docker compose logs --tail 200 kaillux           # Docker

# Live state
curl -s -u 'admin:password' http://127.0.0.1:8080/api/admin/server-info
curl -s -u 'admin:password' http://127.0.0.1:8080/api/admin/users
```

For protocol-level debugging, raise logging for
`su.kidoz.kaillera.controller` to DEBUG. This is verbose; do not leave it on in
production.
