# Architecture decision records

Each record explains one significant decision: the context, what was decided, and the consequences. Records are never rewritten after they are accepted; a later record supersedes them instead.

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-double-booking-prevention.md) | Prevent double bookings with a Postgres exclusion constraint | Accepted |
| [0002](0002-authentication-approach.md) | Authentication with short-lived JWTs and rotating refresh tokens | Accepted |
| [0003](0003-timezone-handling.md) | Store instants in UTC, apply rules in Asia/Singapore | Accepted |
| [0004](0004-lazy-evaluation-of-time-based-rules.md) | Evaluate time-based rules lazily; a scheduled job only tidies up | Accepted |
| [0005](0005-spring-boot-3-5-and-maven.md) | Build the API on Spring Boot 3.5 with Maven | Accepted |

## Adding a record

Copy [template.md](template.md) to the next number, for example `0005-short-title.md`, fill it in, add it to the table above and open a pull request.
