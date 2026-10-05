# ARCHITECTURE

## 1. Overview

```mermaid
flowchart LR
    C[Client] -->|streamed upload| API[REST API - Spring MVC]
    API -->|stream + SHA-256| Q[(Quarantine zone - S3 (LocalStack in dev/tests))]
    API -->|file UPLOADING, then PENDING + scan job| DB[(PostgreSQL)]
    W[Scan workers] -->|FOR UPDATE SKIP LOCKED| DB
    W -->|read| Q
    W -->|INSTREAM over TCP| AV[ClamAV clamd]
    W -->|CLEAN: copy| CL[(Clean zone - S3 (LocalStack in dev/tests))]
    C -->|download if CLEAN| API
    API --> CL
```

File status state machine (one global status, implemented in `SecureFile`):
`UPLOADING -> PENDING -> SCANNING -> CLEAN | INFECTED | SCAN_FAILED`, plus `UPLOADING -> UPLOAD_FAILED`.

| Transition | Trigger |
|---|---|
| (creation) -> UPLOADING | The row is created first, in a short transaction, with an upload deadline; the binary is streamed afterwards |
| UPLOADING -> PENDING | Body stored, digest known, real size equals the declared size; the scan job is created in the same transaction |
| UPLOADING -> UPLOAD_FAILED | Timeout, client abort, storage error or wrong size (the reason is kept) |
| PENDING -> SCANNING | A worker leases the scan job |
| SCANNING -> CLEAN | ClamAV verdict OK (or a valid cached CLEAN verdict) |
| SCANNING -> INFECTED | ClamAV found a signature (or cached INFECTED verdict); the signature name is kept |
| SCANNING -> SCAN_FAILED | Attempts exhausted (see section 8) |
| SCANNING -> PENDING | ClamAV unavailable, worker shutdown or lease expired: job requeued |

Any other transition is rejected by the domain (`InvalidFileTransitionException`) and records nothing. `CLEAN`, `INFECTED` and `SCAN_FAILED` are final for the automatic processing. Download is allowed only in `CLEAN`.

## 2. Hexagonal boundaries

The layout is feature-first: one package per bounded context, each split in the hexagonal layers.

```
com.msj.securefile
├── auth                          implemented: registration, login, refresh, logout, profile
│   ├── domain                    pure Java: User aggregate, UserId, token hashing, domain exceptions
│   ├── application               command/query handlers, result objects (UserProfile)
│   │   └── port.out              UserRepository, RefreshTokenRepository, PasswordHasher, TokenService
│   ├── api                       inbound web adapter: controllers, DTOs, AuthExceptionHandler
│   └── infrastructure
│       ├── adapters.persistence  jOOQ repositories
│       └── security              JWT provider, cookies, filter, BCrypt hasher
├── storage                       file upload, quarantine, scan: domain, application and infrastructure implemented
│   │                             (see "Storage domain" below); the api and the worker runtime are planned
│   ├── application               command/query handlers, result objects
│   │   └── port.out              FileRepository, ScanJobRepository, FileStoragePort, VirusScanner, IdGenerator,
│   │                             CurrentUserProvider, Actor
│   └── infrastructure
│       └── adapters              persistence (jOOQ, audit), storage (S3), scanner (ClamAV), id, security
├── shared
│   ├── domain                    DDD building blocks: AggregateRoot, VersionedAggregateRoot, Entity, ValueObject, DomainEvent
│   └── infrastructure.persistence.jooq   generated jOOQ code (committed), one package per schema
└── config                        Spring configuration (security, OpenAPI, Clock)
```

The handlers are the inbound API of the application layer: controllers call them directly, so there are no `*UseCase` interfaces (a single implementation per operation, no second adapter that needs the abstraction). Handlers return result objects, never domain entities. The outbound side is ports (`port.out`) implemented by infrastructure: persistence, password hashing and token issuing are all behind interfaces owned by `application`.

Rules (ArchUnit, `ArchitectureTest`, see `CLAUDE.md`):
- `domain` depends on neither Spring nor jOOQ (nor any framework). Only `hypersistence-tsid` and Lombok (restricted) are allowed.
- `application` depends only on `domain`, plus `@Service` and `@Transactional` from Spring and SLF4J. Moving those two annotations out would need a wiring class per handler and a hand-made transaction proxy (including the `noRollbackFor` of the login, which must keep failed attempts), for no gain in testability: handlers are tested with plain constructors and Mockito.
- `domain` and `application` never depend on `infrastructure`, `api` or `config`. jOOQ types stay in `infrastructure`.
- No Lombok builder or setter in `domain` and `application`; aggregates use factory methods (`register`, `reconstitute`).
- A `valueobject` package holds only classes that implement `shared.domain.ValueObject`, and every such class lives in a `valueobject` package (storage today; `auth` still has to follow).

