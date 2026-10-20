# Run tests and load tests

This guide covers the Java test suite, protocol end-to-end tests, load tests,
and the formatting and static-analysis checks.

## Unit and integration tests

```bash
./gradlew test
```

Load-tagged tests are excluded by default. The suite runs on Java 25 with JUnit
5. Reports are written under `build/reports/tests/test/`.

## Full verification

```bash
./gradlew check
```

This runs the tests plus Checkstyle, SpotBugs, Spotless, and the JaCoCo report.
The build treats compiler warnings as errors (`-Werror`), so an unused import or
a deprecated API can fail compilation.

Useful individual tasks:

```bash
./gradlew spotlessCheck    # formatting
./gradlew spotlessApply    # auto-format
./gradlew checkstyleMain   # Java lint
./gradlew spotbugsMain     # static analysis
./gradlew jacocoTestReport # coverage report
```

The coverage report is at `build/reports/jacoco/test/html/index.html`.

## End-to-end protocol tests

```bash
just e2e-test
```

This runs `su.kidoz.kaillera.protocol.ProtocolE2ETest`. It starts an embedded
server with real UDP controllers and drives it with a test client. The suite
covers the HELLO handshake, login, chat, quit, concurrent clients, reconnection,
a two-player game, and a kick.

The test uses UDP port 27788 and a data range starting at 27789, so it does not
collide with a default local Kaillux instance on 27888. Make sure that range is
free, or stop anything else bound to it.

## In-process load tests

```bash
just load-test
```

This runs `ServerLoadTest` with `-Dload.tests=true`. The suite exercises the
domain model directly with virtual-thread clients: concurrent connections, game
synchronization, chat throughput, rapid join/quit cycles, concurrent game
creation, and simulated latency. It prints a metrics report at the end.

The recipe accepts optional arguments, which are passed to Gradle:

```bash
just load-test 50 120
```

The in-process suite uses its own fixed concurrency levels; the arguments do not
change them. Use the external test below for a configurable client count.

## External load test

Start a Kaillux server first, then:

```bash
just load-test-external 20
```

This runs `ExternalServerLoadTest` with `-Dload.external=true`,
`-Dkaillera.clients=20`, and connects over UDP to `localhost:27888`. The recipe
currently passes only the client count; override the host or port with Gradle
directly if needed:

```bash
./gradlew test --tests 'su.kidoz.kaillera.load.ExternalServerLoadTest' \
  -Dload.tests=true -Dload.external=true \
  -Dkaillera.clients=20 -Dkaillera.host=192.0.2.10 -Dkaillera.port=27888
```

The server must allow the test clients. If it enforces a low `server.max-users`,
the test will see denials rather than reach its configured client count.

## Admin UI checks

```bash
just admin-install
just admin-typecheck
just admin-check
```

See [Develop the admin UI](develop-the-admin-ui.md).

## Before committing

- Run `./gradlew spotlessApply`.
- Run `just build` (it builds the admin UI before `./gradlew build`).
- Keep tests independent of a running external server unless they are in the
  load/external category.
