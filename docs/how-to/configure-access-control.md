# Configure access control

`access.cfg` decides who may connect, which access level they receive, and
which games and emulators are allowed. It is monitored for changes, so most
edits apply without a restart.

## Find the active file

The access manager searches in this order:

1. a path passed to its constructor (not used by the default Spring wiring);
2. `access.cfg` in the process working directory;
3. `access.cfg` packaged inside the application jar.

In practice, create `access.cfg` in the working directory to customize it:

| Deployment | Working directory and path |
|---|---|
| `java -jar` from the repository root | `./access.cfg` |
| Docker | `/app/access.cfg` (the image already has a copy) |
| Arch package | `/var/lib/kaillux/access.cfg` |

The startup log names the file it loaded:

```text
Using access file from current directory: /path/to/access.cfg
Loading access rules from: /path/to/access.cfg
```

If you do not provide a file, the server uses the packaged default. In a
packaged deployment it extracts that resource to a temporary file; edits to the
jar are not possible at runtime. Copy the sample out first:

```bash
cp src/main/resources/access.cfg ./access.cfg
```

The file path is resolved once at startup. If the server was already running
with the packaged copy, restart it once after creating the working-directory
file. After that, edits to the file are hot-reloaded.

## Understand rule order

Rules are grouped by type, and each group is evaluated from top to bottom. The
first match wins. If nothing matches, the default is permissive:

- access level `NORMAL`;
- address allowed;
- game allowed;
- emulator allowed.

Comments start with `#` or `//`. Blank lines are ignored. A malformed line is
logged and skipped; the rest of the file still loads.

```text
# Deny one address, then allow everyone else.
ipaddress,DENY,203.0.113.45
ipaddress,ALLOW,*
```

Without the final allow, the default would already allow everyone, but the
explicit line makes the intent clear and keeps the behavior stable if the
default changes later.

## Grant an access level

Syntax:

```text
user,<NORMAL|ELEVATED|ADMIN>,<address patterns>[,<login announcement>]
```

Examples:

```text
# The machine running the server.
user,ADMIN,dns:localhost,Server owner logged in

# A LAN and a fixed public address.
user,ADMIN,192.168.*|10.0.0.*|127.0.0.1,Local admin
user,ELEVATED,198.51.100.20,

# A normal user who should see a greeting.
user,NORMAL,2001:db8:*,Welcome IPv6 user
```

`ELEVATED` bypasses normal-user limits (user cap, ping, connection type, name
checks, emulator and game filters) but does not grant admin chat commands.
`ADMIN` adds those commands.

## Allow or deny addresses

Syntax:

```text
ipaddress,<ALLOW|DENY>,<address patterns>
```

Allow only a private network:

```text
ipaddress,ALLOW,192.168.*|10.*|127.0.0.1
ipaddress,DENY,*
```

Ban a range but let one address through:

```text
ipaddress,ALLOW,198.51.100.7
ipaddress,DENY,198.51.100.*
ipaddress,ALLOW,*
```

Address patterns are matched against the textual IP address, not CIDR. `*`
matches any run of characters, and `|` separates alternatives. DNS names are
supported with the `dns:` prefix and are re-resolved every 60 seconds:

```text
ipaddress,ALLOW,dns:example.com|192.168.*
```

## Filter games and emulators

Syntax:

```text
game,<ALLOW|DENY>,<name patterns>
emulator,<ALLOW|DENY>,<name patterns>
```

Allow only two games:

```text
game,ALLOW,NBA Jam*
game,ALLOW,NBA Maximum*
game,DENY,*
```

Ban games and emulators by name fragment:

```text
game,DENY,*Capcom*|*Chat (not game)|*Away (leave messages)
emulator,DENY,Kawaks*
emulator,ALLOW,*
```

Name matching is case-insensitive. The wildcard and alternation rules are the
same as for addresses, but there is no `dns:` handling.

## Apply changes

Save the file. The maintenance thread checks its modification time every 60
seconds, and many access checks also trigger a reload immediately. A connection
made right after saving normally sees the new rules without a restart.

Watch the log for the result:

```text
Reloading permissions...
Loaded 8 rules (0 errors)
```

If a line is rejected, the count of errors increases and the line is logged with
its content. Fix the line and save again.

Temporary bans, silences, and admin grants are separate from the file and are
not affected by a reload. See
[Manage users with chat commands](manage-users-with-chat-commands.md).

## Protect the file

The file can contain address-based privileges. Restrict it to the account that
runs the server:

```bash
chmod 600 access.cfg
```

In Docker, the sample mount is read-only. Hot reload still works because the
server watches the mounted file.