Ports:
- `FileStoragePort`: two logical zones, `QUARANTINE` and `CLEAN`. Operations are stream-based: `store` (writes the body, reports the size and the SHA-256 it measured while reading, and never reads past a limit it is given), `open`, `promote` (copy quarantine -> clean, the quarantine is cleared only after the verdict is recorded so a crash can be retried) and `delete` (already gone is not an error). The domain has no notion of buckets: the S3 adapter maps zones to buckets and names objects by file id, never by user-supplied name. A body longer than the limit stops the write and keeps nothing (`UploadTooLargeException`), a failing read of the body is `UploadInterruptedException`, a failing write is `FileStorageException`.
- `VirusScanner`: scans a stream and returns a verdict. A scanner that cannot be reached (`ScannerUnavailableException`) says nothing about the file and the job is released without consuming an attempt; a scan that does not complete (`ScanExecutionException`) counts as an attempt.
- `CurrentUserProvider`: returns the caller as an `OwnerId`. Its adapter reads the security context through `shared.infrastructure.security.AuthenticatedPrincipal` (the access token carries the user id in a `uid` claim), so neither `auth` nor `storage` knows the other's types; ArchUnit enforces it. It fails closed for anything that cannot tell its id, the anonymous user included.
- `FileRepository` and `ScanJobRepository`: `save` and `saveAll` take the `Actor` (user, worker or system) the change is made by and write the state and its audit events together. `findByIdAndOwner` is the only lookup by caller: there is no `findById` for a user request, so no handler can forget the ownership check.

Authentication: JWT (HS512) in HttpOnly SameSite=Strict `access_token` and `refresh_token` cookies. Refresh tokens are stored as hashes and can be revoked. Five failed logins lock the account for 30 minutes. Unknown user, disabled account and wrong password return the same 401 (`Invalid credentials`); domain exceptions carry fixed messages and `AuthExceptionHandler` maps them to RFC 9457 problem details. The clock is injected (`Clock` bean, UTC) and handed to the domain as a parameter.

Refresh: `POST /api/v1/auth/refresh` checks the JWT signature and expiry, then that the stored hash is neither revoked nor expired, then loads the account: a missing, disabled or locked account gets no new access token, and the roles of the new token come from the account, not from the refresh token. Only the access token is renewed.

Known limits of the authentication (accepted for now):
- Refresh tokens are not rotated: a stolen one stays usable until it expires (7 days) or the user logs out. Rotation needs reuse detection and handling of concurrent refreshes.
- `revokeAllByUserId` exists but nothing calls it yet; it becomes useful with password change or account disabling.
- Swagger UI and `/v3/api-docs` are public.
- Registration checks then inserts: two concurrent registrations of the same username are stopped by the unique constraint (a 500 instead of a 409 in that race).

