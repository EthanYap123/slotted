# Costs and free-tier limits

Slotted must cost nothing to build and run: free tiers only, no credit card, no paid domain. This page records the limits of every service the project uses, what happens when a limit is hit, and how the design stays inside them.

**Last checked:** 29 October 2026. Free tiers change often, so check the linked pages again before adding or upgrading a service, and update this page in the same pull request.

## Summary

| Service | Used for | Monthly cost | Card needed? |
| --- | --- | --- | --- |
| [Render](#render-api-hosting) (Free web service) | Spring Boot API as a Docker image, staging and production | $0 | Not for the Free plan in general, but Render may ask some accounts for card verification. **Open question, see below.** |
| [Neon](#neon-postgres) (Free) | PostgreSQL for staging and production | $0 | No |
| [Vercel](#vercel-web-hosting) (Hobby) | React web app, preview and production | $0 | No |
| [Brevo](#brevo-transactional-email) (Free) | Transactional email | $0 | No |
| [Sentry](#sentry-error-tracking) (Developer) | Error tracking for API and web | $0 | No |
| [GitHub Actions + GHCR](#github-actions-and-container-registry) | CI, CD, scheduled cleanup, Docker images | $0 | No |

## Render (API hosting)

Source: [render.com/docs/free](https://render.com/docs/free), [Instance types](https://render.com/docs/compute-plans), [Deploying an image](https://render.com/docs/deploying-an-image)

| Limit | Value |
| --- | --- |
| Instance size | 512 MB RAM, 0.1 CPU |
| Instance hours | 750 free hours per workspace per month, shared by all free services. Hours stop counting while a service is asleep. Unused hours do not roll over. |
| Sleep | Spins down after 15 minutes with no inbound traffic |
| Cold start | About one minute; browsers see a Render loading page meanwhile |
| Scaling | One instance, no persistent disk, local files lost on redeploy or spin-down, no shell access |
| Bandwidth and build minutes | Counted against the workspace's included amounts. With no payment method on file, going over suspends free services (bandwidth) or disables builds (build minutes) until next month. Nothing is charged. |
| Docker images | Public images from any reachable registry (GHCR included) can be deployed without credentials. Image must be `linux/amd64`. Deploys are triggered by a deploy hook URL; image services do not redeploy on their own when a tag changes. |
| Free Postgres | 1 GB, **expires after 30 days**, no backups. Not used: Neon is used instead. |

**What happens at the limit:** when the 750 hours run out, every free web service in the workspace is suspended until the next month.

**How Slotted stays inside it**

- Staging and production are two free services, so they share 750 hours. Both sleep when idle, so real usage is far lower than 2 × 720 hours.
- The scheduled cleanup job wakes the API, and each wake keeps it up for about 15 minutes. Production runs the job hourly (about 24 × 15 min = 6 hours a day, ~180 hours a month). Staging runs it twice a day. Together with demo traffic this leaves a wide margin.
- The JVM is tuned for 512 MB (small heap, Serial GC, limited thread pools), and startup is kept lean so cold starts stay short. Details come in Phase 4.
- Correctness never depends on the server being awake: see [ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md).

## Neon (Postgres)

Source: [neon.com/pricing](https://neon.com/pricing)

| Limit | Value |
| --- | --- |
| Storage | 1 GB per project, 20 GB across the account |
| Compute | 100 CU-hours per project per month. Branches in a project share it. |
| Projects / branches | 100 projects, 10 branches per project |
| Compute size | Autoscaling up to 2 CU (8 GB RAM) |
| Scale to zero | Suspends after 5 minutes idle; always on, cannot be turned off |
| Data transfer | 5 GB public network transfer per project |
| Point-in-time restore | 6 hours of history (1 GB limit) |
| Card | Not required; the Free plan is permanent, not a trial |

**How Slotted stays inside it**

- Production uses the main branch and staging uses a Neon branch of the same project, as planned. They share 100 CU-hours. If that becomes tight, staging moves to its own Neon project (allowed on Free) without any code change.
- The API's connection pool keeps **no idle connections** (`minimumIdle=0`, short idle timeout), so Neon can scale to zero a few minutes after the API goes quiet. An open idle connection would keep the compute running and burn CU-hours.
- Seeded demo data is tiny (well under 50 MB). The audit log and email outbox are trimmed by the cleanup job so storage stays small.
- 6 hours of restore history is short, so `docs/DEPLOYMENT.md` (Phase 4) will cover taking a `pg_dump` before risky migrations.

## Vercel (web hosting)

Source: [vercel.com/docs/plans/hobby](https://vercel.com/docs/plans/hobby)

| Limit | Value |
| --- | --- |
| Use | Non-commercial, personal use only. Slotted is a portfolio project, so this fits. |
| Fast data transfer | 100 GB per month |
| CDN requests | 1,000,000 per month |
| Function invocations | 1,000,000 per month (Slotted serves static files only, so it uses none) |
| Deployments | 100 per day |
| Build machine | 2 vCPUs, 8 GB memory |
| Projects | 200 |
| Preview deployments | Included, one per pull request |
| Card | Not required |

**What happens at the limit:** usage is paused, in most cases for 30 days. A static Vite build is a few hundred kilobytes, so the limits are far away.

## Brevo (transactional email)

Sources: Brevo free plan as described in [Brevo pricing guides](https://dev.to/techsifted/brevo-pricing-2026-every-plan-explained-with-the-hidden-costs-4oe3) and [automationatlas.io (verified July 2026)](https://automationatlas.io/tools/brevo/); sender rewriting from the [Brevo community forum](https://community.brevo.com/t/email-being-sent-with-t-sender-sib-com/783). Brevo's own pricing page could not be read automatically, so confirm these when signing up in Phase 3.

| Limit | Value |
| --- | --- |
| Volume | 300 emails per day |
| Access | REST API and SMTP relay, transactional email included on Free |
| Branding | Brevo badge in the footer, cannot be removed on Free |
| Card | Not required |
| Own domain | **Not required to send**, but without a verified domain Brevo rewrites the sender address to one of its own shared domains (for example `t-sender-sib.com`), shows a "via" label, and deliverability is lower. A Gmail address cannot be verified as the sender. |

**Why Brevo:** most free email APIs (Resend, Postmark, Mailgun trials) only send to your own address until you verify a domain you own. Brevo still delivers to any recipient without one, which is what a public demo needs.

**How Slotted stays inside it**

- Emails are written to an outbox table and sent by a dispatcher, so a failed or rate-limited send is retried later instead of breaking a booking ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)).
- A daily cap in config (default 250) stops sending before Brevo's limit and leaves the rest queued.
- Demo accounts use addresses on a reserved domain (`@example.com`), which are skipped, not sent.
- The sender sits behind an `EmailSender` interface. A Gmail SMTP sender (app password, no domain) is the fallback if Brevo stops working; its limits must be checked and recorded here before it is used.

## Sentry (error tracking)

Source: [sentry.io/pricing](https://sentry.io/pricing/)

| Limit | Value |
| --- | --- |
| Errors | 5,000 |
| Tracing | 5,000,000 spans |
| Session replays | 50 |
| Users | 1 |
| Retention | 30 days |
| Card | Not required for the Developer plan |

**How Slotted stays inside it:** trace sampling is kept low (for example 5%), session replay is off, and expected errors (409 slot taken, 4xx validation) are not reported. Sentry drops events over quota, which never affects the app.

## GitHub Actions and container registry

Sources: [Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions), [Packages billing](https://docs.github.com/en/billing/concepts/product-billing/github-packages), [Schedule event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule)

| Limit | Value |
| --- | --- |
| Actions minutes | Free and unmetered on standard GitHub-hosted runners in public repositories |
| GHCR | Free for public packages; container storage and bandwidth "currently free" with at least a month's notice before any change |
| Scheduled workflows | Shortest interval 5 minutes; runs can be delayed or dropped at busy times (especially on the hour); **disabled automatically after 60 days with no repository activity** |
| Card | Not required |

**How Slotted stays inside it:** the repository stays public. Scheduled runs use an off-the-hour minute (for example `17 * * * *`) to reduce delays. Because a scheduled run may be late, missed or disabled, it only tidies up; nothing depends on it running on time ([ADR-0004](adr/0004-lazy-evaluation-of-time-based-rules.md)).

## Open questions

1. **Render card verification.** Render's Free plan does not require a card on paper, but recent reports and a Render staff reply say some new accounts are asked to verify with a card (a temporary $1 authorisation that is cancelled). This must be checked when the Render account is created in Phase 4. If Render asks for a card, work stops and a no-card alternative is chosen with Ethan before going further.
2. **Brevo sender address.** Sending without an owned domain works, but mail shows a shared Brevo sender. Accepted for a portfolio demo; revisit only if a free domain becomes available.

## Not used, and why

| Option | Reason |
| --- | --- |
| Render Free Postgres | Deleted 30 days after creation |
| A custom domain | Costs money; `*.onrender.com` and `*.vercel.app` are used instead |
| Render cron jobs / background workers | Not available on the Free plan; GitHub Actions schedules call a protected endpoint instead |
