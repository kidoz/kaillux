# Archived protocol captures

These files are historical raw captures and working notes from the original
EmuLinker/Kaillera development period. They are preserved for protocol research
and provenance. They are not maintained documentation, and they may describe
behavior that differs from the current implementation.

| File | Contents | Why it is kept |
|---|---|---|
| [kailleraprotocol.txt](kailleraprotocol.txt) | Third-party analysis of the Kaillera connect and v0.86 protocols, message catalog, and server-list format. | Primary source for the protocol; the curated [protocol reference](../protocol.md) is based on it and notes where the codec differs. |
| [master_analysis.txt](master_analysis.txt) | Capture of Kaillera master-server `touch_server.php` requests and raw server-list data. | Documents the historical master-server wire format. |
| [raw_server_list2.txt](raw_server_list2.txt) | Raw `raw_server_list2` capture with server and game records from the mid-2000s. | Shows the historical list encoding and real-world data. It contains legacy, non-UTF-8 text and historical public server addresses. |
| [scratch.txt](scratch.txt) | Development notes, a historical fix list, packet traces, and early timing tables. | Shows the reasoning behind connection-type and input-sizing choices. Some tables are out of date; use the [game data reference](../game-data-and-connection-types.md). |

## Screenshots

The original screenshots are in [../../assets/legacy/](../../assets/legacy/):

- `screenshot1.gif` — an EmuLinker server console from October 2005.
- `screenshot2.gif` — a Kaillera client lobby from the same era.

They show the original 2005 deployment and are not a guide to the current UI.

## Using this material

- Treat the files as untrusted historical data. Do not copy encoding-specific
  bytes into configuration or code.
- The curated [protocol reference](../protocol.md) is authoritative for this
  implementation.
- If you find a discrepancy, document it in the curated page rather than editing
  the archived capture.
- These files are tracked in git. Keep them byte-for-byte unless there is a
  deliberate archival reason to change them.
