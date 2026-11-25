# Access configuration file

`access.cfg` is a line-oriented text file with four rule types. It is parsed by
`AccessConfigParser`, stored in `AccessRuleStore`, and reloaded when the file
changes.

## File location

Resolution order:

1. an explicit path passed to `FileBasedAccessManager` (not used by the default
   Spring wiring);
2. `access.cfg` in the process working directory;
3. `access.cfg` on the classpath, which in a packaged jar is extracted to a
   temporary file.

If no file is found, the server fails to start.

## General syntax

```text
<type>,<argument>,<patterns>[,<message>]
```

- One rule per line. Rules may be indented. The file is read as UTF-8.
- Lines beginning with `#` or `//` at column 1 are comments. Indented comment
  lines are parsed as rules and produce an error.
- Empty lines are ignored.
- Fields are comma-separated. The optional user announcement is the third
  field and cannot contain a comma.
- Type and keywords are case-insensitive.
- A malformed line is logged and counted as an error. At startup the rest of
  the file still loads. On a reload the previous rules stay active instead (see
  [Reload behavior](#reload-behavior)).

## Rule types

### `user`

```text
user,<NORMAL|ELEVATED|ADMIN>,<address patterns>[,<login announcement>]
```

Grants an access level to matching source addresses. The first matching `user`
line wins. If none matches, the access level is `NORMAL`.

| Level | Effect |
|---|---|
| `NORMAL` | All normal-user limits apply. |
| `ELEVATED` | Bypasses the user cap, ping, connection-type, name, emulator, and game checks. Cannot use admin commands. |
| `ADMIN` | Elevated plus admin chat commands. |

Examples:

```text
user,ADMIN,192.0.2.10,Server owner logged in
user,ELEVATED,198.51.100.20,
user,NORMAL,2001:db8:*,Welcome IPv6 user
```

An empty third field still requires the trailing comma if you want no message:

```text
user,ADMIN,192.168.1.10,
```

Do not give `ADMIN` to loopback (`127.0.0.1`, `::1`, `dns:localhost`) on a
server that clients reach through a relay, proxy, or port forwarder on the
same host. Every client then arrives from localhost and receives that access.
The server writes a warning to the log when it loads such a rule. The file
shipped with the server has the rule commented out for this reason; enable it
for local testing only.

### `ipaddress`

```text
ipaddress,<ALLOW|DENY>,<address patterns>
```

Allows or denies a source address. The first matching line wins. If none
matches, the address is allowed. Temporary bans are checked before this list.

Examples:

```text
ipaddress,DENY,203.0.113.45
ipaddress,ALLOW,192.168.*|10.*|127.0.0.1
ipaddress,DENY,*
```

### `game`

```text
game,<ALLOW|DENY>,<name patterns>
```

Allows or denies a game (ROM) name at room creation. The first match wins;
default is allow. Matching is case-insensitive.

### `emulator`

```text
emulator,<ALLOW|DENY>,<name patterns>
```

Allows or denies an emulator/client name at login. The first match wins;
default is allow. Matching is case-insensitive.

## Patterns

### Address patterns

Address rules use `DnsResolvingPattern`:

- patterns are separated by `|`;
- `*` matches any run of characters, including none;
- matching is case-insensitive against the textual IP address;
- a pattern prefixed with `dns:` is resolved to one or more addresses and
  re-resolved every 60 seconds; a failed lookup keeps the addresses of the
  last successful one, and a hostname that has never resolved is logged as a
  warning because a rule that names it matches no address;
- there are no CIDR blocks, ranges, or regular expressions.

Examples:

```text
192.168.*
10.0.0.*|127.0.0.1
dns:kaillux.example.com
2001:db8:*
```

`192.168.*` is a string match. It does not understand subnet masks.

IPv6 addresses are compared in uncompressed form, `2001:db8:0:0:0:0:0:1`. A
complete IPv6 address in a pattern is normalized for you, so
`ipaddress,DENY,2001:db8::1` works. A pattern that combines `::` with `*`
does not: write such a prefix in full, for example
`ipaddress,DENY,2001:db8:0:0:0:0:0:*`.

### Name patterns

Game and emulator rules use `SimpleNamePattern`:

- patterns separated by `|`;
- `*` matches any run of characters;
- multiple `*` segments must appear in order (for example `Street*Fighter*`);
- matching is case-insensitive;
- no `dns:` handling.

## Evaluation order and defaults

| Category | Applied to | First match wins | Default when no match |
|---|---|---|---|
| `user` | Source address | Yes | `NORMAL` |
| `ipaddress` | Source address | Yes | Allowed |
| `game` | Game name | Yes | Allowed |
| `emulator` | Emulator name | Yes | Allowed |

Temporary runtime rules are evaluated around the file rules:

- a temporary ban denies the address before `ipaddress` rules, and also takes
  `NORMAL` access away from sessions the address already has open: chat, game
  chat, and game creation are refused at once, until the maintenance pass
  disconnects the session;
- a temporary admin grant returns `ADMIN` before `user` rules;
- a temporary silence blocks chat independently of the file.

## Temporary rules

Created by admin chat commands and held in memory:

| Rule | Command | Duration |
|---|---|---|
| Ban | `/ban <userID> <minutes>` | Minutes |
| Admin grant | `/tempadmin <userID> <minutes>` | Minutes |
| Silence | `/silence <userID> <minutes>` | Minutes |

Expired rules are purged every 60 seconds. `/clear <address>` removes all three
for an address. Temporary rules are lost when the server restarts.

## Reload behavior

A background thread compares the file's modification time with that of the
loaded rules about once a second. Access checks themselves never touch the
disk. When the file has changed, a reload:

- parses the file and then replaces all permanent rules at once;
- keeps the previous rules, and logs the problem, when a line is malformed or
  the file changed while it was being read; the file is not read again until
  it changes once more;
- does not affect temporary rules;
- refreshes DNS resolutions every 60 seconds, without blocking access checks.

The log entry looks like:

```text
Reloading permissions...
Loaded 8 rules (0 errors)
```

## Samples

Allow only a private network:

```text
user,ADMIN,192.168.*,Local admin
ipaddress,ALLOW,192.168.*
ipaddress,DENY,*
game,ALLOW,*
emulator,ALLOW,*
```

Public server with a blocklist and game filter:

```text
user,ADMIN,dns:admin.example.com,
ipaddress,DENY,203.0.113.*
ipaddress,ALLOW,*
game,DENY,*Prohibited ROM*
game,ALLOW,*
emulator,ALLOW,*
```
