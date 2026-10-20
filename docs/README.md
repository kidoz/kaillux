# Kaillux documentation

Kaillux is a Kaillera network server. It lets emulator-based games play
multiplayer over UDP by receiving player input from each client and forwarding
it to every other player in the same game room.

This documentation follows the [Diátaxis](https://diataxis.fr/) framework. Each
page serves one of four needs:

| If you want to... | Use | Because |
|---|---|---|
| Learn by doing, from the beginning | [Tutorials](#tutorials) | A guided lesson with a working result |
| Accomplish a specific task | [How-to guides](#how-to-guides) | Steps for a problem you already understand |
| Look up a fact, name, or value | [Reference](#reference) | Complete, precise information |
| Understand how or why something works | [Explanation](#explanation) | Background, design, and trade-offs |

## Start here

New to Kaillux? Work through
[Run your first Kaillux server](tutorials/getting-started.md). It builds the
project, starts a server, verifies it, signs in to the admin dashboard, and
connects a Kaillera client.

## Tutorials

Learning-oriented lessons. Follow them in order; each step builds on the last.

- [Run your first Kaillux server](tutorials/getting-started.md) — build, start,
  verify, and connect to a local server.

## How-to guides

Task-oriented recipes. Jump directly to the task you need.

### Install and configure

- [Install with Docker](how-to/install-with-docker.md)
- [Install on Arch Linux](how-to/install-on-arch-linux.md)
- [Configure server settings](how-to/configure-server-settings.md)
- [Configure access control](how-to/configure-access-control.md)
- [Register with master servers](how-to/register-with-master-servers.md)

### Operate

- [Use the admin dashboard](how-to/use-the-admin-dashboard.md)
- [Manage users with chat commands](how-to/manage-users-with-chat-commands.md)
- [Monitor the server](how-to/monitor-the-server.md)
- [Enable relay mode](how-to/enable-relay-mode.md)
- [Harden a public server](how-to/harden-a-public-server.md)
- [Troubleshoot client connections](how-to/troubleshoot-client-connections.md)

### Develop

- [Develop the admin UI](how-to/develop-the-admin-ui.md)
- [Run tests and load tests](how-to/run-tests-and-load-tests.md)

## Reference

Information-oriented descriptions. These pages do not teach or prescribe.

- [Configuration properties](reference/configuration.md)
- [Access configuration file](reference/access-config-file.md)
- [Admin REST API](reference/admin-rest-api.md)
- [Chat commands](reference/chat-commands.md)
- [Kaillera protocol](reference/protocol.md)
- [Master server protocol](reference/master-server-protocol.md)
- [Game data and connection types](reference/game-data-and-connection-types.md)
- [Network ports](reference/network-ports.md)
- [Metrics](reference/metrics.md)
- [Build and task reference](reference/build-and-tasks.md)
- [Glossary](reference/glossary.md)
- [Archived protocol captures](reference/archive/README.md)

## Explanation

Understanding-oriented discussion of design and behavior.

- [Architecture](explanation/architecture.md)
- [Connection lifecycle](explanation/connection-lifecycle.md)
- [Game synchronization](explanation/game-synchronization.md)
- [Relay mode](explanation/relay-mode.md)
- [Threading model](explanation/threading-model.md)
- [Access control model](explanation/access-control-model.md)
- [Master server registration](explanation/master-server-registration.md)
- [Security model](explanation/security-model.md)

## Documentation conventions

- Commands run from the repository root unless a page says otherwise.
- Configuration properties are written in kebab-case, as in
  `application.properties`. The equivalent environment variable is the same name
  in upper snake case, for example `server.max-users` becomes
  `SERVER_MAX_USERS`.
- `example` values are illustrative. Replace them with values that fit your
  deployment.
- Pages in this tree describe Kaillux 1.4.0 unless noted.
