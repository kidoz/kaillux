# Run your first Kaillux server

In this tutorial you will build Kaillux, start it, check its health, sign in to
the admin dashboard, and connect a Kaillera client. It takes about ten minutes,
plus build time.

You do not need a database, an external service, or a configuration file. The
server ships with working defaults and a bundled `access.cfg`.

## Before you start

You need:

- Java 25 or newer. Check with `java -version`.
- Node.js 24 and npm, for the admin UI.
- The Kaillux source tree.
- A terminal.
- Optional: `just`, which wraps the build steps.
- Optional: a Kaillera-capable emulator or client for the last step.

## Step 1: Build the server and admin UI

The admin UI is a separate Vite project. Its bundle must exist before Gradle
packages the jar. From the repository root:

```bash
just build
```

`just build` installs the UI dependencies, builds the UI, and then runs the
Gradle build. The first run downloads dependencies and can take several minutes.
The build runs tests, Checkstyle, SpotBugs, and Spotless. The executable jar is
written to `build/kaillux.jar`.

Without `just`, run the same steps directly:

```bash
npm --prefix src/main/resources/static/admin install
npm --prefix src/main/resources/static/admin run build
./gradlew build
```

`./gradlew build` alone fails with a message if the UI bundle is missing.

## Step 2: Start the server

Set an admin password and start the jar:

```bash
ADMIN_PASSWORD='choose-a-strong-password' java -jar build/kaillux.jar
```

On PowerShell, set the variable first:

```powershell
$env:ADMIN_PASSWORD = 'choose-a-strong-password'
java -jar build/kaillux.jar
```

Startup logs include lines similar to these:

```text
Kaillux executor initialized with virtual threads (Project Loom)
Ready to accept connections on port 27888 (addresses: [0.0.0.0, 0:0:0:0:0:0:0:0])
Listening on UDP ports: 27889 to 27924 (addresses: [0.0.0.0, 0:0:0:0:0:0:0:0])
Kaillux server is running @ ...
```

The data-port range is calculated at startup. With the defaults
(`server.max-users=25` and `controllers.v086.extra-ports=10`), it is
27889–27924. If you change the user limit, read
[Network ports](../reference/network-ports.md) before opening a firewall.

If you omit `ADMIN_PASSWORD`, Kaillux generates a random password and prints it
once to the console. It is not written to the application log. Set the password
explicitly for anything beyond a local experiment.

Leave this terminal running.

## Step 3: Check that the server is healthy

In a second terminal:

```bash
curl -s http://127.0.0.1:8080/healthz
```

You should see:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

The Kubernetes-style probes are also available:

```bash
curl -s http://127.0.0.1:8080/healthz/liveness
curl -s http://127.0.0.1:8080/healthz/readiness
```

Both return `{"status":"UP"}` when the application is healthy.

## Step 4: Sign in to the admin dashboard

Open <http://127.0.0.1:8080/admin/> in a browser.

Sign in with:

- **Username:** `admin` (the default; override it with `ADMIN_USERNAME`)
- **Password:** the value you set in step 2

The **Overview** page shows the server version, uptime, user and game counts,
connection statistics, and controller activity. The **Users**, **Games**, and
**Controllers** pages show live data. The dashboard refreshes every ten seconds;
the refresh button requests an immediate update.

You can also call the API directly:

```bash
curl -s -u 'admin:choose-a-strong-password' \
  http://127.0.0.1:8080/api/admin/server-info
```

The response is JSON describing the server. A request without credentials
returns HTTP 401.

## Step 5: Connect a Kaillera client

Configure the Kaillera client in your emulator to connect to:

- **Server address:** `127.0.0.1`
- **Port:** `27888`

The client first sends a `HELLO` message to port 27888. The server replies with
the private data port assigned to that client, and the client continues on that
port. The assigned port is always inside the range reported at startup.

If the client cannot connect, check that UDP 27888 and the data-port range are
reachable. On a remote server, open both in the firewall; see
[Network ports](../reference/network-ports.md).

Once connected, the client appears in the **Users** page. Create a game room,
join it from a second client, and start the game. The **Games** page updates as
the room changes state.

All players in a room must use the same emulator and the same connection type.
The server rejects a start request when either differs.

## Step 6: Stop the server

Return to the server terminal and press <kbd>Ctrl</kbd>+<kbd>C</kbd>.

## What you learned

You built Kaillux, started it with an explicit admin password, checked its
health, used the admin dashboard and API, and connected a Kaillera client.

From here:

- Change ports, user limits, and timeouts in
  [Configure server settings](../how-to/configure-server-settings.md).
- Decide who may connect and which games they may play in
  [Configure access control](../how-to/configure-access-control.md).
- Understand the handshake in
  [Connection lifecycle](../explanation/connection-lifecycle.md).
- Run it as a service in
  [Install with Docker](../how-to/install-with-docker.md) or
  [Install on Arch Linux](../how-to/install-on-arch-linux.md).
