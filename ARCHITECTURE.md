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

### How a scan streams the content to ClamAV

```
S3 quarantine --HTTP response body--> worker --TCP socket, INSTREAM--> clamd
```

- The worker opens the quarantined object as an `InputStream` (the body of an HTTP response of the S3 API) and writes what it reads straight to a plain TCP socket opened towards clamd (`ClamAvVirusScanner`). clamd does not speak HTTP: it has its own small protocol on port 3310. Nothing is kept once a block is sent, the memory of a scan is one 64 KiB buffer whatever the size of the file.
- Bytes on the socket: the command `zINSTREAM\0`, then each block as a 4-byte big-endian length followed by that many bytes, then a block of length zero that means "end of the file". TCP only carries a continuous stream of bytes and keeps no message boundaries, which is why every block announces its length. clamd answers one line ended by a NUL byte: `stream: OK`, or `stream: <signature> FOUND`; anything else (size limit exceeded, internal error) is a failed scan, retried later.
- clamd gives its answer once it has received the whole stream (the zero-length block); the content is not judged block by block as it arrives.
- **Slow clamd, flow control.** Nothing in the code measures clamd's speed: it is the blocking I/O that slows the worker down. When clamd consumes slowly, the receive and send buffers of the socket fill, the write blocks, and the worker does not read the next block from S3. The unread data of the S3 response fills its own TCP buffers in turn, so S3 stops sending until the worker reads again. A virtual thread that waits in a blocking call costs almost nothing. The same chain applies to the upload from the client to S3.
- Stated limits: a Java socket has a read timeout (`app.scanner.clamav.read-timeout`, used while waiting for the answer) but **no write timeout**. A clamd that stops reading altogether would block the writing thread until clamd closes the connection, which its own read timeout (`ReadTimeout` of clamd) normally does. The lease of the job expires meanwhile and the reaper gives the job to another worker, but the blocked thread is not freed by the application itself. A watchdog that closes the socket after a period without progress, or non-blocking I/O with timeouts, would close this gap. A long stall may also see the S3 connection closed on its side: the read then fails and the scan counts a failed attempt.
- The connection to clamd is neither encrypted nor authenticated (clamd has no native TLS): it must stay on a private network, as in the compose file where the port is bound to `127.0.0.1`.
- Not chosen: asking clamd to scan a file by its path (`SCAN`) avoids sending the bytes but needs a filesystem shared by the workers and clamd, which does not fit object storage and an independent scaling of ClamAV.

### Checks applied today (streaming through the application)

