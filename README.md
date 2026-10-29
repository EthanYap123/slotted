# Slotted

A booking system for shared spaces such as study rooms, sports courts and meeting rooms. Students book time slots, facility managers look after rooms, opening hours and blackout dates, and admins manage users and see how the spaces are used.

Key features: conflict-free booking under concurrency, recurring bookings, a waitlist with auto-promotion, QR code check-in, auto-release of no-shows after 15 minutes, email confirmations and an audit log.

**Status:** Phase 0, requirements and design. See [`docs/`](docs/) for the product requirements, architecture and decision records.

Stack: Spring Boot 3 (Java 21), PostgreSQL with Flyway, Spring Security with JWT, React + TypeScript (Vite) with TanStack Query. Everything runs on free tiers ([`docs/COSTS.md`](docs/COSTS.md)).

## License

[MIT](LICENSE)
