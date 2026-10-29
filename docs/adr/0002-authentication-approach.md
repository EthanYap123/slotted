# ADR-0002: Authentication with short-lived JWTs and rotating refresh tokens

- **Status:** Accepted
- **Date:** 29 October 2026
- **Related:** [PRD ST-1, AD-1](../PRD.md#6-user-stories-and-acceptance-criteria), [COSTS.md](../COSTS.md)

## Context

- Three roles (Student, Manager, Admin) with checks on every endpoint, plus manager scope by facility.
- The web app (Vercel) and API (Render) are on **different sites** (`*.vercel.app` and `*.onrender.com`). Browsers increasingly block third-party cookies, so a cookie set by the API is not reliably sent from the web app.
- The API runs on a 512 MB instance that sleeps and restarts often, so in-memory sessions would log everyone out on every cold start.
- Admins must be able to disable a user and have it take effect quickly (AD-1).
- The project brief asks for Spring Security with JWT.

Options considered:

1. **Server sessions** (Spring Session in Postgres) with a session cookie.
2. **Long-lived JWT** stored in `localStorage`.
3. **Short-lived access JWT in memory + rotating refresh token**, stored hashed in Postgres.

## Decision

Option 3.

**Access token**

- JWT signed with HS256 using a 256-bit secret from the environment (`JWT_SECRET`). One service issues and verifies tokens, so an asymmetric key pair adds nothing yet.
- Lifetime 15 minutes. Claims: `sub` (user ID), `role`, `iat`, `exp`, `jti`. Facility assignments are **not** in the token; they are looked up per request so changes apply at once.
- Sent as `Authorization: Bearer <token>`. The web app keeps it **in memory only** (a module variable), never in `localStorage`, so an XSS bug cannot read a stored token later.
- Verified by Spring Security's OAuth2 resource server support (`NimbusJwtDecoder` with the secret key), so no hand-written JWT parsing.

**Refresh token**

- A random 256-bit value, returned in the JSON body on sign-in and refresh. The web app keeps it in `localStorage` so a user stays signed in across tabs and reloads; a cross-site HttpOnly cookie would be safer but is not reliable here (see [below](#why-the-refresh-token-is-in-localstorage)).
- Stored server-side **only as a SHA-256 hash**, with `family_id`, `expires_at` (7 days) and `revoked_at`.
- **Rotation:** every refresh issues a new refresh token and revokes the old one. If a revoked token is used again (a sign of theft), the whole family is revoked and the user must sign in again.
- Sign-out revokes the family. Disabling a user or changing their role revokes all their families, so the change applies within at most 15 minutes (the remaining access-token life). Role checks also reload the user's status from the database on sensitive actions (admin endpoints, booking writes), which closes that gap for disabled users.

**Passwords**

- BCrypt with cost 10 (Spring Security `DelegatingPasswordEncoder`). Argon2 was considered but its memory cost is unwelcome on a 512 MB instance.
- Policy: at least 10 characters, at most 128, rejected if in a bundled list of common passwords. No composition rules (NIST SP 800-63B).
- Sign-in is rate limited per IP and per email (Phase 5), and errors never reveal whether an email exists.

**Authorisation**

- URL rules in the security filter chain for coarse role checks (`/api/v1/admin/**` → ADMIN).
- Method security (`@PreAuthorize`) with a `FacilityAccess` bean for manager scope, for example `@PreAuthorize("@facilityAccess.canManage(#facilityId)")`.
- Ownership checks for student resources (a student can only read or cancel their own bookings) live in the service layer and are covered by authorisation tests in Phase 5.

**CSRF and CORS**

- No cookies are used for authentication, so CSRF protection is disabled for the API. Tokens are sent explicitly in a header, which a cross-site form cannot do.
- CORS allows only the configured web origins (production, the Vercel preview pattern for this project, and `localhost` in development).

## Why the refresh token is in `localStorage`

The safest place for a refresh token is an `HttpOnly; Secure; SameSite` cookie. That only works reliably when the web app and API share a site. Two ways to get there:

- **A custom domain** for both (`app.example.com`, `api.example.com`): costs money, ruled out by [COSTS.md](../COSTS.md).
- **Proxying `/api` through Vercel rewrites** so the browser sees one origin: possible, but every API call then also counts against Vercel's limits, and a one-minute Render cold start may exceed the proxy timeout. This is worth testing in Phase 4.

Until then, the refresh token sits in `localStorage`, and the risk is reduced by: rotation with reuse detection, a 7-day maximum life, a strict Content Security Policy on the web app (no inline scripts, no third-party script origins except Sentry), React's default output escaping, and no use of `dangerouslySetInnerHTML`. If the Vercel proxy works in Phase 4, the refresh token moves to an HttpOnly cookie and this ADR is superseded.

## Consequences

- Survives API restarts and cold starts: nothing about a session lives in API memory.
- One extra table (`refresh_token`) and a small amount of rotation logic, all unit-testable.
- Stateless access checks cost no database call except where a fresh status check is required.
- Sign-out on one device does not sign out others (each sign-in is its own family). An admin "sign out everywhere" revokes all families.
- If `JWT_SECRET` leaks, every token can be forged. It lives only in host dashboards and GitHub Actions secrets, differs between staging and production, and rotating it signs everyone out, which is acceptable.

## Rejected options

- **Server sessions:** would need the same cross-site cookie that is unreliable here, and the brief asks for JWT.
- **Long-lived JWT only:** cannot be revoked when a user is disabled, and a stolen token is valid for its whole life.
