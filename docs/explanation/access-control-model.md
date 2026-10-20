# Access control model

Kaillux has two layers of access control: a file of permanent rules that an
operator maintains, and a small set of temporary rules that admins create at
runtime with chat commands.

## Permanent rules: `access.cfg`

`FileBasedAccessManager` reads `access.cfg` and parses it into four independent
rule lists:

| Rule type | Controls |
|---|---|
| `user` | The access level for source addresses, plus an optional login announcement. |
| `ipaddress` | Whether a source address may connect at all. |
| `game` | Whether a game (ROM) name may be used to create a room. |
| `emulator` | Whether an emulator/client name may log in. |

Each category is evaluated separately, top to bottom, and the first matching
rule wins. If no rule matches, the default is permissive:

- access level `NORMAL`;
- address allowed;
- game allowed;
- emulator allowed.

That is why a file containing only `ipaddress,ALLOW,*` behaves like no address
filter at all, and why a deny list usually needs a final allow:

```text
ipaddress,DENY,203.0.113.*
ipaddress,ALLOW,*
```

## Access levels

| Level | Value | Meaning |
|---|---:|---|
| `BANNED` | 0 | The source is denied. Login validation rejects any access below `NORMAL`. |
| `NORMAL` | 1 | Subject to all limits: max ping, allowed connection types, name lengths, game/emulator filters, idle timeout. |
| `ELEVATED` | 2 | Bypasses normal-user limits, including the user cap, ping, connection type, name and emulator checks. |
| `ADMIN` | 3 | Same bypasses as elevated, plus permission to use admin chat commands. |

An address becomes elevated or admin through a `user` line or a temporary
`/tempadmin` grant. Admin commands also verify that the command came from the
same address that performed the handshake, so a spoofed chat packet cannot act
with someone else's privilege.

## Temporary rules

`TemporaryRuleStore` holds three rule types in memory:

- **Ban** — denies connection from the address. Checked before the file's
  `ipaddress` rules.
- **Admin grant** — returns `ADMIN` from `getAccess`. Checked before the file's
  `user` rules.
- **Silence** — blocks chat, not connection. Checked by the chat moderation
  service.

Temporary rules expire after the configured number of minutes. A maintenance
thread purges expired rules every 60 seconds. They are not persisted: restarting
the server clears them. `/clear <address>` removes all temporary rules for an
address.

## Pattern matching

Address patterns and name patterns use different matchers:

- **Addresses** (`user`, `ipaddress`) use `DnsResolvingPattern`. Patterns are
  pipe-separated wildcard strings, matched case-insensitively against the
  textual IP address. A pattern prefixed with `dns:` is resolved to one or more
  addresses and re-resolved every 60 seconds, which supports dynamic DNS.
- **Names** (`game`, `emulator`) use `SimpleNamePattern`. Patterns are
  pipe-separated wildcard strings, matched case-insensitively against the name.

`*` matches any run of characters. `|` separates alternatives. There are no
regular expressions and no CIDR blocks; a pattern such as `192.168.*` is a
string match, not an address-range calculation.

## Hot reload

The maintenance thread compares the file's modification time every 60 seconds.
Many access checks also call `checkReload()` directly, so an edit is usually
visible on the next connection or chat message, not only at the next tick.

Reload replaces all permanent rules atomically. A parse error on one line is
logged and counted; that line is skipped. The rest of the file still loads.
Temporary rules are unaffected by a reload.

## Where the checks are applied

| Check | Applied at |
|---|---|
| Address allowed | Connect handshake, before allocating a data port. |
| Access level | Login, and periodically by server maintenance. |
| Ping | Login for normal users. |
| Connection type | Login for normal users. |
| User and client name lengths | Login for normal users. |
| Emulator allowed | Login, and periodically by maintenance. |
| Game allowed | Game creation. |
| Silence | Chat and game chat. |
| Admin commands | Each command, after verifying peer address and access. |

Because maintenance re-evaluates the access file, a user whose emulator becomes
disallowed while connected is disconnected on the next maintenance pass.
Temporary bans take effect immediately because the ban command disconnects the
user and the handshake denies the address afterwards.

Related pages: [Access configuration file](../reference/access-config-file.md),
[Configure access control](../how-to/configure-access-control.md).
