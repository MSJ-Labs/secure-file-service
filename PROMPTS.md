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

## Prompt 2 — Build and infrastructure

~~~~text
Ok Let's move on to the build and infra side. Still no business code and no upload/download endpoints, those come in later prompts.

  As usual, give me a short plan and your questions first, wait for my answer and stop after each step so I can review.

  For the pom, I want Spring Boot 4 with Web MVC and virtual threads, jOOQ, Flyway, PostgreSQL, MapStruct, hypersistence-tsid, Actuator and Lombok. On the test side: JUnit 5, Mockito, ArchUnit and Testcontainers for PostgreSQL and MinIO. Surefire runs the classes with suffix Test and Failsafe the ones with IT suffix, and JaCoCo merges both and fails the build if 80% is not reached. I also need the jooq-codegen profile we described: it starts a Testcontainers PostgreSQL, applies the Flyway migrations and generates the code into the committed package, so that a plain `mvn compile` keeps working without Docker.

  For talking to MinIO, I'm not sure yet which client to use: the AWS SDK v2 or the MinIO Java SDK. I don't know either of them well, so compare them in your plan, especially for streaming uploads of large files (2 GB) when the size isn't known in advance and recommend one. The adapter should work with real S3 later by just changing the config. Also confirm the MinIO image is still available and pin its version.

  About Lombok, I'm fine with it everywhere, but in domain and application only @Getter, @EqualsAndHashCode and @ToString, nothing that bypasses the factory methods (no @Data, @Setter, @Builder,
  @AllArgsConstructor). Add an ArchUnit rule for that and update CLAUDE.md, since it currently says hypersistence-tsid is the only third-party library allowed in the domain. Watch the MapStruct + Lombok processor
  order, and tell me if the Lombok version you pick isn't clearly compatible with Java 25.

  For the first Flyway migration I just want the initial schema: files (with owner_id and status), scan jobs (with the lease and retry fields) and the SHA-256 verdict cache. owner_id stays a plain column, no
  users table, since auth will come later. Follow what ARCHITECTURE.md says about the queue and the cache, and if you think something there should change, say it.

  Then the config. application.properties with datasource, Flyway, virtual threads, S3/MinIO, ClamAV and multipart, everything overridable through environment variables and no secrets in the file, plus an application-local.properties.example. Actuator should expose only health, I need it for the compose healthcheck.

  For docker-compose I want the app, Postgres, MinIO and ClamAV. MinIO buckets should be created at startup, and the services should have healthchecks and wait for each other with service_healthy. The app should wait for Postgres and MinIO (and the bucket creation), but not for ClamAV: it can take minutes to download its signatures, and the queue already handles ClamAV being down. ClamAV needs its config raised for 2 GB (StreamMaxLength, MaxFileSize, MaxScanSize). Add a .env.example too.

  The Dockerfile should be multi-stage, with a layered jar and a non-root user, and JVM flags that make sense in a container: MaxRAMPercentage, and an explicit MaxDirectMemorySize because we're dealing with
  streams. Add a .dockerignore. Keep it simple and explain your choices in a couple of lines.

  For CI, a GitHub Actions workflow on push and pull request: JDK 25, Maven cache, `mvn verify`, and the JaCoCo report as an artifact. No Sonar for now.

  To finish, complete the .gitignore and update the README and ARCHITECTURE.md wherever they still say "next step".

  Give me the exact versions you pick, and if you're unsure about something with Java 25 or Spring Boot 4, flag it instead of guessing. Don't add things I didn't ask for (no k8s, no observability stack). And
  before you tell me a step is done, run what you can, like `mvn compile` and `docker compose config`.
~~~~

## Prompt 2b — MinIO image unavailable (follow-up decision)

~~~~text
Context: while writing docker-compose.yml, the MinIO image pin requested in prompt 2 turned out to be impossible: `minio/minio` and `quay.io/minio/minio` can no longer be pulled, and `bitnami/minio` was removed from Docker Hub (`bitnamilegacy/minio` still exists but is frozen and unpatched).

Author's decision: first check the Bitnami image, and if it is not usable, switch to LocalStack instead of Garage or RustFS, because Testcontainers and the AWS SDK v2 support it natively and the adapter code does not change. Then "go".

Result: LocalStack `4.14.0` (S3 only) in docker-compose.yml and `testcontainers-localstack` in the pom; ClamAV pinned to `1.4.6-debian` (the Alpine tags have no arm64 build); README, ARCHITECTURE.md and CLAUDE.md updated accordingly.
~~~~

## Prompt 3 — Shared module and jOOQ package move

~~~~text
I added a shared module that contains shared DDD domain classes (aggregate, entity, value object, DomainEvent...) . move the infra pers jooq generated tables under that shared folder (same path infra pers jooq) and do the necessary changes for jooq generated classess config
~~~~

## Prompt 4 — Authentication module

~~~~text
I added auth module from another project. Check if there is anyhting needed to update. I have included the flyway migration for users table. update users table in the migration and     
  rename it to user_account. I have updated the properties files as well to add jwt and some other propeties for logging. update readme to explain how to start the app with generation   
  the jwt secret. I added required depenecies in the pom.xml, check that too.  check the format and comments for these files.
~~~~

## Prompt 4b — Authentication review and follow-up decisions

~~~~text
Decisions taken while integrating and reviewing the auth module (author's answers, in order):
- Table name: user_account (not app_user). Every bounded context gets its own PostgreSQL schema and nothing lives in public: tables of the file context go to a schema (first "file", renamed to "storage" because "file.file" read badly) and auth tables to "auth". V1 and V2 were edited in place because nothing is deployed yet.
- jOOQ is generated for both schemas, schema-qualified, no flattening.
- Controllers and handlers are tested without a heavy context where possible: unit tests with Mockito, adapter ITs on a shared Testcontainers PostgreSQL, and a single @SpringBootTest end-to-end test.
~~~~

## Prompt 5 — File upload and scan module

~~~~text
We start now the file upload/scan module.

- http flow : I am thinking of using a semaphore to limit concurrent uploads (otherwise return error). Implement very short transactions: a first one creates the file metadata (status UPLOADING), then direct streaming to s3/quarantine outside any transaction, then a second one sets the status to pending and creates the scan job. Never keep a db connection open during the streaming.

- For workers and scan, fast reservation usign SKIP LOCKED (status scanning, lease unitil now + 30s (or even better if the duration is dynamic and relative to file size, what do you think?)). Then commit immediately.
ALso, clamAV should scan outside of transation with a regular check extending the lease. Worker should check if it is still the owner of the scan via compare and set (CLEAN or INFECTED). No long sql lock (no for update).

-  Domain and application layers must reamin pure with no spring annotation nor framework dependency.
Shceduled tasks and spring profile management (web and workers) live together in the infrastucture layer.

- performances : I would like to implement an adaptive PoolThreadExecutor to limit the number of threads to not exceed the max possible number of simultanious processing.
The check for new files to scan should be exponential so at night when there is no activity, the worker wont process useless checks. The second it finds a new file, it goes back to 0s.
Isolate small and large file scan in seperate queues so no one blocks the other.
Gracefull shutdown to release leases when the containers stops.


To get started with this step, I'd like us to move forward step by step.
~~~~

### Decisions (author's answers)
- Scan lease: 30 s, renewed by a heartbeat, so it does not depend on the file size. The upload deadline is different: nothing renews it while the body streams, so it grows with the declared size (base delay + size / minimum rate, both configurable).
- scan_job gets a `queue` column (SMALL / LARGE) in a new migration; scan concurrency is capped per queue by configuration and workers claim only when a slot is free.
