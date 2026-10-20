# Install with Docker

This guide runs Kaillux as a Docker Compose service, including the built admin
UI. It assumes Docker Engine and the Compose plugin are installed.

## Start the server

Set a strong admin password in the shell. Compose requires it and refuses to
start when it is empty:

```bash
export ADMIN_PASSWORD='choose-a-strong-unique-password'
docker compose up -d --build
```

The first build compiles the Java application and the admin UI. Later starts
reuse the image unless you pass `--build` again.

`ADMIN_USERNAME` is optional and defaults to `admin`.

Check that it started:

```bash
docker compose ps
docker compose logs -f kaillux
```

The health check calls `http://localhost:8080/healthz` every 30 seconds. A
healthy container reports `healthy` in `docker compose ps`.

## Connect to it

| Service | Address |
|---|---|
| Admin dashboard | <http://127.0.0.1:8080/admin/> |
| Admin API | `http://127.0.0.1:8080/api/admin/` |
| Health | `http://127.0.0.1:8080/healthz` |
| Metrics | `http://127.0.0.1:8080/metrics` |
| Kaillera connect | UDP 27888 |
| Kaillera data ports | UDP 27889–27924 |

Compose publishes port 8080 on `127.0.0.1` only, so the admin surface is not
reachable from other hosts by default. The UDP ports are published on all
interfaces.

## Use custom access rules

The image contains the default `access.cfg`. To use your own:

1. Copy the default file to the project root:

   ```bash
   cp src/main/resources/access.cfg ./access.cfg
   ```

2. Edit `access.cfg`.

3. Uncomment the `volumes` block in `compose.yaml`:

   ```yaml
   volumes:
     - type: bind
       source: ./access.cfg
       target: /app/access.cfg
       read_only: true
       bind:
         create_host_path: false
   ```

4. Recreate the service:

   ```bash
   docker compose up -d
   ```

The mount is read-only and `create_host_path: false` means a missing host file
fails the start instead of creating a directory. The file is still monitored for
changes, so edits apply without a restart. See
[Configure access control](configure-access-control.md).

## Change the user limit and port range

`compose.yaml` sets `SERVER_MAX_USERS` and publishes a fixed UDP range. The
data-port count is:

```text
server.max-users + controllers.v086.extra-ports + 1
```

With the defaults (`25 + 10 + 1`), Kaillux needs 36 ports: 27889–27924. If you
raise the user limit, widen the published range to match. For example, for 50
users with 10 extra ports:

```yaml
environment:
  SERVER_MAX_USERS: "50"
ports:
  - "27889-27960:27889-27960/udp"
```

Also update any host firewall rules.

## Enable relay mode

Relay mode needs its own published port and the backend's data-port range:

```yaml
ports:
  - "27887:27887/udp"
environment:
  RELAY_ENABLED: "true"
  RELAY_BACKEND_HOST: "backend.example.com"
  RELAY_BACKEND_PORT: "27888"
```

The published data-port range must cover the ports the backend assigns.
Clients connect to UDP 27887 on the relay. See
[Enable relay mode](enable-relay-mode.md).

## Logs and troubleshooting

```bash
docker compose logs -f kaillux      # follow console logs
docker compose exec kaillux ls -l   # workspace inside the container
```

The application also writes `kaillux.log` in `/app` when file logging is
enabled in `logback.xml`. The default configuration logs to the console only.

Startup failures are usually visible immediately in the logs: a missing
`ADMIN_PASSWORD`, a bind conflict on 8080 or a UDP port, or an invalid
`access.cfg` path.

## Stop or upgrade

```bash
docker compose down            # stop and remove the container
docker compose up -d --build   # rebuild after pulling source changes
```

Configuration set through environment variables is not persisted by Compose.
Keep the password and any custom settings in a `.env` file or your deployment
system.
