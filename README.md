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

## License

[MIT](LICENSE)
