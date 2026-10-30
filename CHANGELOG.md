# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `api/`: Spring Boot 3.5 (Java 21) skeleton with Maven wrapper, Flyway baseline migration, Actuator health probes, stateless security baseline and a Testcontainers Postgres 17 integration test.
- ADR-0005: Spring Boot 3.5 and Maven.
- `web/`: React 19 + TypeScript (strict) on Vite 8 with TanStack Query and React Router; ESLint 9 (typescript-eslint strict, React hooks, jsx-a11y), Prettier, and Vitest with Testing Library; placeholder page showing API status.
- `docker-compose.yml` for a local Postgres 17 with a health check; example env files for Compose, the API and the web app; local setup instructions in the README.
- Repository bootstrap: MIT licence, `.gitignore`, README and changelog.
- Phase 0 design documents:
  - `docs/COSTS.md`: free-tier limits of every planned service and how the design stays inside them.
  - `docs/PRD.md`: roles, business rules BR-1 to BR-33, user stories with acceptance criteria, out-of-scope list.
  - `docs/adr/`: ADR-0001 to ADR-0004 (double-booking prevention, authentication, time zones, lazy evaluation of time-based rules).
  - `docs/ERD.md`: data model and key constraints.
  - `docs/api/openapi.yaml` and `docs/api/errors.md`: OpenAPI 3.1 contract and error codes.
  - `docs/ARCHITECTURE.md`: system context, layers, concurrent booking sequence, booking lifecycle, environments.
