# Architecture

How Slotted is put together, and why. The reasoning behind the main choices is in the [ADRs](adr/README.md); the data model is in [ERD.md](ERD.md) and the API contract in [api/openapi.yaml](api/openapi.yaml).

## 1. Overview

Slotted is a single Spring Boot API (a modular monolith) backed by one PostgreSQL database, with a React single-page app as the only client. Everything runs on free tiers ([COSTS.md](COSTS.md)), which shapes three decisions:

1. **The database guarantees correctness.** Double bookings are prevented by an exclusion constraint, not by application locks or a cache ([ADR-0001](adr/0001-double-booking-prevention.md)).
2. **Nothing depends on the server being awake.** Time-based rules are evaluated when data is read or written; a scheduled GitHub Actions job only tidies up ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)).
3. **No extra infrastructure.** No message broker, no Redis, no separate worker. The email queue is a table (outbox) and the scheduler is GitHub Actions.

## 2. System context

```mermaid
flowchart LR
    student(["Student"])
    manager(["Facility manager"])
    admin(["Admin"])

    subgraph slotted["Slotted"]
        web["Web app<br/>React + TypeScript<br/>(Vercel)"]
        api["API<br/>Spring Boot 3, Java 21<br/>(Render, Docker)"]
        db[("PostgreSQL 17<br/>(Neon)")]
    end

    email["Brevo<br/>transactional email"]
    sentry["Sentry<br/>error tracking"]
    gha["GitHub Actions<br/>CI, CD, scheduled sweep"]
    ghcr["GitHub Container<br/>Registry"]

    student -- "books, checks in by QR" --> web
    manager -- "manages rooms, hours, blackouts" --> web
    admin -- "manages users, views analytics" --> web
    web -- "HTTPS JSON, Bearer JWT" --> api
    api -- "JDBC, TLS" --> db
    api -- "send email (HTTPS API)" --> email
    email -. "confirmation, waitlist, cancellation emails" .-> student
    api -. "errors" .-> sentry
    web -. "errors" .-> sentry
    gha -- "POST /internal/maintenance/sweep" --> api
    gha -- "push image" --> ghcr
    ghcr -- "pull image on deploy" --> api
```

| Element | Responsibility |
| --- | --- |
| Web app | All user interfaces. Static build served from Vercel's CDN. Holds the access token in memory and calls the API directly. Shows a "waking the server" state when the API is cold. |
| API | All business rules, authentication and authorisation, email outbox, audit log. Stateless: any instance can serve any request. |
| PostgreSQL | System of record. Enforces room overlap, foreign keys and value checks. |
| Brevo | Delivers emails queued in the outbox. Optional at runtime: bookings work if it is down. |
| GitHub Actions | CI on pull requests, CD on merge and tags, and the scheduled sweep. |

## 3. Layered architecture

### 3.1 API

The API is organised **by feature**, and each feature has the same layers inside it. Dependencies point inwards: web → application → domain, and infrastructure plugs into interfaces the application defines.

```mermaid
flowchart TB
    subgraph web_layer["Web layer (per feature)"]
        controllers["REST controllers<br/>request/response DTOs, validation,<br/>problem+json errors"]
    end
    subgraph app_layer["Application layer"]
        services["Use-case services<br/>@Transactional, authorisation checks,<br/>settle step, audit, outbox"]
    end
    subgraph domain_layer["Domain layer (no Spring)"]
        rules["Booking rules and policies<br/>BookingRules, BookingTimePolicy,<br/>QuotaPolicy, SeriesPlanner"]
        model["Entities and value objects<br/>Booking, Room, Slot, OpeningHours"]
    end
    subgraph infra_layer["Infrastructure"]
        repos["Spring Data JPA repositories<br/>+ native SQL for availability/analytics"]
        adapters["Adapters<br/>EmailSender (Brevo), QR generator,<br/>Clock, password encoder, JWT"]
    end
    db[("PostgreSQL<br/>Flyway migrations")]

    controllers --> services
    services --> rules
    services --> model
    services --> repos
    services --> adapters
    rules --> model
    repos --> db
```