| Step | Check |
|---|---|
| Initiate | The declared size must not exceed `app.upload.max-size-bytes` (`UploadSizePolicy`, checked first, so a refused file costs no row and no byte: 413). The owner comes from the authenticated principal, never from the request. The name is required and at most 255 characters, the size is not negative, the upload deadline is in the future and grows with the declared size. |
| Upload | The file is looked up by id **and** owner (someone else's file behaves as not found). It must still be `UPLOADING`: stored content is immutable, nothing is ever written over a file that left that status. The body is read once, never more than the declared size (`UploadTooLargeException`), never held whole in memory. The size and the SHA-256 are measured by the server, never taken from the client. A client that disconnects, a storage error or a wrong size mark the upload as failed with their own reason and the quarantine is cleaned. |
| Complete | The measured size must equal the declared one, otherwise the upload fails (`SIZE_MISMATCH`). The file becomes `PENDING` and its scan job is created in the same transaction, in the queue chosen by the size. A completion that arrives after the reaper failed the upload is refused by the status check. |
| Scan | A job is held by a lease (long enough for the longest scan, no heartbeat yet) and only its owner can end it (compare-and-set). A scanner that is down releases the job without consuming an attempt, a failed scan consumes one, with a backoff, up to a limit. The clean copy is made before the verdict is recorded, the quarantine is cleared after. |
| Download | The file is looked up by id and owner and must be `CLEAN` (`ensureDownloadable`). Only the clean zone is ever read. No database connection is held while streaming. |
| Always | Object keys are file ids, never names. Ids are guessable, so the ownership check is mandatory everywhere. Every state change is audited with its actor. The database repeats the invariants as constraints (digest format, status against the fields it implies). No content and no secret is logged. A token without a user id does not authenticate. |

Known gaps in these checks: there is no per-user concurrency cap or quota yet, and nothing validates the type of the content (any file type is accepted; the download is always an `application/octet-stream` attachment, so a browser never renders it).

### Load test, and the option of bypassing the application for large files

The streaming design is the default and has to be measured before it is trusted: a load test (k6 or Gatling) must fix the semaphore size, the part size, the number of workers per queue and the lease and heartbeat durations, which are hypotheses today. If the bandwidth, the CPU or the memory of the instances turns out to be the bottleneck for large files, large files can **bypass the application**: `InitiateUpload` returns presigned multipart upload URLs to the quarantine bucket, the client sends the parts straight to S3, then calls a completion endpoint. Small files keep the streaming path. Every check above has to be rebuilt on the new path, because a presigned URL moves the control from our code to S3's configuration:

| Check | With a direct upload |
|---|---|
| Ownership | Unchanged: URLs are only issued by an authenticated call that passes the ownership check. They are short-lived, name exactly one key (the file id) and cannot touch the clean bucket. |
| Size limit | A presigned `PUT` cannot cap the size by itself. The part size and the number of parts are fixed by the application from the declared size, each part is signed with its length (or a `POST` policy with `content-length-range` is used), and the completion step compares the real size of the object with the declared one and deletes it on a mismatch. |
| Digest | S3 gives no SHA-256 of a multipart object. The digest must be computed by reading the object again; the scan worker already streams it to ClamAV and can compute it in the same pass. That means a file would reach `PENDING` without its digest, which changes the schema constraint and the domain rule that tie the digest to the status. |
| Immutability and status | The row stays `UPLOADING` until the completion step has verified the object (it exists, it is complete, its size is right). The client's call to complete is a claim, not a proof. |
| Deadline and cleanup | The reaper must also abort the unfinished multipart upload, and a bucket lifecycle rule (`AbortIncompleteMultipartUpload`) is the safety net. |
| Concurrency and quotas | The per-instance semaphore no longer bounds those uploads. Limits move to the initiation: a cap of simultaneous `UPLOADING` files per user (counted in the database, so it holds across instances) and a storage quota. |
| Isolation and leaks | A presigned URL is a bearer right to write: short TTL, one key, bucket policy limited to the quarantine prefix, CORS restricted to the front end, server-side encryption. Downloads stay on the clean bucket after the status check, never from the quarantine. |
| Audit | Same events, plus one to record that upload URLs were issued. |

How such an upload would work, step by step:
- The application opens the multipart upload on S3 (`CreateMultipartUpload`), which answers with an `uploadId`, a short text. It is stored in a new column of the metadata row of the file in `storage.file` (never the content, which stays in S3): without it the application could neither check the parts, nor assemble them, nor abort the upload.
- The application cuts the file into parts (for example 16 MiB, at most 10,000 parts, so about 128 for 2 GB) and signs **one URL per part**. Each URL allows one operation only: upload part N of that `uploadId` under the key of that file in the quarantine bucket. Signing is a local computation with the application's credentials, it makes no call to S3, so the URLs can be given all at once or in batches as the client asks for more, which keeps their lifetime short. One part failing means resending that part, not the whole file; parts can be sent in parallel. A single signed `PUT` for the whole object also exists (up to 5 GB) but it restarts from zero at the first incident.
- **S3 does not know who the caller is.** A presigned URL is a bearer right: S3 only checks that the signature is valid (any change of the key, the `uploadId` or the part number breaks it) and that the URL has not expired. Whoever holds it can use it, it is not single-use and not bound to an IP address or a user. The identity check is therefore ours and happens **before** the URL is handed out: the call that issues it is authenticated and passes the ownership check, the URL names one key only, its lifetime is a few minutes, it never reaches the clean bucket, it is sent over HTTPS and **never logged** (the signature is a secret).
- Stealing a URL does not give more than the owner's own right: it can only alter that upload before it is assembled. The completion call is authenticated and checks the owner again; before assembling, the application compares the parts S3 received (`ListParts`: numbers, sizes, ETags) with what it expects and aborts the upload on any difference; once an upload is assembled or aborted, every URL of its `uploadId` stops working; and the scan runs on the final bytes whatever happened before.
- A variant gives the client temporary credentials limited to a key prefix (STS) instead of signed URLs. It is more powerful, heavier to operate, and also a bearer right: not retained.

The decision is deferred on purpose: it is taken with the numbers of the load test, not before.

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
- Runtime (implemented, `storage.infrastructure.worker`, wired by `config.ScanWorkerConfig` and switched with `app.worker.enabled`): one polling loop per queue (`ScanQueueLoop`) on its own virtual thread, each scan on a virtual thread of the executor of its queue (one executor per queue only so the thread names say which queue they serve: `scan-small-1`, `scan-large-1`, `scan-loop-small`, `scan-maintenance`; it limits nothing, the slots do). A `ScanQueueWorker` holds a semaphore of slots, claims at most the free slots, hands each job to `ProcessScan` and gives the slot back whatever the outcome. The wait between two polls is a `PollingPolicy`: no wait after a hit, a delay that doubles from a minimum to a maximum while the queue is empty. A separate maintenance loop calls the two reapers (expired leases, expired uploads) every `app.worker.maintenance-interval`. The web and the worker run in the same process for now; `app.worker.enabled=false` gives an instance that only serves requests.
- Not implemented yet: the heartbeat of a running scan (the lease is configured long enough for the longest expected scan instead, 10 minutes by default, and `renewLease` exists on the repository), and a graceful shutdown that releases the leases (at shutdown the scans are interrupted, their leases expire and the reaper gives the jobs back).

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

### Runtime control and adaptive capacity (not implemented)

Today the capacity of a worker is fixed at startup: the slots per queue (`app.worker.small-slots`, `app.worker.large-slots`, defaults 8 and 2) are configuration, read once, and the two queues have independent semaphores. The only control from outside is `app.worker.enabled` and the database. What would come next, in the order that seems useful:

- **Measure first.** A load test fixes the slots, the leases and the thresholds; metrics on the queues (jobs `PENDING`, age of the oldest, slots in use, scan duration) feed any later decision. ClamAV is CPU-bound: raising the slots above what `clamd` can process only slows each scan, so the real ceiling is raised by adding clamd instances behind a TCP balancer.
- **Borrowing between queues.** A global ceiling with a minimum reserved for each queue. `SMALL` may use the slots `LARGE` leaves free, because a small scan gives its slot back within seconds; `LARGE` must not take the slots of `SMALL`, because a large scan runs for minutes and cannot be taken back, which is exactly what the two queues are there to prevent.
- **Scaling.** Horizontally first: more worker instances consume the same queues thanks to `SKIP LOCKED`, started and stopped by an orchestrator from the queue depth (for example KEDA or an HPA). Inside an instance the slots could follow the queue depth and the scan latency (a semaphore can grow with `release(n)` and shrink with `reducePermits`), within the ceiling that ClamAV allows.
- **Control from outside the application.** An administration role (the application only has `ROLE_USER` today) and, behind it: pause and resume of a queue (the claim stops, running scans finish; the state lives in the database so it holds on every instance), a `RetryScan` command that requeues an exhausted job with its attempts reset (through the domain, so the audit records who did it), a registry of the running scans to cancel one (a new transition in the domain, the lease and the reaper stay the safety net), and a view of what runs (slots in use per queue, leased jobs and their worker, oldest pending job).

### Deployment and observability at scale (not implemented)

- **Several ClamAV instances.** Each `clamd` is independent (its own signatures in memory, 1 to 2 GB, and its own `freshclam` updates), so the replicas can be raised behind one service name and the application only changes `app.scanner.clamav.host`. A scan is a single TCP connection (`INSTREAM`), so balancing per connection is enough, but a plain Kubernetes `Service` spreads at random without looking at the load: an L4 balancer with least connections (HAProxy, Envoy) is fairer for scans that last minutes. Two instances may briefly hold different signature versions, which is what the signature version of the verdict cache is for. The pod needs the memory of its signatures reserved, a readiness probe (`clamdcheck.sh`), and an autoscaler on the CPU, since a scan is CPU-bound.
- **Separate `web` and `worker` instances.** Today one switch exists, `app.worker.enabled` (`WORKER_ENABLED`, true by default): an instance serves the API and scans. To separate them now, the same image runs twice: web instances with `WORKER_ENABLED=false`, worker instances with `WORKER_ENABLED=true` that are not exposed behind the load balancer or the ingress (only their health probe is reachable), each scaled on its own signal (traffic for the web, queue depth for the workers). Real Spring profiles (`web` and `worker`, each with its own properties: the worker switch, the size of the connection pool, the management port) would make it a single variable, `SPRING_PROFILES_ACTIVE`.
- **Metrics with Prometheus.** Prometheus pulls (scrapes) the metrics, it does not receive them: each pod exposes `/actuator/prometheus` (Micrometer) and Prometheus discovers the pods by their labels (a `ServiceMonitor` or a `PodMonitor` with the Prometheus operator). It stores one series per pod, told apart by the `pod` and `instance` labels, and aggregates at query time (`sum(rate(...))`, `sum by (queue)(...)`); a counter that restarts at zero is handled by `rate()`. Values read from the database (jobs pending, age of the oldest) are the same on every pod and need `max`, not `sum`; values of a pod (slots in use, scan duration) are summed. A single Prometheus is the weak point: two identical replicas for availability, and Thanos, Mimir or Cortex for a global view and a long history. Nothing is exposed yet beyond the health endpoint; the metrics to add are the jobs pending, the slots in use per queue, the scan duration, the verdicts and the failures, which would also drive the scaling of the workers.

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
