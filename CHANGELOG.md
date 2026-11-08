# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Accounts and sign-in (#35): register, sign in, refresh and sign out, `GET /me`. BCrypt passwords of 10-128 characters checked against 9,000 common passwords; 15-minute HS256 access tokens; refresh tokens stored as SHA-256 hashes, rotated on every use, with reuse revoking the whole session. Sign-in errors never reveal whether an email exists.
- Admin user management (#36): search users by email or name and filter by role, change roles, disable and re-enable accounts, lift suspensions. Role and status changes end the user's sessions; admin endpoints re-check the database so a demoted admin's unexpired token stops working. Admins cannot remove their own access. The first admin is created from `ADMIN_EMAIL`/`ADMIN_PASSWORD` on startup.
- Check-in and the cleanup job (#53): students check in by scanning the room's QR code, which opens the web app's check-in page, inside the window from 10 minutes before the start to 15 after; each refusal says why. Managers get a printable QR code per room and can rotate it so old copies stop working. A checked-in booking can be ended once, releasing the rest from the next half hour (keeping at least its first 30 minutes) to the waitlist. A key-protected sweep, called hourly by a GitHub Actions schedule once the API is deployed, records overdue no-shows, promotes into the time they free, records expired waitlist entries and deletes old refresh tokens. It is idempotent, skips when another sweep holds its lock, and refuses calls less than a minute apart.
- Waitlists (#52): students join a queue for a taken slot from a booked cell on the timeline, or when told a slot was just taken, and see their place in My bookings. Freed time goes to the first eligible student straight away, whether it was freed by a cancellation, a no-show, a blackout, shorter hours or a disabled account. A student who is no longer eligible stays waiting while the next person is tried. Entries expire when their slot starts, blackouts and shorter hours expire the entries they rule out, and their previews count them. Promotion never waits for a student's lock, and commits separately from a booking attempt that fails (ADR-0007).
- Recurring bookings (#51): repeat a booking weekly (staff can also repeat daily), up to 8 dates, with a preview of which dates can be booked and why the others cannot. Dates that clash are skipped and listed; the rest are booked together. Repeating bookings have their own limits on how far ahead they reach and how many one student holds, and each date stays a normal booking under the same rules and exclusion constraint. My bookings can cancel one date or this date and every later one.
- Blackouts (#50): block a room or a whole facility, with a dry-run preview of the bookings it would cancel. Shorter opening hours preview and cancel the same way, and disabling an account cancels its future bookings. Schedule changes take the same room locks as bookings, so a booking racing a blackout is never left inside it.
- Web booking screens (#41): a facility timeline of 30-minute cells in Singapore time, usable by keyboard and on a phone; a booking dialog; `SLOT_TAKEN` refreshes the timeline; My bookings with upcoming and past, and cancelling with a late-cancellation warning.
- Web sign-in (#40): register, sign in and out, tokens refreshed behind the scenes with one refresh at a time across tabs, routes guarded by sign-in and role, a notice while the server wakes, and a light-by-default theme with a switch. API types are generated from the OpenAPI contract and checked in CI.
- Bookings in one room now take turns behind a room row lock, taken after the student's lock (ADR-0006). The concurrency tests showed that simultaneous inserts could deadlock in the exclusion constraint's index, turning most losing requests into 500s; with the lock they get `409 SLOT_TAKEN`. A small retry covers any lock failure that remains.
- Concurrency tests (#39): 16 students racing for one slot always produce exactly one booking (repeated 3 times); partly overlapping races never leave overlapping rows; one student's 5 parallel requests never beat the 3-booking quota; and raw SQL that tries to double book is refused by the constraint.
- Bookings (#38): book, cancel, view and list bookings, and a facility's day timeline. Double bookings are refused by a Postgres exclusion constraint (ADR-0001); every rule in PRD 5.1 is checked with a clear error code; students' quotas run under a row lock; no-shows are worked out from the time on every request and written down before each new booking in that room (ADR-0004); three no-shows in 30 days suspend a student for 7 days. Late cancellations are recorded; managers cancel with a reason.
- Facilities, opening hours, managers and rooms (#37): admins create facilities and assign managers; managers edit their own facilities, set weekly opening hours on the hour or half hour (with a dry-run preview), and add, edit or deactivate rooms. Access is checked against the database on every change, so a removed manager loses access at once. Names are unique ignoring case; edits can send `If-Match` to avoid overwriting someone else's change. `/me` now lists the facilities a manager runs.
- Every API error is an RFC 9457 problem with a stable `code` and the request's `X-Request-Id`; CORS allows only configured web origins.
- `api/`: Spring Boot 3.5 (Java 21) skeleton with Maven wrapper, Flyway baseline migration, Actuator health probes, stateless security baseline and a Testcontainers Postgres 17 integration test.
- ADR-0005: Spring Boot 3.5 and Maven.
- `web/`: React 19 + TypeScript (strict) on Vite 8 with TanStack Query and React Router; ESLint 9 (typescript-eslint strict, React hooks, jsx-a11y), Prettier, and Vitest with Testing Library; placeholder page showing API status.
- `docker-compose.yml` for a local Postgres 17 with a health check; example env files for Compose, the API and the web app; local setup instructions in the README.
- GitHub Actions CI on pull requests and `main`: API build and Testcontainers tests (in a non-Singapore time zone), web lint/format/typecheck/test/build, Compose smoke test, gitleaks secret scan over full history, and one `CI result` check for branch protection. Dependabot for Maven, npm, Actions and Compose.
- API quality gates in `./mvnw verify`: Spotless (google-java-format), Checkstyle, JaCoCo minimum coverage (80% lines, 70% branches), and ArchUnit rules for layers, the injected `Clock`, constructor injection and feature cycles. CI publishes a coverage summary and report.
- `CONTRIBUTING.md` (workflow, Conventional Commits, conventions, definition of done), `SECURITY.md`, pull request template and issue forms.
- Recommended branch protection for `main` as a GitHub ruleset in `.github/rulesets/main.json`, explained in `docs/BRANCH_PROTECTION.md`.
- Dependabot skips major updates that are deferred on purpose: Spring Boot 4, ESLint 10, TypeScript 7, `@types/node` beyond the Node.js runtime, and Postgres 18.

### Changed

- The API now runs on port 8090 locally, so it can run alongside other tools that use 8080. Hosts can still set the port with `PORT`.

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
