# Slotted

A booking system for shared spaces such as study rooms, sports courts and meeting rooms. Students book time slots, facility managers look after rooms, opening hours and blackout dates, and admins manage users and see how the spaces are used.

Key features: conflict-free booking under concurrency, recurring bookings, a waitlist with auto-promotion, QR code check-in, auto-release of no-shows after 15 minutes, email confirmations and an audit log.

**Status:** Phase 0, requirements and design. No application code yet.

## Design documents

| Document | What it covers |
| --- | --- |
| [Product requirements](docs/PRD.md) | Roles, business rules, user stories and acceptance criteria, out of scope |
| [Architecture](docs/ARCHITECTURE.md) | System context, layers, the concurrent-booking sequence, booking lifecycle, environments |
| [Data model](docs/ERD.md) | Tables, relationships and the constraints that prevent double bookings |
| [API contract](docs/api/openapi.yaml) | OpenAPI 3.1 definition, with [error codes](docs/api/errors.md) |
| [Decision records](docs/adr/README.md) | Why: exclusion constraint vs locking, auth, time zones, lazy evaluation on a sleeping server |
| [Costs](docs/COSTS.md) | Free-tier limits of every service and how the design stays inside them |

Stack: Spring Boot 3 (Java 21), PostgreSQL with Flyway, Spring Security with JWT, React + TypeScript (Vite) with TanStack Query. Everything runs on free tiers ([`docs/COSTS.md`](docs/COSTS.md)).

## Run it locally

You need Java 21 or newer, Node.js 22 or newer and Docker.

```bash
# 1. Start Postgres 17 (first time: copy the example settings)
cp .env.example .env
docker compose up -d --wait

# 2. Start the API on http://localhost:8080 (Flyway migrates the database on startup)
cp api/.env.example api/.env
cd api && ./mvnw spring-boot:run

# 3. In another terminal, start the web app on http://localhost:5173
cp web/.env.example web/.env.local
cd web && npm ci && npm run dev
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

### Checks

| Area | Command | What it runs |
| --- | --- | --- |
| API | `cd api && ./mvnw verify` | Unit and integration tests (integration tests need Docker for Testcontainers) |
| Web | `cd web && npm run lint && npm run typecheck && npm test && npm run build` | ESLint, TypeScript, Vitest, production build |

### Environment files

| File | Used by | Example |
| --- | --- | --- |
| `.env` | Docker Compose | [`.env.example`](.env.example) |
| `api/.env` | API (read by Spring Boot on startup) | [`api/.env.example`](api/.env.example) |
| `web/.env.local` | Vite dev server and build | [`web/.env.example`](web/.env.example) |

All real `.env` files are git-ignored. Staging and production secrets live only in the hosting dashboards and GitHub Actions secrets.

## License

[MIT](LICENSE)
