# Master server registration

A Kaillera client discovers servers through a master list. Kaillux does not
listen for discovery requests; it periodically pushes a status report to the
master. This is called "touching" the master.

## Two destinations

Kaillux can report to two masters independently:

| Master | Property | URL | What it receives |
|---|---|---|---|
| Kaillera | `master-list.touch-kaillera` | `http://www.kaillera.com/touch_server.php` | Server metadata plus the current waiting games and recently started games. |
| EmuLinker-compatible | `master-list.touch-emulinker` | `master-list.emulinker-master-url` | Server metadata, version, build, and host OS flag. |

The Kaillera URL is fixed in `KailleraMasterUpdateTask`. The EmuLinker URL is
configurable and defaults to `http://master.emulinker.org/touch.php`.

If both switches are false, `MasterListUpdaterImpl` does not start its thread.
If only the EmuLinker switch is on, the Kaillera statistics collector is not
created.

## What is published

`PublicServerInformation` reads four values from configuration:

- `master-list.server-name` — display name in the list;
- `master-list.server-location` — free-text location;
- `master-list.server-website` — URL shown to clients;
- `master-list.server-connect-address` — the address clients should use.

The connect address matters when the server is behind NAT or a proxy. If it is
empty, the master sees no explicit address and clients rely on the address they
reached the master through or on the server's observed address.

The report also includes live counts: connected users, maximum users, number of
games, and the connect port. The Kaillera report includes:

- `Kaillera-games` header — ROM names of games started since the last touch;
- `Kaillera-wgames` header — one `id|rom|owner|emulator|players|` record for
  each room in the waiting state.

No user names, addresses, or chat content are sent to the Kaillera master in the
metadata. The waiting-games header does include the room owner's display name.

## Timing

The updater runs after the rest of the server is started (lifecycle phase 40).
It sleeps 60 seconds between rounds, then touches the EmuLinker master and the
Kaillera master. A failure in one touch is logged and does not stop the thread
or the other master.

The started-games list is drained on each Kaillera touch. If no Kaillera task is
configured, the list is cleared each round so it cannot grow without bound.

## EmuLinker response

The EmuLinker master returns Java-properties text. Kaillux reads:

- `updateAvailable` — when `true`, logs a warning that a newer version exists;
- `latest` — the latest version string;
- `notes` — optional release notes.

The response is informational. Kaillux does not download or install updates.

## Why push, not pull

The original Kaillera ecosystem expects servers to announce themselves. A push
model works from behind NAT as long as the server can make outbound HTTP
requests, and it lets the master show live counts without polling every server.
The trade-off is that the master only knows what the server reports, and a
failed touch leaves the listing stale until the next successful one.

For setup, see
[Register with master servers](../how-to/register-with-master-servers.md). For
the wire formats, see
[Master server protocol](../reference/master-server-protocol.md).
