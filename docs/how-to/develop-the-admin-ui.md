# Develop the admin UI

The admin UI is a React 19 + TypeScript + Vite application under
`src/main/resources/static/admin/`. It uses Tailwind CSS 4 for styling, Zod for
response validation, and the lint/format tooling configured in the admin
project.

## Prerequisites

- Node.js 24 (the Docker build uses `node:24-alpine`)
- npm
- The Java server running on port 8080

## 1. Install dependencies

```bash
just admin-install
```

This runs `npm install` in the admin directory.

## 2. Start the backend

In one terminal:

```bash
ADMIN_PASSWORD='dev-password' ./gradlew bootRun
```

The API is available at `http://localhost:8080`.

## 3. Start the Vite dev server

In another terminal:

```bash
just admin-dev
```

Vite serves the UI with hot module replacement at
<http://localhost:5173/admin/>. Requests under `/api` are proxied to
`http://localhost:8080`, so the browser talks to one origin and the Basic auth
header from the sign-in screen is preserved.

Sign in with the admin credentials from the backend process.

## 4. Build for production

```bash
just admin-build
```

This runs `tsc` and `vite build`. The Vite base is `/admin/`, so the output is
served under `/admin/` by Spring Boot. The build output directory is
`src/main/resources/static/admin/dist/`, which Gradle packages into the jar; the
TypeScript sources and `node_modules` are excluded by `processResources`.

`just build` runs `admin-install`, `admin-build`, and `./gradlew build` in that
order, so a jar built that way contains the current UI. If you run
`./gradlew build` alone, it fails with a message unless the bundle already
exists; run `just admin-build` first after changing the UI.

## 5. Check types and style

```bash
just admin-typecheck   # tsc --noEmit
just admin-check       # lint + format check
just admin-fix         # auto-fix lint and format issues
```

There is no unit-test runner configured for the frontend. Verify changes with
type checking, the frontend lint/format checks, and manual use against a running
server.

## Code map

| File | Role |
|---|---|
| `src/main.tsx` | React entry point. |
| `src/App.tsx` | Authentication, view routing by URL hash, ten-second refresh loop, sign-out handling. |
| `src/api.ts` | Fetch wrapper with a five-second timeout, Basic auth header construction, and 401 handling. |
| `src/types.ts` | Zod schemas and inferred types for API responses. |
| `src/components/` | Login screen, sidebar, tables, stat cards, dialogs, banners. |
| `src/index.css` | Tailwind entry and theme tokens. |
| `vite.config.ts` | `/admin/` base path and `/api` proxy to port 8080. |
| `package.json` | Scripts and dependencies, including the lint/format commands. |

The API client validates every response with Zod. If a server response changes
shape, the affected view logs a validation error and keeps the previous data
rather than rendering inconsistent state. When you change a REST DTO, update the
matching schema in `types.ts`.

## API paths

The UI calls `/api/admin/*`. The server also exposes `/api/v1/admin/*`; the UI
does not use the versioned prefix.