Ownership: `SecureFile` carries its `owner: OwnerId` (the storage context's own type, no dependency on `auth`). Repositories and use cases always query with the caller's identity, so a foreign file is indistinguishable from a missing one (no IDOR).

IDs are TSIDs (`UserId`, `FileId`, `OwnerId`, `ScanJobId` wrap a `TSID`), stored as `BIGINT`, generated by the application behind an `IdGenerator` port (never a database sequence or auto-increment), exposed as strings in the API.

### Storage domain (implemented, pure Java)

```
storage/domain/file/               SecureFile (aggregate), FileStatus, UploadFailureReason, UploadTimeoutPolicy
storage/domain/file/valueobject/   FileId, OwnerId, Sha256 (only true value objects: ArchUnit checks both ways)
storage/domain/file/event/         FileEvent (sealed) and its 8 records
storage/domain/file/exception/     InvalidFileTransitionException, UploadSizeMismatchException
storage/domain/scan/               ScanJob (aggregate), ScanJobState, ScanQueue, ScanFailureCause, ScanFailureOutcome,
                                   ScanRetryPolicy, ScanQueuePolicy
storage/domain/scan/valueobject/   ScanJobId, WorkerId
storage/domain/scan/event/         ScanJobEvent (sealed) and its 6 records
storage/domain/scan/exception/     LeaseLostException, InvalidScanJobTransitionException, ScanJobNotDueException,
                                   LeaseNotExpiredException
```

- `SecureFile` and `ScanJob` are separate aggregates, linked by `FileId`. Both are created by a factory method and every transition takes `now` as a parameter.
- **Scan job lease**: fixed 30 s (configurable), renewed by a heartbeat, so it does not depend on the file size. Only the owner (`WorkerId`) can renew, complete, fail or release the job; any other worker gets `LeaseLostException` and must drop its result. A reported failure and an expired lease consume an attempt; a `release` (ClamAV down, shutdown) does not. `ScanJob.fail` and `reclaimExpired` return a `ScanFailureOutcome` (`RETRY_SCHEDULED` or `EXHAUSTED`) and the caller applies it to the file (`requeueScan` or `failScan`).
- **Policies** are immutable records configured from outside: `UploadTimeoutPolicy` (upload deadline = base delay + size / minimum rate, because nothing renews it while the body streams), `ScanRetryPolicy` (max attempts, exponential backoff capped by a maximum), `ScanQueuePolicy` (a file of at most the threshold goes to the `SMALL` queue, a larger one to `LARGE`; the threshold is a hypothesis to calibrate with measured scan durations).
- **Audit events**: every successful transition records exactly one typed event; a refused transition records nothing; the heartbeat records nothing. See section 12.
- Dates in the storage domain are `Instant` (stored as `TIMESTAMPTZ`).

## 3. Persistence

- PostgreSQL with jOOQ. No JPA/Hibernate.
- ALL DDL is in Flyway migrations (`src/main/resources/db/migration`). One schema per bounded context (`storage` for files and scans, `auth` for accounts and tokens), nothing in `public`. The contexts do not reference each other: a file's `owner_id` is a plain column, not a foreign key to `auth`.
- jOOQ code is generated from the migrations against a Testcontainers PostgreSQL and **committed** under `shared.infrastructure.persistence.jooq`. Reason: the project must compile on a machine without Docker. The `jooq-codegen` Maven profile regenerates it (needs Docker). A test checks that the committed code matches the migrations, so drift fails the build. Generated code is excluded from JaCoCo.
- MapStruct is used in `infrastructure` only (DTO <-> command, jOOQ record <-> domain).

## 4. Container-aware JVM and Direct Buffers

- Run with container support (default on modern JVMs). Size the heap as a percentage of the container limit (`-XX:MaxRAMPercentage`), leaving room for off-heap memory. Do not hard-code `-Xmx`.
- Direct (off-heap) memory is not part of the heap. NIO channels, S3 and socket I/O use direct buffers. Set `-XX:MaxDirectMemorySize` explicitly so it is bounded and predictable, and budget: `container limit >= heap + direct + metaspace + thread stacks + headroom`.
- Virtual threads are enabled (`spring.threads.virtual.enabled=true`). Blocking I/O per request is cheap, but memory per in-flight upload is not: cap concurrent uploads and scans (bulkheads/semaphores), because each holds buffers.
- Avoid pinning: no long blocking operations inside `synchronized` blocks (prefer `ReentrantLock`).
- Buffers are fixed-size and reused per stream. The number of concurrent streams times buffer size defines the direct memory need.
- Direct memory and virtual-thread metrics come in a later phase (section 10).

## 5. Stream handling

- Never buffer a whole file. Upload up to 2 GB is streamed end to end: request body -> `DigestInputStream` (SHA-256 computed on the fly) -> quarantine storage.
- Avoid framework abstractions that spool the whole request to memory or disk (for example, standard multipart handling into a temporary file). The upload must read the servlet input stream directly.
- S3 `PutObject` needs a known length, so streaming an unknown-length body uses **multipart upload** with fixed-size parts. Memory use is `part size x concurrent uploads`.
- Failures abort the multipart upload and remove partial objects. The file never becomes visible in status `PENDING` unless the upload fully succeeded and the digest is known.
- Row first: TX1 creates the file (`UPLOADING`, with its upload deadline), the body streams outside any transaction (no database connection held), then TX2 moves the file to `PENDING` and creates the scan job. A reaper fails the `UPLOADING` files past their deadline (`UPLOAD_FAILED`); a late TX2 is then refused by the status check. Chosen over "stream first, insert after" because the in-flight state stays visible and recoverable and it keeps room for presigned uploads and idempotency keys.
- Concurrency of uploads is bounded per instance by a non-blocking semaphore (`tryAcquire`, otherwise `429`/`503` with `Retry-After`), plus a lower per-user cap (planned, not implemented).
- The worker streams quarantine object -> ClamAV `INSTREAM` (chunks prefixed by a 4-byte length, terminated by a zero-length chunk) over TCP. The connection has connect/read timeouts.
- ClamAV limits must exceed the largest file: `StreamMaxLength`, `MaxFileSize` and `MaxScanSize` are raised to cover 2 GB. clamd may spool the stream to its temp directory, so that volume must have enough disk space.
- Reverse proxy and Tomcat limits (request size, timeouts) must be aligned with 2 GB.

## 6. Scan-cache strategy

Cache table keyed by SHA-256, storing the verdict, the signature DB version and the scan time.

- **INFECTED is final.** A known-bad digest stays bad: no re-scan needed.
- **CLEAN is valid only for the signature version that produced it.** The worker reads the current clamd signature version (`VERSION` command: engine, signature DB number and date) before scanning. On a cache hit, a CLEAN verdict is reused if the stored signature version equals the current one. Otherwise the file is scanned again and the cache entry is replaced.
- Rationale: a fixed TTL is arbitrary. New signatures are the real reason a CLEAN verdict becomes stale, so the signature version is the invalidation key. Since signatures update several times a day, CLEAN hits are mostly effective for duplicate uploads within the same signature window.
- Not done yet (possible later): retroactive re-scan of stored CLEAN files after a signature update.

## 7. Scan job queue

- A scan job row is created in PostgreSQL in the same transaction that marks the file `PENDING`. The job goes to the `SMALL` or `LARGE` queue (`ScanQueuePolicy`), each served by its own workers, so a large scan never delays the small ones.
- Workers claim jobs in batches, as many as they have free slots, with `SELECT ... LIMIT n FOR UPDATE SKIP LOCKED` (no contention, horizontally scalable, no broker needed) and commit immediately: the claim sets the lease owner and expiry, and the files move to `SCANNING`. A worker never claims more than it can start at once, because a lease runs from the claim and a job left waiting would see it expire and lose an attempt. ClamAV then scans outside any transaction while a heartbeat extends the lease; the only database lock is the one of the short claim transaction, during the scan a job is protected by its lease, not by a lock. On completion the worker applies its verdict with a compare-and-set on the lease owner.
- A job whose file is not in the expected state (a `PENDING` job whose file is not `PENDING`, or an expired lease whose file is not `SCANNING`) is inconsistent data. It must not fail the rest of its batch nor stay at the head of the queue: its job counts a failed attempt and waits for its backoff, and its file is left alone.
- Runtime (planned, not implemented): `web` and `worker` Spring profiles in the same jar, scheduling and profile wiring in `infrastructure`; a bounded executor per queue (a worker claims only when a slot is free); an exponential polling delay that falls back to zero as soon as a job is found; graceful shutdown that releases the leases.

## 8. Queue failure handling

| Case | Behaviour |
|---|---|
| Worker dies mid-scan | The job has a **lease** (default 30 s, heartbeat about every 10 s). When the lease expires, a reaper (`ScanJob.reclaimExpired`) puts the job back to `PENDING` and **consumes an attempt**: a file that kills its worker must not be retried forever. The former owner can no longer renew or complete. |
| Scan fails (I/O, timeout, protocol error) | The worker reports it (`ScanJob.fail`): retry with exponential backoff (`next_attempt_at`, a job is not claimable before it is due). **Max 3 attempts**, then the job is `DONE`, the file becomes `SCAN_FAILED` and the last error is kept. |
| ClamAV is down or unreachable | This is infrastructure trouble, not a file problem: the job is released (`ScanJob.release`) **without consuming an attempt**, with a delay, and the worker pauses claiming jobs (circuit-breaker style) until clamd answers `PING`. Files are not marked `SCAN_FAILED` because of an outage. A worker that shuts down releases its job with no delay. |
| Duplicate execution | Idempotent: the verdict is applied only if the job lease still belongs to the worker (fenced by `WorkerId`, which must be unique per process start). |
| Manual recovery | `SCAN_FAILED` is terminal for automatic processing. Requeue is a manual operation (to be specified later). |

Defaults (lease, attempts, backoff, queue threshold, upload deadline) are configuration values; the numbers are working hypotheses (for example a 50 MiB threshold between the `SMALL` and `LARGE` queues) to calibrate with a load test.

## 9. Testing strategy

- **Domain and use cases**: strict TDD. Domain tests without mocks. Use cases with Mockito on ports.
- **Architecture**: ArchUnit tests run in `mvn test` and fail on boundary violations.
- **Adapters**: integration tests (`*IT`, Failsafe) with Testcontainers: PostgreSQL (repositories, `SKIP LOCKED`, migrations) and LocalStack S3 (storage adapter, including multipart uploads). ClamAV may be a container or a fake TCP server for protocol tests.
- **Schema/codegen drift**: a test regenerates from the migrations and compares.
- **Coverage**: JaCoCo, unit + integration merged, fails below 80% instruction coverage. Excluded: jOOQ generated code, MapStruct generated implementations, configuration classes, the Application main class.
- `mvn test` needs no Docker. `mvn verify` needs Docker.

## 10. Scaling strategy

- The API is stateless: scale horizontally behind a load balancer. Files are never stored on local disk.
- Scan workers scale independently of the API: any number of workers can consume the same queue thanks to `SKIP LOCKED`. Concurrency per worker is bounded (memory/direct buffers).
- ClamAV scales by running several clamd instances behind a TCP load balancer. Each uses a lot of RAM for signatures, and scans are CPU-bound.
- Object storage scales independently (S3-compatible).
- PostgreSQL is the coordination point: index the queue with partial indexes (`next_attempt_at` where `state = 'PENDING'`, `lease_expires_at` where `state = 'LEASED'`) so they only hold live jobs, keep job rows small, and purge finished jobs.

## 11. Infrastructure

Phase 1 (`docker-compose.yml`): app, PostgreSQL, LocalStack S3 (pinned image tag), ClamAV (with raised limits). MinIO was dropped because its public images are no longer pullable; the adapter uses the AWS SDK v2, so real S3 or any S3-compatible server only needs configuration. LocalStack is an emulator: the community edition keeps no data across restarts.

The app image is built in two stages (Maven build, then a JRE-only runtime running as a non-root user) from a layered jar, so a code change rebuilds only the last layer. Defaults in the image: `-XX:MaxRAMPercentage=50`, `-XX:MaxDirectMemorySize=256m` and `-XX:+ExitOnOutOfMemoryError`; compose gives the app 1 GB, so heap 512 MB + direct 256 MB leaves about 250 MB for metaspace, thread stacks and headroom. The app healthcheck uses bash's `/dev/tcp` because the image has no curl.

Planned for a later phase (not implemented): Prometheus, Grafana, Loki and Tempo, plus Micrometer metrics (virtual threads, direct memory, scan duration).

## 12. Audit trail (history of file changes)

Requirement: the history of every change to a file is kept; nothing is overwritten without a trace. The tables `file` and `scan_job` hold the **current state**; the history is a separate append-only table. This is an audit log, not event sourcing: the state is never rebuilt from the events.

Domain (implemented): `SecureFile` records one `FileEvent` per transition and `ScanJob` one `ScanJobEvent` (both `sealed` hierarchies, so the code that writes them is checked by the compiler). Every event carries the `fileId`, so the whole history of a file, upload and scan attempts together, is one query. The heartbeat is not a transition and records nothing (it would write a row every few seconds for no audit value).

Persistence (implemented):
- One table `storage.file_event` for both aggregates: `id` (TSID, generated by the application), `file_id`, `aggregate_type`, `aggregate_id`, `version`, `event_type`, `payload` (JSONB with only the fields specific to the event type; never file content or secrets), `actor_type` (`USER`, `WORKER`, `SYSTEM`), `actor_id` (empty for the system), `occurred_at`. Index on `(file_id, occurred_at)`; unique `(aggregate_type, aggregate_id, version)`; no foreign key to `file`, so deleting a file never deletes its history. `AuditEventMapper` turns each event into a row with an exhaustive `switch` over the two sealed families, with event type names spelled out (never derived from a class name, they are stored forever).
- No auto-increment or sequence query. The version belongs to `VersionedAggregateRoot` (the storage aggregates; `User` does not carry one) and is the version the aggregate was loaded with, 0 when new. At save time the adapter computes in memory `new version = loaded version + number of events`, inserts the new aggregates in one multi-row `INSERT`, updates the others in one JDBC batch whose every row is `UPDATE ... WHERE id = ? AND version = loaded` (optimistic lock, a row that matches nothing means a concurrent change and raises `ConcurrentUpdateException`), and inserts the events of all the aggregates in one statement, numbered after the loaded version. No extra read.
- The exception is the heartbeat: it is not a transition, it records no event and must not touch the version (which counts events), so it has its own operation, `renewLease`, guarded on the lease owner and the `LEASED` state, and raises `LeaseLostException` when the job is no longer held, which tells the worker to stop.
- Events are written in the same transaction as the state change, so history and state cannot diverge. The actor is passed by the handler that makes the change (the caller, the worker, or the system for the reapers) as a second argument of `save`: the infrastructure does not guess who acts, and the repository cannot write a state without its audit. It is metadata of the event envelope, not part of the domain event.
- The table is immutable by design; enforcing it in the database (no `UPDATE`/`DELETE` privilege, or a trigger) and a retention policy (archiving or partitioning by date) are to be decided.
- File content is immutable: a new upload is a new file, never an overwrite of the stored object. Deletion will be logical. S3 versioning is not used: the audit concerns state changes, not copies of the bytes.
