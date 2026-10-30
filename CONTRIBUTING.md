# Contributing

How work gets into Slotted. The same rules apply to the owner and to anyone else.

## Set up

You need Java 21 or newer, Node.js 22 or newer, and Docker (for the local database and the API's integration tests). Then follow [Run it locally](README.md#run-it-locally).

Useful commands:

| Task | Command |
| --- | --- |
| All API checks (format, Checkstyle, tests, coverage, ArchUnit) | `cd api && ./mvnw verify` |
| API checks without Docker (integration tests and the coverage gate are skipped) | `cd api && ./mvnw verify -DskipCoverageCheck` |
| Fix Java formatting | `cd api && ./mvnw spotless:apply` |
| All web checks | `cd web && npm run lint && npm run format:check && npm run typecheck && npm test && npm run build` |
| Fix web formatting | `cd web && npm run format` |

On Windows, use `mvnw.cmd` instead of `./mvnw`.

## Workflow

1. **Start from an issue.** Every change has an issue with "Done when" criteria. Phase work uses the `phase-N` label and milestone.
2. **Branch from an up-to-date `main`**, one branch per issue:

   ```bash
   git switch main && git pull
   git switch -c feat/42-waitlist-promotion
   ```

   Branch names are `<type>/<issue>-<short-description>`, using the commit types below.
3. **Commit with [Conventional Commits](https://www.conventionalcommits.org/)** (see below). Small, focused commits are easier to review.
4. **Open a pull request** that says `Closes #<issue>`, fill in the template, and keep it to one issue.
5. **CI must be green.** `CI result` is the required check ([branch protection](docs/BRANCH_PROTECTION.md)).
6. **Squash merge.** The PR title becomes the commit on `main`, so it must also follow Conventional Commits. The branch is deleted automatically.

Never commit directly to `main` or force-push to it; the ruleset blocks both.

## Commit messages

```
<type>(<optional scope>): <summary in the imperative, lower case, no full stop>

<optional body: why, not what>

Refs #<issue>
```

| Type | Use for |
| --- | --- |
| `feat` | New behaviour users can see |
| `fix` | A bug fix |
| `docs` | Documentation only |
| `test` | Adding or fixing tests only |
| `refactor` | Code change with no behaviour change |
| `perf` | Performance improvement |
| `build` | Build tool or dependency changes |
| `ci` | GitHub Actions and Dependabot |
| `chore` | Anything else (scaffolding, housekeeping) |

Scopes in use: `api`, `web`, `booking`, `waitlist`, `auth`, `room`, `facility`, `admin`, `deps`. Add `!` after the type for a breaking API change, for example `feat(api)!: rename booking status field`.

## Code conventions

### API (Java)

- Package by feature, with layers inside: `dev.ethanyap.slotted.<feature>.{web,application,domain,infrastructure}` ([ARCHITECTURE.md](docs/ARCHITECTURE.md#31-api)). ArchUnit enforces the layer rules.
- Business rules live in `domain` as plain Java with no Spring, and take a `Clock` for the current time. Calling `Instant.now()` or similar fails the build ([ADR-0003](docs/adr/0003-timezone-handling.md)).
- Constructor injection only.
- Every schema change is a new Flyway migration (`V<n>__<description>.sql`). Never edit a migration that has been merged.
- Errors are RFC 9457 problem details with a code from [docs/api/errors.md](docs/api/errors.md).
- Change [`docs/api/openapi.yaml`](docs/api/openapi.yaml) first, in the same pull request as the code.

### Web (TypeScript)

- Strict TypeScript; no `any`. ESLint runs with zero warnings allowed.
- Server data goes through TanStack Query hooks, never `useEffect` + `fetch`.
- Show all times in Asia/Singapore, whatever the browser's zone.
- Accessible by default: labelled controls, keyboard support, visible focus. `eslint-plugin-jsx-a11y` catches the basics.

## Tests

- Unit tests for every business rule, including the boundaries (for example exactly 15 minutes after the start).
- Integration tests use Testcontainers with real Postgres 17, never H2, because the design relies on Postgres features ([ADR-0001](docs/adr/0001-double-booking-prevention.md)).
- Coverage must stay above **80% of lines and 70% of branches** for the API. CI posts the numbers in the job summary.
- Web components are tested with Testing Library through what the user sees (roles and text), not implementation details.

## Definition of done

A change is done when:

- [ ] The issue's "Done when" criteria are met
- [ ] Tests cover it and CI is green
- [ ] The OpenAPI contract, docs, ADRs and `CHANGELOG.md` (under **Unreleased**) are updated where relevant
- [ ] UI changes work at 375px wide, with a keyboard, in light mode
- [ ] No secrets, personal data or real `.env` files are committed
- [ ] It is squash-merged and the issue is closed

## Secrets

Real values live only in git-ignored `.env` files, GitHub Actions secrets and the hosting dashboards. Copy the `.env.example` files to start. gitleaks scans every pull request; if it flags something real, rotate the secret first, then remove it from the branch.

## Reporting security issues

See [SECURITY.md](SECURITY.md).
