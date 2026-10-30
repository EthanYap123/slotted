# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `api/`: Spring Boot 3.5 (Java 21) skeleton with Maven wrapper, Flyway baseline migration, Actuator health probes, stateless security baseline and a Testcontainers Postgres 17 integration test.
- ADR-0005: Spring Boot 3.5 and Maven.
- `web/`: React 19 + TypeScript (strict) on Vite 8 with TanStack Query and React Router; ESLint 9 (typescript-eslint strict, React hooks, jsx-a11y), Prettier, and Vitest with Testing Library; placeholder page showing API status.
- `docker-compose.yml` for a local Postgres 17 with a health check; example env files for Compose, the API and the web app; local setup instructions in the README.
- GitHub Actions CI on pull requests and `main`: API build and Testcontainers tests (in a non-Singapore time zone), web lint/format/typecheck/test/build, Compose smoke test, gitleaks secret scan over full history, and one `CI result` check for branch protection. Dependabot for Maven, npm, Actions and Compose.
- API quality gates in `./mvnw verify`: Spotless (google-java-format), Checkstyle, JaCoCo minimum coverage (80% lines, 70% branches), and ArchUnit rules for layers, the injected `Clock`, constructor injection and feature cycles. CI publishes a coverage summary and report.
- `CONTRIBUTING.md` (workflow, Conventional Commits, conventions, definition of done), `SECURITY.md`, pull request template and issue forms.
- Recommended branch protection for `main` as a GitHub ruleset in `.github/rulesets/main.json`, explained in `docs/BRANCH_PROTECTION.md`.
- Dependabot skips major updates that are deferred on purpose: Spring Boot 4, ESLint 10 and Postgres 18.

### Fixed

- Hikari connection-pool timeouts are given in milliseconds; the duration format stopped the API from starting.
- Repository bootstrap: MIT licence, `.gitignore`, README and changelog.
- Phase 0 design documents:
  - `docs/COSTS.md`: free-tier limits of every planned service and how the design stays inside them.
  - `docs/PRD.md`: roles, business rules BR-1 to BR-33, user stories with acceptance criteria, out-of-scope list.
  - `docs/adr/`: ADR-0001 to ADR-0004 (double-booking prevention, authentication, time zones, lazy evaluation of time-based rules).
  - `docs/ERD.md`: data model and key constraints.
  - `docs/api/openapi.yaml` and `docs/api/errors.md`: OpenAPI 3.1 contract and error codes.
  - `docs/ARCHITECTURE.md`: system context, layers, concurrent booking sequence, booking lifecycle, environments.
