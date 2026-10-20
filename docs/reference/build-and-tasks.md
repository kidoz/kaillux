# Build and task reference

The project builds with the Gradle wrapper. The `justfile` wraps common
commands, including the admin UI build.

## Prerequisites

- JDK 25 (the Gradle toolchain targets Java 25)
- Node.js 24 and npm for the admin UI
- `just` (optional; all recipes are also available as direct commands)
- Docker for image builds

## Gradle tasks

| Task | Effect |
|---|---|
| `./gradlew build` | Compile, run tests, Checkstyle, SpotBugs, Spotless, JaCoCo report, and build `build/kaillux.jar`. Requires the admin UI bundle to exist. |
| `./gradlew bootJar` | Build the executable jar only. Does not run checks or tests, but still requires the admin UI bundle. |
| `./gradlew bootRun` | Run the application from source. Requires the admin UI bundle. |
| `./gradlew verifyAdminUi` | Check that `src/main/resources/static/admin/dist/` exists. |
| `./gradlew clean` | Delete `build/`. |
| `./gradlew test` | Run tests. Load-tagged tests are excluded by default. |
| `./gradlew check` | Run the full verification lifecycle. |
| `./gradlew spotlessCheck` | Verify Java formatting. |
| `./gradlew spotlessApply` | Apply Java formatting. |
| `./gradlew checkstyleMain` | Run Checkstyle on main sources. |
| `./gradlew spotbugsMain` | Run SpotBugs on main sources. |
| `./gradlew jacocoTestReport` | Generate the coverage report. |
| `./gradlew jacocoTestCoverageVerification` | Enforce the configured coverage threshold. |

The compiler uses `-Werror`, `-Xlint:deprecation`, and `-Xlint:unchecked`, so
warnings fail the build. The executable jar is written to `build/kaillux.jar`.
The plain `jar` task is disabled.

## `just` recipes

| Recipe | Effect |
|---|---|
| `just build` | Install admin dependencies, build the admin UI, then run `./gradlew build`. |
| `just rebuild` | Same as `build` after `clean`. |
| `just run` | `./gradlew bootRun`. |
| `just check-style` | `./gradlew spotlessCheck`. |
| `just format` | `./gradlew spotlessApply`. |
| `just lint` | `./gradlew checkstyleMain`. |
| `just load-test [clients] [timeout]` | Run `ServerLoadTest` with load tests enabled. Arguments are passed to Gradle. |
| `just load-test-external [clients]` | Run `ExternalServerLoadTest` against `localhost:27888`. |
| `just e2e-test` | Run `ProtocolE2ETest`, which starts an embedded UDP server. |
| `just admin-install` | `npm install` in the admin directory. |
| `just admin-dev` | Start Vite with HMR. |
| `just admin-build` | Type-check and build the admin UI. |
| `just admin-typecheck` | `tsc --noEmit`. |
| `just admin-lint` | Lint the admin sources. |
| `just admin-check` | Run the lint and format check. |
| `just admin-fix` | Auto-fix lint and format issues. |
| `just docker-build` | `docker build -t kaillux .`. |
| `just docker-run` | Run the image with HTTP and UDP port mappings. |

`just build` builds the admin UI before the jar so the jar contains the current
frontend. The Gradle `bootJar` and `bootRun` tasks depend on `verifyAdminUi`,
which fails with an explanatory message when
`src/main/resources/static/admin/dist/` is missing. Run `just admin-build` (or
`npm run build` in the admin directory) before invoking Gradle directly.

## Admin UI npm scripts

Run from `src/main/resources/static/admin/` or through the `just` recipes:

| Script | Command | Effect |
|---|---|---|
| `dev` | `vite` | Development server with HMR. |
| `build` | `tsc && vite build` | Type-check and produce `dist/`. |
| `preview` | `vite preview` | Serve the production build locally. |
| `typecheck` | `tsc --noEmit` | Type-check only. |
| `lint` | Configured linter | Lint. |
| `format` | Configured formatter | Format. |
| `check` | Configured checks | Lint and format check. |
| `check:fix` | Configured checks | Auto-fix lint and format. |

## Build outputs

| Path | Contents |
|---|---|
| `build/kaillux.jar` | Executable Spring Boot jar. |
| `build/reports/tests/test/` | Test reports. |
| `build/reports/checkstyle/` | Checkstyle HTML reports. |
| `build/reports/spotbugs/` | SpotBugs HTML reports. |
| `build/reports/jacoco/test/html/` | Coverage report. |
| `src/main/resources/static/admin/dist/` | Built admin UI bundled into the jar. |

## Toolchain and versions

- Java 25 via the Gradle toolchain.
- Spring Boot and dependency versions come from
  `gradle/libs.versions.toml`; Spring Boot manages its own supported library
  versions.
- Spotless uses the Eclipse formatter with the AOSP profile and the repository
  configuration in `config/eclipse-formatter.xml`.
- Checkstyle configuration: `config/checkstyle/checkstyle.xml`.
- SpotBugs exclusions: `config/spotbugs/exclude.xml`.
- Frontend lint/format scripts and dependencies:
  `src/main/resources/static/admin/package.json`.
