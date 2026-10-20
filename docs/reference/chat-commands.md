# Chat commands

Chat commands are typed into the Kaillera lobby or game chat. A message whose
first character is `/` is treated as a command instead of chat.

- Arguments are separated by spaces.
- `<userID>` and `<gameID>` are numeric IDs shown by `/finduser`, `/findgame`,
  or the admin dashboard.
- `<minutes>` is a whole number.
- `<pattern>` is a case-insensitive wildcard where `*` matches any run of
  characters and `|` separates alternatives.
- Commands are matched by prefix, so `/help` also matches `/helpme`; use the
  documented forms.

## Admin commands

Require `ADMIN` access. Rejected if the sender is not logged in or if the
data-port peer address differs from the handshake address.

| Command | Syntax | Effect |
|---|---|---|
| Help | `/help` | List available admin commands. |
| Version | `/version` | Print version, Java, OS, CPU, and memory details. |
| Find user | `/finduser <pattern>` | List matching logged-in users with ID, ping, address, and name. |
| Find game | `/findgame <pattern>` | List matching games with ID, owner, and ROM name. |
| Kick | `/kick <userID>` | Disconnect a user. |
| Ban | `/ban <userID> <minutes>` | Disconnect and add a temporary address ban. |
| Silence | `/silence <userID> <minutes>` | Block chat from the user's address for the duration. |
| Temporary admin | `/tempadmin <userID> <minutes>` | Grant the user's address temporary `ADMIN` access. |
| Clear | `/clear <address>` | Remove temporary bans, silences, and admin grants for an address. |
| Close game | `/closegame <gameID>` | Close a game room. |
| Announce | `/announce <message>` | Send an announcement to the lobby. |
| Announce all | `/announceall <message>` | Send an announcement to the lobby and all games. |
| Announce game | `/announcegame <gameID> <message>` | Send an announcement to one room. |

### Permission rules

- A user cannot kick, ban, or silence themselves.
- A user cannot kick, ban, or silence another admin.
- `/closegame` refuses to close a room owned by an admin.
- `/clear` needs an address or resolvable hostname, not a wildcard. It removes
  all temporary rule types for that address and reports whether anything was
  removed.
- Temporary rules are in memory only and are lost on restart.

### Message handling

`/announce` and `/announceall` strip a leading `:` from the message, which
prevents clients from injecting a server-formatted message. `/announcegame`
joins the remaining arguments with spaces.

## Game-owner commands

Require ownership of the current room. Typed in game chat.

| Command | Syntax | Effect |
|---|---|---|
| Help | `/help` | List room-owner commands. |
| Auto-fire detection | `/detectautofire <0-5>` | Set sensitivity; 0 disables detection. |

Sensitivity can be changed only while the room is in the waiting state. If the
argument is missing, not numeric, or outside 0–5, the server replies with the
command help instead of changing the setting.

| Sensitivity | Max delay | Min repetitions |
|---:|---:|---:|
| 0 | off | — |
| 1 | 2 | 13 |
| 2 | 3 | 11 |
| 3 | 4 | 9 |
| 4 | 5 | 7 |
| 5 | 6 | 5 |

See [Game synchronization](../explanation/game-synchronization.md#auto-fire-detection).

## Granting admin access

Permanent admin access comes from `access.cfg`:

```text
user,ADMIN,<address patterns>
```

Temporary admin comes from `/tempadmin`. See
[Access configuration file](access-config-file.md) and
[Manage users with chat commands](../how-to/manage-users-with-chat-commands.md).
