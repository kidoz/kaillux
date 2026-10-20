# Use the admin dashboard

The admin dashboard is a React application served by the same Spring Boot
process. It reads the admin REST API with HTTP Basic credentials.

## Open it

1. Start Kaillux with a known admin password:

   ```bash
   ADMIN_PASSWORD='choose-a-strong-password' java -jar build/kaillux.jar
   ```

2. Open <http://127.0.0.1:8080/admin/>.

3. Sign in with the username (`admin` by default) and password.

The server redirects `/admin` and `/admin/` to `/admin/index.html`. If the
dashboard cannot sign in, confirm that `ADMIN_PASSWORD` was set in the
environment of the server process, not only in your shell.

## Pages

| Page | Shows |
|---|---|
| **Overview** | Server name and version, uptime, connected users, active games, connection counters, thread activity, and event-queue warnings. |
| **Users** | Connected users with ID, status, connection type, ping, address, and connect time. A kick action is available per user. |
| **Games** | Active rooms with ID, ROM name, owner, status, and player count. |
| **Controllers** | Protocol controller version, buffer size, connected client count, and accepted client types. |

The dashboard refreshes every ten seconds. Use the refresh button for an
immediate update. The status line shows the last successful refresh; a failed
request keeps the previous data and shows an error banner.

## Kick a user

1. Open **Users**.
2. Choose **Kick** on the target row.
3. Optionally enter a reason. The reason is shown to the user in the disconnect
   message; if blank, the server uses "Kicked by administrator".
4. Confirm.

Kicking disconnects the session. It does not ban the address. For temporary
bans, use the `/ban` chat command; see
[Manage users with chat commands](manage-users-with-chat-commands.md).

## Authentication and sessions

The dashboard stores the Basic authorization header in `sessionStorage`. It is
scoped to the browser tab and is not sent as a cookie. **Sign out** clears it.

An expired or rejected credential produces HTTP 401. The dashboard then returns
to the sign-in screen instead of repeatedly retrying.

## Use the API directly

All dashboard data is available from the admin API:

```bash
AUTH='admin:choose-a-strong-password'

curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/server-info
curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/users
curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/games
curl -s -u "$AUTH" http://127.0.0.1:8080/api/admin/controllers
```

Kick a user:

```bash
curl -s -u "$AUTH" -X POST \
  -H 'Content-Type: application/json' \
  -d '{"reason":"Excessive lag"}' \
  http://127.0.0.1:8080/api/admin/users/12/kick
```

Interactive API documentation is available at
<http://127.0.0.1:8080/swagger-ui.html>, with the OpenAPI document at
<http://127.0.0.1:8080/api-docs>. These paths are public by default; restrict
them at the proxy if the server is internet-facing. See
[Admin REST API](../reference/admin-rest-api.md) for every endpoint.

## Serve it safely

The application serves the dashboard over plain HTTP. The bundled Compose file
publishes port 8080 on `127.0.0.1` only. If you need remote access, put a
TLS-terminating reverse proxy in front of it and add authentication at the proxy
or restrict source addresses. Do not expose 8080 directly to the internet. See
[Harden a public server](harden-a-public-server.md).
