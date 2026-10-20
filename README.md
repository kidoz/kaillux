# Kaillux

![Java](https://img.shields.io/badge/Java-25-007396?logo=java&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.x-02303A?logo=gradle&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--2.0-blue)

Kaillux is a Kaillera network server that enables online multiplayer for
emulators by routing player input over the network.

## Highlights

- Modernized Java 25 + Spring Boot build
- Admin API and health/metrics endpoints for ops visibility
- File-based access control with temporary bans and silencing
- Arch Linux packaging with systemd service

## Quick start

Build the server (the admin UI is built first):

```bash
just build
```

`just build` installs the admin UI dependencies, builds the Vite bundle, and then
packages it into the server JAR with Gradle. Building with `./gradlew build`
directly requires the bundle to exist (`just admin-build`); otherwise the build
fails with a message explaining the missing step.

Dependency, plugin, and analysis tool versions are defined in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml). Spring Boot manages
the versions of its supported libraries.

Run the JAR:

```bash
java -jar build/kaillux.jar
```

## Installation

### Arch Linux

Build and install using the provided PKGBUILD:

```bash
cd packaging/archlinux
makepkg -si
```

Start the service:

```bash
sudo systemctl enable --now kaillux
```

Configuration files are installed to `/etc/kaillux/`.

### Docker

```bash
export ADMIN_PASSWORD='choose-a-strong-unique-password'
docker compose up -d --build
```

Compose requires a nonempty `ADMIN_PASSWORD`. Set `ADMIN_USERNAME` to override
the default username `admin`. The admin UI is available locally at
`http://127.0.0.1:8080/admin/`; UDP ports 27888 and 27889–27924 are published
for game clients. If you change the maximum user count, also adjust the data-port
range to cover `max-users + extra-ports + 1` ports.

The image includes default access rules. For custom rules, copy
`src/main/resources/access.cfg` to `access.cfg` in the project root and uncomment
the `volumes` block in `compose.yaml`. The mount supports hot-reload and fails
clearly if the host file is missing, without creating a directory in its place.

For relay mode, uncomment the UDP 27887 port mapping and the relay environment
settings in `compose.yaml`, then set the backend host and port. The published
data-port range must cover the ports assigned by the backend server. Clients
connect to the relay on UDP 27887.

### Manual

Run directly with Java 25+:

```bash
java -jar build/kaillux.jar
```

## Configuration

- App config: `src/main/resources/application.properties`
- Access control rules: `src/main/resources/access.cfg`
- Logging: `src/main/resources/logback.xml`

For packaged installations (Arch Linux), config files are in `/etc/kaillux/`.

## Architecture

### Package Structure

All code now lives under a single package hierarchy:

- **`su.kidoz.*`** - Kaillux Kaillera server codebase

Tests mirror the source package structure (e.g., `su.kidoz.kaillera.model`).

## Admin, health, and metrics

- Admin API: `/api/admin/**` (HTTP Basic; set `admin.username`/`admin.password`)
- Health probes: `/healthz`, `/livez`, `/readyz`
- Prometheus metrics: `/metrics`

These endpoints are intended for cluster-internal access only.

## License

GPL-2.0. See `LICENSE`.

## Credits

- **Current Maintainer:** Aleksandr Pavlov
- **Original Author:** Paul Cowan
- **Contributors:** See [GitHub contributors](../../graphs/contributors)