| Layer | Contains | Rules |
| --- | --- | --- |
| Web | `@RestController`s, DTOs, a global `@RestControllerAdvice` mapping exceptions to problem details | No business logic. DTOs are separate from entities, so the API shape does not leak table structure. |
| Application | One service per use case area (`BookingService`, `WaitlistService`, `CheckInService`, ...) | Owns transactions. Every write: lock student row → settle room → check rules → save → audit → outbox. |
| Domain | Rule classes and entities | Pure Java with an injected `Clock`, so rules are unit-tested without Spring or a database. |
| Infrastructure | Repositories, email, QR, security | Behind interfaces; tests swap them for fakes. |

Planned packages under `api/src/main/java/.../slotted/`:

```
auth/         sign-in, tokens, password policy, security config
user/         users, roles, suspension
facility/     facilities, opening hours, managers
room/         rooms, check-in tokens and QR codes
booking/      bookings, availability, settle step, check-in, series, blackouts, waitlist
audit/        audit log writer, search and retention (ADR-0008)
notification/ email outbox, templates, dispatcher, EmailSender
analytics/    utilisation and attendance report (AD-4)
maintenance/  sweep endpoint
common/       errors, clock, time zone helpers, pagination
```

Module boundaries are checked with ArchUnit tests in Phase 1 (for example, `domain` must not depend on Spring or on `web`).

### 3.2 Web app

```mermaid
flowchart TB
    routes["Routes and pages<br/>(React Router)"]
    components["Components<br/>Timeline, BookingDialog, QR check-in, tables"]
    queries["Server state<br/>TanStack Query hooks per resource"]
    client["API client<br/>typed from openapi.yaml, auth + refresh, problem+json"]
    auth["Auth state<br/>access token in memory"]

    routes --> components
    components --> queries
    queries --> client
    client --> auth
```

- Types for requests and responses are generated from [openapi.yaml](api/openapi.yaml), so the client and server share one contract.
- TanStack Query owns all server data: caching, refetch on focus, and invalidation after a booking, cancel or check-in. After `SLOT_TAKEN` the availability query is refetched so the timeline shows reality.
- The API client retries once after refreshing tokens on `401`, and shows a "waking the server" banner if the first request takes longer than a few seconds.
- All times are formatted in Asia/Singapore ([ADR-0003](adr/0003-timezone-handling.md)).

## 4. Two users book the same slot at the same time

Alice and Bob both see Study Room 3 free from 14:00 to 15:00 and press **Book** at the same moment. Each request runs in its own transaction on its own connection.

```mermaid
sequenceDiagram
    autonumber
    actor Alice
    actor Bob
    participant API as API (two request threads)
    participant DB as PostgreSQL

    par Alice's request
        Alice->>API: POST /bookings {room 3, 14:00-15:00}
        API->>DB: BEGIN
        API->>DB: lock Alice's app_user row (FOR UPDATE)
        API->>DB: lock room 3 (FOR NO KEY UPDATE)
    and Bob's request
        Bob->>API: POST /bookings {room 3, 14:00-15:00}
        API->>DB: BEGIN
        API->>DB: lock Bob's app_user row (FOR UPDATE)
        API->>DB: lock room 3
        Note over DB: Alice holds room 3, so Bob waits here
    end

    API->>DB: Alice: settle overdue no-shows in room 3
    API->>API: Alice: check rules (hours, window, quotas): pass
    API->>DB: Alice: any active booking overlapping 14:00-15:00? none
    API->>DB: Alice: INSERT booking (room 3, [14:00, 15:00), CONFIRMED)
    API->>DB: Alice: COMMIT (room lock released)
    API-->>Alice: 201 Created (CONFIRMED)

    API->>DB: Bob: (gets the room lock) settle, check rules: pass
    API->>DB: Bob: any active booking overlapping 14:00-15:00? Alice's
    API->>DB: Bob: ROLLBACK
    API-->>Bob: 409 SLOT_TAKEN
```

Points to notice:

- **The exclusion constraint is the guarantee** ([ADR-0001](adr/0001-double-booking-prevention.md)). Whatever writes a booking (this API, a later feature, a script), Postgres refuses an overlapping active row with `23P01`, which the API turns into `409 SLOT_TAKEN`.
- **The room lock keeps the normal path orderly** ([ADR-0006](adr/0006-lock-the-room-while-booking.md)). Without it, simultaneous inserts into one room can wait on each other's uncommitted rows until Postgres aborts some of them as deadlocks (`40P01`). The concurrency test for issue #39 found this; with the lock, requests for one room take turns and the loser gets a clean `409`.
- **Locks are always taken in the same order** (the booker, then the room), so booking transactions never wait on each other in a cycle. Bookings in different rooms do not wait at all.
- **The per-student lock** stops one student's two tabs from beating the quota together; it never makes two different students wait for each other.
- `ConcurrentBookingIntegrationTest` proves this against a real Postgres (Testcontainers): 16 students racing for one slot always produce exactly one booking, partly overlapping races never leave overlapping rows, one student's 5 parallel requests never exceed the 3-booking quota, and raw SQL that tries to double book is refused by the constraint.

## 5. Booking lifecycle

Stored statuses are `CONFIRMED`, `CHECKED_IN`, `CANCELLED` and `NO_SHOW`. `COMPLETED` is derived when an active booking's end has passed. Transitions marked *derived* are true at read time before they are written ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)).

```mermaid
stateDiagram-v2
    [*] --> CONFIRMED: book, series occurrence,<br/>or waitlist promotion
    CONFIRMED --> CHECKED_IN: QR check-in<br/>start - 10 min to start + 15 min
    CONFIRMED --> CANCELLED: owner before start,<br/>manager/admin anytime,<br/>blackout or hours change
    CONFIRMED --> NO_SHOW: start + 15 min without check-in<br/>(derived, then settled)
    CHECKED_IN --> COMPLETED: end passes (derived)
    CHECKED_IN --> COMPLETED: ended early, rest released
    CHECKED_IN --> CANCELLED: manager/admin with reason
    NO_SHOW --> [*]
    CANCELLED --> [*]
    COMPLETED --> [*]
```

Every transition writes an audit record. Transitions that free time (`CANCELLED`, `NO_SHOW`, ended early) run waitlist promotion in the same transaction.

## 6. Waitlist promotion

```mermaid
flowchart TD
    freed["Time freed in room R<br/>(cancel, no-show settled, ended early)"] --> find["Find WAITING entries in R whose slot<br/>has not started, oldest first"]
    find --> next{"Next entry?"}
    next -- none --> done(["Done"])
    next -- yes --> free{"Slot completely free now?"}
    free -- no --> next
    free -- yes --> lock["Try that student's row lock without waiting<br/>(busy: entry stays WAITING),<br/>check quotas, suspension, overlap"]
    lock --> eligible{"Eligible?"}
    eligible -- "no: entry stays WAITING" --> next
    eligible -- yes --> book["INSERT booking (source WAITLIST)<br/>entry → PROMOTED<br/>audit + outbox email"]
    book --> next
```

A cancellation, blackout or opening-hours change promotes inside the transaction that freed the time, so they commit together or not at all. A booking attempt first settles and promotes in a transaction of its own, so its own failure cannot undo the promotion. Promotion never waits for a student's lock while it holds the room ([ADR-0007](adr/0007-promote-from-the-waitlist-without-waiting.md)). The exclusion constraint still applies to promoted bookings, so promotion can never create an overlap even if two cancellations race.

A waitlist request names only a slot, so a promoted booking is for one person and has no purpose.

## 7. Time-based rules and the scheduled sweep

```mermaid
flowchart LR
    subgraph request["Any request"]
        read["Reads<br/>use effective status:<br/>no-show and expiry are<br/>computed from now"]
        write["Writes<br/>settle the room first,<br/>then apply the change"]
    end
    subgraph schedule["GitHub Actions schedule"]
        cron["Hourly (prod), twice daily (staging)<br/>POST /internal/maintenance/sweep<br/>X-Maintenance-Key"]
    end
    sweep["Sweep<br/>settle all rooms, expire waitlist,<br/>send outbox, purge old rows"]
    outbox[("email_outbox")]
    brevo["Brevo"]

    write --> outbox
    cron --> sweep
    sweep --> outbox
    outbox -- "after commit, and retried by sweep" --> brevo
```

