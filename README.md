# Kaillux

![Java](https://img.shields.io/badge/Java-25-007396?logo=java&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-9.x-02303A?logo=gradle&logoColor=white)
![License](https://img.shields.io/badge/License-GPL--2.0-blue)

Kaillux is a Kaillera network server that enables online multiplayer for
emulators by routing player input over UDP.

## Highlights

- Modern Java 25 and Spring Boot build with virtual-thread concurrency
- Admin dashboard and REST API for users, games, controllers, and relay status
- File-based access control with hot reload, temporary bans, and silences
- Prometheus metrics and Kubernetes-style health probes
- Optional UDP relay mode for clients behind NAT or firewalls
- Arch Linux packaging and a Docker image

## Quick start

You need Java 25, Node.js 24, and `just`.

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

Run the JAR with an explicit admin password:

```bash
ADMIN_PASSWORD='choose-a-strong-password' java -jar build/kaillux.jar
```

The admin dashboard is at <http://127.0.0.1:8080/admin/>. Kaillera clients
connect to UDP 27888. For a guided first run, start with
[Run your first Kaillux server](docs/tutorials/getting-started.md).

## Documentation

Documentation is organized with [Diátaxis](https://diataxis.fr/). Start at the
[documentation index](docs/README.md).

| Need | Read |
|---|---|
| Learn by doing | [Tutorials](docs/tutorials/getting-started.md) |
| Perform a task | [How-to guides](docs/README.md#how-to-guides) |
| Look up a fact | [Reference](docs/README.md#reference) |
| Understand the design | [Explanation](docs/README.md#explanation) |

Common entry points:

- [Install with Docker](docs/how-to/install-with-docker.md)
- [Install on Arch Linux](docs/how-to/install-on-arch-linux.md)
- [Configure server settings](docs/how-to/configure-server-settings.md)
- [Configure access control](docs/how-to/configure-access-control.md)
- [Enable relay mode](docs/how-to/enable-relay-mode.md)
- [Configuration properties](docs/reference/configuration.md)
- [Kaillera protocol](docs/reference/protocol.md)

## Admin, health, and metrics

- Admin API: `/api/admin/**` (HTTP Basic; set `admin.username`/`admin.password`
  or `ADMIN_USERNAME`/`ADMIN_PASSWORD`)
- Admin dashboard: `/admin/`
- Health probes: `/healthz`, `/healthz/liveness`, `/healthz/readiness`
- Prometheus metrics: `/metrics`
- OpenAPI: `/api-docs`; Swagger UI: `/swagger-ui.html`

These endpoints are intended for cluster-internal access. See
[Harden a public server](docs/how-to/harden-a-public-server.md).

## License

GPL-2.0. See [LICENSE](LICENSE).

## Credits

- **Current Maintainer:** Aleksandr Pavlov
- **Original Author:** Paul Cowan
- **Contributors:** See [GitHub contributors](../../graphs/contributors)
