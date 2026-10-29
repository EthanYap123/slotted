# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Repository bootstrap: MIT licence, `.gitignore`, README and changelog.
- Phase 0 design documents:
  - `docs/COSTS.md`: free-tier limits of every planned service and how the design stays inside them.
  - `docs/PRD.md`: roles, business rules BR-1 to BR-33, user stories with acceptance criteria, out-of-scope list.
  - `docs/adr/`: ADR-0001 to ADR-0004 (double-booking prevention, authentication, time zones, lazy evaluation of time-based rules).
  - `docs/ERD.md`: data model and key constraints.
  - `docs/api/openapi.yaml` and `docs/api/errors.md`: OpenAPI 3.1 contract and error codes.
  - `docs/ARCHITECTURE.md`: system context, layers, concurrent booking sequence, booking lifecycle, environments.