The workflow is `.github/workflows/sweep.yml`. It needs the `API_URL` repository variable and the `MAINTENANCE_KEY` secret, set when the API is deployed in Phase 4; until then each run finds them missing and does nothing.

If the sweep is late or never runs, every answer the API gives is still correct; only no-show emails and database bookkeeping wait. Full reasoning and the failure table are in [ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md).

## 8. Environments and delivery

```mermaid
flowchart LR
    dev["Developer laptop<br/>API + web locally,<br/>Postgres in Docker Compose"]
    pr["Pull request"]
    ci["CI<br/>API tests (Testcontainers),<br/>web lint/type/test/build,<br/>gitleaks"]
    main["main branch"]
    tag["Release tag v*"]

    subgraph staging["Staging"]
        sapi["Render service<br/>slotted-api-staging"]
        sdb[("Neon branch<br/>staging")]
        sweb["Vercel preview"]
    end
    subgraph prod["Production"]
        papi["Render service<br/>slotted-api"]
        pdb[("Neon main branch")]
        pweb["Vercel production"]
    end

    dev --> pr --> ci --> main
    main -- "build image, push to GHCR,<br/>deploy hook" --> sapi
    main --> sweb
    tag -- "same image digest,<br/>deploy hook" --> papi
    tag --> pweb
    sapi --> sdb
    papi --> pdb
```

- The same Docker image (by digest) is promoted from staging to production; it is never rebuilt for production.
- Flyway runs migrations on startup, before the app accepts traffic. Migrations are written to be backward compatible with the previous release so a rollback of the app does not need a database rollback.
- Health: Spring Boot Actuator `/actuator/health` (liveness and readiness), used by Render's health check.
- Swagger UI is enabled on staging only.
- Details, secrets and rollback steps come in `docs/DEPLOYMENT.md` (Phase 4).

## 9. Cross-cutting concerns

| Concern | Approach |
| --- | --- |
| Authentication | Short-lived JWT access tokens, rotating hashed refresh tokens ([ADR-0002](adr/0002-authentication-approach.md)) |
| Authorisation | Role rules in the security filter chain, `@PreAuthorize` with a facility-scope bean, ownership checks in services; authorisation tests per endpoint in Phase 5 |
| Validation | Bean Validation on DTOs for shape; domain rule classes for business rules, each with its own error code ([errors.md](api/errors.md)) |
| Errors | One `@RestControllerAdvice` producing RFC 9457 problem details with `code` and `requestId` |
| Time | `timestamptz` and `Instant` everywhere, rules in Asia/Singapore via injected `Clock` ([ADR-0003](adr/0003-timezone-handling.md)) |
| Concurrency | Exclusion constraint for rooms (the guarantee), room and per-student row locks taken in a fixed order (ADR-0006), also taken by blackouts and opening-hour changes so they cannot miss a booking in progress, optimistic `version` for edits |
| Audit | Written by services in the same transaction as the change; booking changes go through `BookingRecorder`, which records the change and queues its email together ([ADR-0008](adr/0008-record-audit-changes-in-the-services.md)) |
| Email | Transactional outbox, dispatched after commit and retried by the sweep |
| Logging | JSON logs with `requestId`, user ID and route; no personal data beyond IDs |
| Monitoring | Sentry for API and web errors (expected 4xx not reported), Actuator health |
| Memory | JVM sized for 512 MB: small heap, Serial GC, small Tomcat and Hikari pools, lazy initialisation where safe (Phase 4) |
| Database connections | Hikari pool with no idle connections, so Neon can scale to zero ([COSTS.md](COSTS.md)) |

## 10. Repository layout (from Phase 1)

```
api/                Spring Boot application (Maven)
web/                React + TypeScript (Vite)
docs/               PRD, architecture, ERD, ADRs, API contract, costs
.github/workflows/  CI, CD and the scheduled sweep
docker-compose.yml  Local Postgres
```
