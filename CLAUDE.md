# CLAUDE.md

Secure file management microservice: streamed uploads into a quarantine zone, asynchronous ClamAV scan, download only when the file is CLEAN. See `ARCHITECTURE.md` for the design and `README.md` for setup.

## Stack
Java 25, Spring Boot 4 (Spring Web MVC, virtual threads), Maven, PostgreSQL, jOOQ (no JPA/Hibernate), Flyway, MapStruct, MinIO (S3), ClamAV. Exact versions live in `pom.xml` only.

Base package: `com.msj.securefile` (groupId `com.msj`, artifactId `secure-file-service`). Single Maven module.

## Commands
| Purpose | Command | Docker needed |
|---|---|---|
| Compile | `mvn compile` | no |
| Unit tests | `mvn test` | no |
| Unit + integration tests + coverage gate (80%) | `mvn verify` | yes |
| Regenerate jOOQ code from Flyway migrations | `mvn -Pjooq-codegen generate-sources` | yes |
| Run infrastructure + app | `docker compose up --build` | yes |

Unit tests are `*Test`, integration tests are `*IT` (Failsafe, Testcontainers). Commands marked as available are defined in `pom.xml` (step 3 of the bootstrap).

## Architecture rules (enforced by ArchUnit at build time)
- `domain`: pure Java. No Spring, no jOOQ, no MapStruct, no Jakarta, no I/O frameworks. The only third-party library allowed is `hypersistence-tsid`.
- `application`: use cases and ports (in/out interfaces). Depends only on `domain` (and the JDK). No Spring annotations.
- `infrastructure`: adapters (web, persistence, storage, scanner, config). May depend on `application` and `domain`. Nothing depends on `infrastructure`.
- Dependencies point inward only: `infrastructure -> application -> domain`.
- MapStruct is used in `infrastructure` only. Never map in the domain.
- Never expose domain or jOOQ types in the web layer: use DTOs.

## Security rules
- Every file belongs to an owner (`UserId`). Every use case receives the caller through the `CurrentUserProvider` port and checks ownership (no IDOR). A file that belongs to someone else behaves as "not found".
- Authentication is out of scope for now (it will be added later). Only the `CurrentUserProvider` port and a test stub exist.
- IDs are TSIDs (`io.hypersistence.tsid.TSID`), exposed in the API as strings. They are guessable: ownership checks are mandatory, never rely on ID secrecy.
- Never load a whole file in memory. Always stream. Never log file content or secrets.

## Database rules
- ALL DDL lives in Flyway migrations (`src/main/resources/db/migration`). Never edit a released migration: add a new one.
- The app never creates or alters schema.
- jOOQ generated code is committed under `com.msj.securefile.infrastructure.persistence.jooq` (so the project compiles without Docker). It is regenerated only with the `jooq-codegen` profile. A test fails if it drifts from the migrations. Never edit generated code by hand.

## Coding guidelines
- Prefer immutable value objects (records) and factory methods that enforce invariants. No anonymous setters on the domain.
- Constructor injection only. No field injection.
- Small classes, one reason to change. Inject a `Clock` and an `IdGenerator` port, never call `Instant.now()` or generate IDs directly in the domain or use cases.
- Fail fast with domain-specific exceptions. No null returns: use `Optional` or exceptions.
- Comments explain why, not what. Match the style of the surrounding code.

## TDD rules (domain and use cases)
1. Write the failing test first and show it.
2. Run it and confirm it fails for the right reason.
3. Implement the minimum code to pass.
4. Refactor with tests green.

- Use cases are tested with Mockito on ports. Domain is tested without mocks.
- Adapters are tested with Testcontainers (PostgreSQL, MinIO).
- Coverage: JaCoCo fails the build below 80% instruction coverage (unit + integration merged). Excluded: jOOQ generated code, MapStruct generated implementations, configuration classes, the Application main class.

## Working rules
- The author always commits. Never run `git commit` or `git push`.
- Work in reviewed steps. Do not invent upload/download endpoints, file types or limits: they come from later prompts.
- Prompts are logged in `PROMPTS.md` only when the author asks.
