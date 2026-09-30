# PROMPTS

Log of significant prompts, in English. Only meaningful prompts are logged (no tiny checks or fixes). New entries are added when the author asks for it.

## Prompt 1 — Project foundations

~~~~text
I am starting a secure file management microservice. This first prompt covers ONLY the project foundations. Detailed upload/download specifications (file types, limits, endpoints) will come in later prompts, so do not invent them.

### Working mode
- Before generating anything, reply with a short plan and a list of open questions. Wait for my answer.
- Work in the steps below and STOP after each one for my review.
- State explicitly the exact versions you choose (Spring Boot, jOOQ, Flyway, Testcontainers, ArchUnit, plugins) and flag anything you are unsure exists or is compatible with Java 25 / Spring Boot 4.

### Tech Stack
- Java 25, Spring Boot 4, Maven
- PostgreSQL + jOOQ (no JPA/Hibernate)
- Flyway: ALL DDL lives in Flyway migrations (src/main/resources/db/migration). No schema created by the app or by hand.
- jOOQ code generation runs from the Flyway migrations against a Testcontainers PostgreSQL at build time.
- Strict Hexagonal Architecture (Ports and Adapters), enforced at build time with ArchUnit (domain depends on neither Spring nor jOOQ; application depends only on domain).
- Testing: JUnit 5, Mockito, Testcontainers (PostgreSQL, MinIO), JaCoCo

### Security (scope of this phase)
- Authentication (login/password, JWT in HttpOnly SameSite=Strict cookies access_token + refresh_token) will be ADDED BY ME LATER from an existing implementation. Do NOT implement it.
- Only create a port `CurrentUserProvider` in the application layer (returns a UserId) with a test stub implementation.
- Design from day one for per-user ownership: a user can only access their own files (no IDOR). The domain model must carry the owner.

### Target workflow (for context, not implemented in this step)
1. Upload (up to 2 GB, streamed, never fully in memory, SHA-256 computed on the fly) into a QUARANTINE zone.
2. A scan job is inserted in PostgreSQL and consumed by workers using FOR UPDATE SKIP LOCKED.
3. Worker streams the file to ClamAV via TCP INSTREAM.
4. SHA-256 verdict cache to skip re-scans (note: a CLEAN verdict can become stale after signature updates; INFECTED can be final. Document the chosen strategy in ARCHITECTURE.md).
5. Download only if status == CLEAN.
- File status state machine: PENDING -> SCANNING -> CLEAN | INFECTED | FAILED.
- Job queue: document lease/timeout for dead workers, retry count, and behaviour when ClamAV is down.

### Storage
- Define a `FileStoragePort` with two logical zones: quarantine and clean.
- Adapter: S3-compatible (MinIO). MinIO is part of docker-compose and of the Testcontainers integration tests.

### Infrastructure (phase 1)
- docker-compose.yml: App, Postgres, MinIO, ClamAV. 
- ClamAV config must raise StreamMaxLength, MaxFileSize and MaxScanSize to support 2 GB.
- Prometheus, Grafana, Loki, Tempo and Micrometer metrics (virtual threads, direct memory, scan duration) come in a LATER phase. Only mention them in ARCHITECTURE.md as planned.

### Quality rules
- Strict TDD for domain and use cases: write the failing tests first, show them, then implement.
- jacoco-maven-plugin fails the build under 80% instruction coverage. Exclude jOOQ generated code, configuration classes and the Application main class.

### Mandatory documentation (create BEFORE any business code)
- CLAUDE.md: build/test commands, coding guidelines, architecture rules, TDD rules.
- ARCHITECTURE.md: hexagonal boundaries, container-aware JVM and Direct Buffers, stream handling, testing strategy, scaling strategy, scan-cache strategy, queue failure handling.
- README.md: prerequisites, quickstart, test commands, `docker compose up`.
- PROMPTS.md: log of prompts. Log this initial prompt first, verbatim.

### Steps (stop after each)
1. Plan + open questions.
2. The four documentation files.
3. pom.xml (jOOQ, Flyway, Spring Web, Testcontainers, Mockito, ArchUnit, JaCoCo 80%) + docker-compose.yml (App, Postgres, MinIO, ClamAV) + Flyway V1 migration for the initial schema.
4. Hexagonal package structure (domain, application, infrastructure) + ArchUnit test.
5. Domain unit tests first, then pure Java domain (SecureFile, FileStatus, User/UserId) and port interfaces (including FileStoragePort, CurrentUserProvider).
~~~~
