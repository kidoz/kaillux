# Master server protocol

Kaillux reports itself to master servers over HTTP. It does not serve a master
list itself. This page documents the request formats and the server-list text
format.

## Kaillera master touch

`KailleraMasterUpdateTask` sends a GET request to:

```text
http://www.kaillera.com/touch_server.php
```

Query parameters:

| Parameter | Value |
|---|---|
| `servername` | `master-list.server-name` |
| `port` | The connect port. |
| `nbusers` | Current logged-in users. |
| `maxconn` | `server.max-users`. |
| `version` | `0.86`. |
| `nbgames` | Current game rooms. |
| `location` | `master-list.server-location`. |
| `ip` | `master-list.server-connect-address`. |
| `url` | `master-list.server-website`. |

Headers:

| Header | Value |
|---|---|
| `Kaillera-games` | ROM names of games started since the previous touch, separated by `|`. |
| `Kaillera-wgames` | Waiting-game records, each ending with `|`. |

A waiting-game record:

```text
<gameID>|<romName>|<ownerName>|<emulatorName>|<numPlayers>|
```

Example request:

```http
GET /touch_server.php?servername=My+Server&port=27888&nbusers=3&maxconn=25&version=0.86&nbgames=1&location=Berlin%2C+DE&ip=kaillux.example.com&url=https%3A%2F%2Fexample.com HTTP/1.1
Host: www.kaillera.com
Kaillera-games: Street Fighter II|
Kaillera-wgames: 1|Street Fighter II|Player1|MAME32k 0.64|2|
```

The response body is not interpreted.

## EmuLinker master touch

`EmuLinkerMasterUpdateTask` sends a GET request to
`master-list.emulinker-master-url` (default
`http://master.emulinker.org/touch.php`) with:

| Parameter | Value |
|---|---|
| `serverName` | `master-list.server-name` |
| `connectAddress` | `master-list.server-connect-address` |
| `location` | `master-list.server-location` |
| `website` | `master-list.server-website` |
| `port` | The connect port. |
| `connectCount` | Successful handshakes since startup. |
| `numUsers` | Current logged-in users. |
| `maxUsers` | `server.max-users`. |
| `numGames` | Current game rooms. |
| `maxGames` | `server.max-games` (0 = unlimited). |
| `version` | Release version, for example `1.4.0`. |
| `build` | Build number. |
| `isWindows` | `true` when the host OS is Windows, otherwise `false`. |

The response is Java-properties text:

```properties
updateAvailable=true
latest=1.4.0
notes=Security fixes
```

Kaillux reads `updateAvailable`, `latest`, and `notes`. When `updateAvailable`
is `true`, it logs a warning. It does not download anything.

## Raw server-list format

The Kaillera master publishes server lists in a plain-text format. Each entry is
two lines:

```text
<server name>
<address>:<port>;<users>/<maxUsers>;<gameCount>;<version>;<location>
```

Example:

```text
Unknown serv0r
61.15.145.199:27888;3/10;1;0.86;Unknown location
```

The file `raw_server_list2.php` is a different capture format that also includes
game records. The archived
[raw_server_list2.txt](archive/raw_server_list2.txt) is a historical example of
that capture and is not used by Kaillux.

## Timing and failure behavior

- The updater sleeps 60 seconds between rounds.
- Both masters are touched in the same round, if enabled.
- Each touch is independent; an exception in one is logged and the other still
  runs.
- The started-games list is drained after a Kaillera touch so it is not sent
  twice.

See [Master server registration](../explanation/master-server-registration.md)
for the design and [Register with master servers](../how-to/register-with-master-servers.md)
for setup.
