# Manage users with chat commands

Admins can moderate a running server from the Kaillera in-game or lobby chat.
Commands start with `/` and are executed instead of being broadcast as chat.

## Become an admin

An address gets admin access in one of two ways:

1. Add a rule to `access.cfg`:

   ```text
   user,ADMIN,your.public.ip.address
   ```

   Save the file. The rule applies on the next access check, without a restart.

2. An existing admin grants temporary admin:

   ```text
   /tempadmin <userID> <minutes>
   ```

Temporary admin expires after the given number of minutes and is not persisted
across restarts. It applies to the source address, not the user name.

## Find IDs

Most moderation commands take a user or game ID. Find them with:

```text
/finduser <wildcard>
/findgame <wildcard>
```

Examples:

```text
/finduser Alice*
/findgame Street Fighter*
```

The reply lists IDs plus diagnostic details. User and game IDs are also visible
in the **Users** and **Games** pages of the admin dashboard.

## Commands

| Command | Effect |
|---|---|
| `/help` | List available admin commands. |
| `/version` | Print server version, Java/OS details, CPU count, and memory figures. |
| `/finduser <pattern>` | List logged-in users whose name matches the wildcard. |
| `/findgame <pattern>` | List games whose ROM name matches the wildcard. |
| `/kick <userID>` | Disconnect a user. |
| `/ban <userID> <minutes>` | Disconnect and temporarily ban the user's address. |
| `/silence <userID> <minutes>` | Prevent the user from chatting for the given time. |
| `/tempadmin <userID> <minutes>` | Grant the user's address temporary admin access. |
| `/clear <address>` | Remove temporary bans, silences, and admin grants for an address. |
| `/closegame <gameID>` | Close a game room. |
| `/announce <message>` | Announce to the lobby. |
| `/announceall <message>` | Announce to the lobby and every game room. |
| `/announcegame <gameID> <message>` | Announce to one game room. |

Examples:

```text
/kick 12
/ban 12 30
/silence 12 10
/tempadmin 9 60
/clear 203.0.113.45
/closegame 4
/announce Server restart in 10 minutes
/announcegame 4 Match starts now
```

## Restrictions

- You cannot kick, ban, or silence yourself.
- You cannot kick, ban, or silence another admin.
- `/closegame` refuses to close a room owned by an admin.
- Admin commands are rejected unless the sending client is logged in and its
  data-port address matches the address that performed the handshake.
- `/clear` expects an IP address literal (or a resolvable hostname), not a
  wildcard.

Temporary rules are stored in memory and lost on restart. Use `access.cfg` for
permanent rules; see
[Configure access control](configure-access-control.md).

## Game-owner commands

Room owners have a separate command set in game chat:

| Command | Effect |
|---|---|
| `/help` | List room-owner commands. |
| `/detectautofire <0-5>` | Set auto-fire detection sensitivity (0 disables it). |

Auto-fire sensitivity can only be changed while the room is waiting, not during
a match. See
[Game synchronization](../explanation/game-synchronization.md#auto-fire-detection).
