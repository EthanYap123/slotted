Closes #

## What and why

<!-- One or two sentences: what changes, and which PRD rule, story or ADR it serves. -->

## How I tested it

<!-- Commands run, tests added, and anything checked by hand (screens, sizes, time zones). -->

## Checklist

- [ ] Title follows Conventional Commits (`feat(booking): ...`, `fix(api): ...`)
- [ ] Tests cover the change (unit tests for rules, integration tests for database behaviour)
- [ ] `./mvnw verify` and/or `npm run lint && npm run typecheck && npm test` pass locally
- [ ] `docs/api/openapi.yaml` updated if the API changed
- [ ] Docs, ADRs and `CHANGELOG.md` updated if behaviour or decisions changed
- [ ] No secrets, real personal data or `.env` files committed
- [ ] UI changes checked at phone width (375px) with keyboard only
