# Register with master servers

Kaillux can announce itself to the Kaillera master list and to an
EmuLinker-compatible master. Registration is a periodic outbound HTTP request;
no inbound master traffic is required.

## 1. Describe the server

Set these properties in `application.properties` or as environment variables:

```properties
master-list.server-name=My Kaillux Server
master-list.server-location=Berlin, DE
master-list.server-website=https://example.com
master-list.server-connect-address=kaillux.example.com
```

| Property | Environment variable | Meaning |
|---|---|---|
| `master-list.server-name` | `MASTER_LIST_SERVER_NAME` | Name shown in the server list. |
| `master-list.server-location` | `MASTER_LIST_SERVER_LOCATION` | Free-text location. |
| `master-list.server-website` | `MASTER_LIST_SERVER_WEBSITE` | Website URL shown to clients. |
| `master-list.server-connect-address` | `MASTER_LIST_SERVER_CONNECT_ADDRESS` | Address clients should use. Set this to the public address, especially behind NAT. |

If `server-connect-address` is empty, clients must rely on the address through
which they reach the server. Set it explicitly for a public deployment.

## 2. Choose the masters

```properties
master-list.touch-kaillera=true
master-list.touch-emulinker=true
```

The Kaillera master URL is fixed. The EmuLinker-compatible URL is configurable:

```properties
master-list.emulinker-master-url=http://master.emulinker.org/touch.php
```

Set either switch to `false` to stop reporting to that master. If both are
false, the updater thread is not started.

## 3. Restart and verify

Restart the server, then watch the log. After the server is fully started, the
updater sleeps 60 seconds and touches the enabled masters:

```text
Touching Kaillux master list done
Touching Kaillera Master done
```

A failure is logged but does not stop future attempts:

```text
Failed to touch Kaillera Master: ...
```

The server needs outbound HTTP access to the master hosts. It does not use a
proxy unless the JVM is configured with one.

## 4. Confirm the listing

Look up the server in the master's server list. The Kaillera listing includes
the name, address, current and maximum users, game count, version, and location.
The EmuLinker listing includes the same metadata plus version, build, and host
OS information.

If the server appears with the wrong address, fix
`master-list.server-connect-address` and restart. The master does not verify
that the reported address belongs to the server.

## Behavior to expect

- Reports are sent every 60 seconds.
- Started games and waiting rooms are included in the Kaillera report.
- The EmuLinker response may report `updateAvailable=true`; Kaillux logs a
  warning with the latest version and notes but does not self-update.
- Registration is unauthenticated. Anyone can report an address to the master.

For the wire format and request parameters, see
[Master server protocol](../reference/master-server-protocol.md). For the design
rationale, see
[Master server registration](../explanation/master-server-registration.md).
