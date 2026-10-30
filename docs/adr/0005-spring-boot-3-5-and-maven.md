# ADR-0005: Build the API on Spring Boot 3.5 with Maven

- **Status:** Accepted
- **Date:** 30 October 2026
- **Related:** [ARCHITECTURE.md](../ARCHITECTURE.md), issue #13

## Context

The project brief fixes the stack at Spring Boot 3 on Java 21. When the API was scaffolded (30 October 2026):

- The newest Spring Boot 3 line is **3.5** (3.5.16). Its free open-source support ended on **30 June 2026**; patch releases continue under commercial extended support until 2032, and recent 3.5.x patches are still published to Maven Central.
- Spring Boot **4.1** is the current open-source line (support until July 2027). It moves to Spring Framework 7 and Jakarta EE 11, and some libraries (springdoc, Testcontainers 2) change major versions with it.
- Spring Initializr no longer generates 3.5 projects, so the skeleton is written by hand.

The API also needs a build tool. The team (one student) knows Maven from coursework; Gradle would be new.

## Decision

- Use **Spring Boot 3.5.x** (latest patch, 3.5.16 at the time of writing) with **Java 21** (`<java.version>21</java.version>`, so the compiler targets release 21 even on a newer JDK).
- Use **Maven** through the **Maven Wrapper** (`./mvnw`), pinned to Maven 3.9.11 with a SHA-256 checksum, so every machine and CI runner uses the same Maven without installing it.
- Track Spring Boot 3.5 patch releases with Dependabot (Phase 1 CI) and plan an **upgrade to Spring Boot 4.x after v1.0** (Phase 6 retrospective). Keeping the code on standard Spring APIs (no deprecated features, `jakarta.*` imports, `SecurityFilterChain` beans, `@ServiceConnection` for tests) keeps that upgrade small.

## Consequences

- Matches the brief and the most common Spring Boot version in tutorials, coursework and existing job postings.
- Security fixes in Spring Boot 3.5 dependencies may lag behind 4.x. Mitigated by the OWASP dependency check in Phase 5, Dependabot alerts, and overriding a managed dependency version when a fix exists only in a newer library release.
- An upgrade to 4.x is planned work, not a surprise, and is a good portfolio story (a documented migration with tests as the safety net).
- Maven's XML is verbose, but plugin configuration for Spotless, Checkstyle and JaCoCo is well documented.

## Rejected options

- **Spring Boot 4.1 now:** current and supported, but outside the brief's "Spring Boot 3" and with fewer learning resources for its newest changes.
- **Gradle:** faster incremental builds, but a new tool to learn at the same time as the rest of the stack, for little gain on a single-module project.
