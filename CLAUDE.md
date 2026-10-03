# CLAUDE.md

Secure file management microservice: streamed uploads into a quarantine zone, asynchronous ClamAV scan, download only when the file is CLEAN. See `ARCHITECTURE.md` for the design and `README.md` for setup.

## Stack
Java 25, Spring Boot 4 (Spring Web MVC, virtual threads), Maven, PostgreSQL, jOOQ (no JPA/Hibernate), Flyway, MapStruct, S3-compatible storage (AWS SDK v2; LocalStack in compose and tests), ClamAV. Exact versions live in `pom.xml` only.

Base package: `com.msj.securefile` (groupId `com.msj`, artifactId `secure-file-service`). Single Maven module.

## Commands
| Purpose | Command | Docker needed |
|---|---|---|
| Compile | `mvn compile` | no |
| Unit tests | `mvn test` | no |
| Unit + integration tests + coverage gate (80%) | `mvn verify` | yes |
| Regenerate jOOQ code from Flyway migrations | `mvn -Pjooq-codegen generate-sources` | yes |
| Run infrastructure + app | `docker compose up --build` | yes |

Unit tests are `*Test`, integration tests are `*IT` (Failsafe, Testcontainers). Maven commands are defined in `pom.xml`.

## Package layout
Feature-first: `com.msj.securefile.<context>.{domain,application,infrastructure}` per bounded context (today `auth`; the file context comes later), plus `shared` (shared DDD building blocks in `shared.domain`, generated jOOQ code in `shared.infrastructure.persistence.jooq`) and `config` (Spring configuration). The `auth` context also has `api`, its inbound web adapter (controllers, DTOs, exception handler).

## Architecture rules (enforced by ArchUnit in `ArchitectureTest`)
- `domain`: pure Java. No Spring, no jOOQ, no MapStruct, no Jakarta, no I/O frameworks. The only third-party libraries allowed are `hypersistence-tsid` and Lombok, restricted as below.
- `application`: command/query handlers and ports (`port.out` interfaces for repositories, `PasswordHasher`, `TokenService`, ...). Depends only on `domain` (and the JDK). Handlers are the inbound API: there are no `*UseCase` interfaces, controllers call the handlers. Handlers return result objects (`UserProfile`), never domain entities. The only Spring types allowed are `@Service` and `@Transactional` (composition and transactions, no logic), plus SLF4J logging; web, security and persistence types are forbidden.
- Lombok: in `domain` only `@Getter`, `@EqualsAndHashCode` and `@ToString`. In `application` also `@RequiredArgsConstructor` (constructor injection) and `@Slf4j`. Never `@Data`, `@Setter`, `@Builder` or `@AllArgsConstructor`/`@NoArgsConstructor` there: they bypass the factory methods that enforce invariants. Since Lombok annotations are source-retention, ArchUnit detects the forbidden generated members through `@lombok.Generated` (see `lombok.config`). Anywhere else Lombok is allowed.
- Aggregates are created with a factory method (`User.register`) and rebuilt from storage with `reconstitute`. No public builder.
- `infrastructure` (and `api`): adapters (web, persistence, storage, scanner, security). May depend on `application` and `domain`. `domain` and `application` never depend on `infrastructure`, `api` or `config`.
- jOOQ types stay in `infrastructure`.
- MapStruct is used in `infrastructure` only. Never map in the domain.
- Never expose domain or jOOQ types in the web layer: use DTOs.

## Security rules
- Authentication (`auth` context): JWT (HS512) in HttpOnly SameSite=Strict `access_token` / `refresh_token` cookies, BCrypt passwords, refresh tokens stored hashed and revocable, account lock after 5 failed logins. Domain exceptions carry fixed messages (no user enumeration) and are mapped to RFC 9457 problem responses in `AuthExceptionHandler`.
- Every file belongs to an owner (`UserId`). Every use case receives the caller through the `CurrentUserProvider` port and checks ownership (no IDOR). A file that belongs to someone else behaves as "not found". The file context does not exist yet; `CurrentUserProvider` will be backed by the authenticated principal.
- IDs are TSIDs (`io.hypersistence.tsid.TSID`), exposed in the API as strings. They are guessable: ownership checks are mandatory, never rely on ID secrecy.
- Never load a whole file in memory. Always stream. Never log file content or secrets.

## Database rules
- ALL DDL lives in Flyway migrations (`src/main/resources/db/migration`). Never edit a released migration: add a new one. (Exception, once: V1 was edited before any deployment to introduce the schemas. From the first deployed environment on, the rule has no exception.)
- One PostgreSQL schema per bounded context (`storage`, `auth`). Nothing lives in `public`. Tables are schema-qualified in SQL and in the generated jOOQ code.
- The app never creates or alters schema.
- jOOQ generated code is committed under `com.msj.securefile.shared.infrastructure.persistence.jooq` (so the project compiles without Docker). It is regenerated only with the `jooq-codegen` profile. A test fails if it drifts from the migrations. Never edit generated code by hand.

## Coding guidelines
- Prefer immutable value objects (records) and factory methods that enforce invariants. No anonymous setters on the domain.
- Constructor injection only. No field injection.
- Small classes, one reason to change. Inject a `Clock` (bean in `config.ClockConfig`, UTC) and pass `now` into domain methods; never call `Instant.now()`/`LocalDateTime.now()` without a clock. IDs come from `UserId.generate()` for now; the `IdGenerator` port arrives with the file context.
- Fail fast with domain-specific exceptions. No null returns: use `Optional` or exceptions.
- Comments explain why, not what. Match the style of the surrounding code.

## TDD rules (domain and use cases)
1. Write the failing test first and show it.
2. Run it and confirm it fails for the right reason.
3. Implement the minimum code to pass.
4. Refactor with tests green.

- Use cases are tested with Mockito on ports. Domain is tested without mocks.
- Adapters are tested with Testcontainers (PostgreSQL, LocalStack S3).
- Coverage: JaCoCo fails the build below 80% instruction coverage (unit + integration merged). Excluded: jOOQ generated code, MapStruct generated implementations, configuration classes, the Application main class.

## Working rules
- The author always commits. Never run `git commit` or `git push`.
- Work in reviewed steps. Do not invent upload/download endpoints, file types or limits: they come from later prompts.
- Prompts are logged in `PROMPTS.md` only when the author asks.
