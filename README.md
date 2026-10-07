# secure-file-service

Secure file management microservice: files are uploaded (streamed, up to 2 GB) into a quarantine zone, scanned by ClamAV, and can be downloaded only once they are CLEAN. Each user can only access their own files.

Status: authentication, streamed upload, asynchronous scan (workers) and download of clean files are implemented. A React interface lives in a separate repository, `secure-file-ui`. See `ARCHITECTURE.md` for the design.

## Prerequisites

- JDK 25
- Maven 3.9+
- Docker (with Docker Compose), needed only for:
  - integration tests and the coverage gate (`mvn verify`)
  - running the whole stack
  - regenerating jOOQ code

You can compile and run the unit tests **without Docker**: the jOOQ code is committed.

## Quickstart

```bash
# compile (no Docker)
mvn compile

# 1. create your .env (git-ignored) from the template
cp .env.example .env

# 2. generate the JWT signing secret into it (works on macOS and Linux)
sed -i.bak "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')|" .env && rm .env.bak

# 3. start PostgreSQL, LocalStack (S3), ClamAV and the app
docker compose up --build
```

The app listens on `http://localhost:8080`. ClamAV downloads its signatures on first start and takes a couple of minutes to become ready; the app does not wait for it.

If port 5432 is already used on your machine, change `POSTGRES_HOST_PORT` in `.env`.

### The JWT secret

`JWT_SECRET` signs the access and refresh tokens (HS512). It must be at least 64 characters, it has no default on purpose, and the app (and `docker compose`) refuses to start without it. Never commit a real value: `.env` and `application-local.properties` are git-ignored. Changing it invalidates every token already issued. `openssl rand -base64 64` prints 88 characters.

`COOKIE_SECURE` defaults to `false` so cookies work over plain HTTP in local development. Set it to `true` behind HTTPS.

### Running the app outside Docker

Start only the infrastructure, then run the app from your IDE or Maven:

```bash
docker compose up -d postgres localstack clamav

cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
# edit it: set jwt.secret (openssl rand -base64 64) and make the other values match your .env

mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### Trying the authentication

```bash
# register, then log in (the tokens are returned as HttpOnly cookies, not in the body)
curl -X POST localhost:8080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'
curl -c cookies.txt -X POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"password123"}'

# current user (uses the access_token cookie)
curl -b cookies.txt localhost:8080/api/v1/users/me
```

Other endpoints: `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`. Everything except `/api/v1/auth/**`, `/actuator/health` and the OpenAPI documentation (`/swagger-ui/index.html`, `/v3/api-docs`) requires authentication.

### Trying the files

```bash
# upload: the body is the file itself, the name is a query parameter (Content-Length is required)
curl -b cookies.txt -X PUT "localhost:8080/api/v1/files?name=report.pdf" --data-binary @report.pdf

# list my files with their status, then download one once it is CLEAN
curl -b cookies.txt localhost:8080/api/v1/files
curl -b cookies.txt -OJ localhost:8080/api/v1/files/<id>/content
```

| Endpoint | Meaning |
|---|---|
| `PUT /api/v1/files?name=` | Streams the body into quarantine. `202` with the file id once stored and queued for the scan; `411` without `Content-Length`; `413` above `app.upload.max-size-bytes` |
| `GET /api/v1/files` | The files of the caller, newest first, with their status |
| `GET /api/v1/files/{id}/content` | The content as an attachment. `409` unless the file is `CLEAN`; `404` for an unknown file **and** for the file of someone else |

A file goes `UPLOADING`, `PENDING`, `SCANNING`, then `CLEAN` or `INFECTED` (or a failed state). To see an infected file, upload the EICAR test string, a harmless text every antivirus flags: `X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*`.

The scan workers run inside the application (`app.worker.*` in `application.properties`: slots per queue, lease, polling delays). `WORKER_ENABLED=false` starts an instance that only serves requests.

## Tests

| Command | What it runs | Docker |
|---|---|---|
| `mvn test` | Unit tests and ArchUnit architecture rules | no |
| `mvn verify` | Unit + integration tests (Testcontainers: PostgreSQL, LocalStack S3) and the JaCoCo 80% gate | yes |

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` with JDK 25 on every push and pull request, and uploads the JaCoCo report as the `jacoco-report` artifact (also when the coverage gate fails). On pushes to `main` and on pull requests into `main`, the same build also sends the analysis to SonarCloud (`mvn verify sonar:sonar`, project `MSJ-Labs_secure-file-service`); it needs the `SONAR_TOKEN` repository secret and the project's "Automatic Analysis" switched off in SonarCloud. Other branches and pull requests from forks (no access to the secret) only run `mvn verify`. To run it by hand: `SONAR_TOKEN=... mvn verify sonar:sonar`.

## Database and jOOQ

The schema lives only in Flyway migrations (`src/main/resources/db/migration`). The jOOQ code generated from it is committed under `com.msj.securefile.shared.infrastructure.persistence.jooq`. After adding a migration, regenerate it (needs Docker) and commit the result:

```bash
mvn -Pjooq-codegen generate-sources
```

## Design choices

- **Hexagonal, feature-first.** Two bounded contexts, `auth` and `storage`, that never depend on each other (checked by ArchUnit); a pure-Java domain, handlers as the inbound API, adapters (jOOQ, S3, ClamAV, web) outside.
- **Streaming everywhere.** The upload is read once from the servlet stream, hashed (SHA-256) and written to S3 (multipart above one part) without ever holding the file in memory; the size and the digest are measured by the server. Downloads stream from S3 to the response.
- **Quarantine, then clean zone.** Content is stored in a quarantine bucket, scanned by ClamAV through its `INSTREAM` protocol, copied to a clean bucket, and only that bucket is ever served.
- **Asynchronous scan with a database queue.** A scan job per file, claimed with `FOR UPDATE SKIP LOCKED`, two queues (small and large files) with their own workers, leases, retries with backoff, reapers for abandoned uploads and expired leases. No broker: PostgreSQL is enough at this scale and keeps the file and its job in one transaction.
- **Audit trail.** Every state change of a file or a scan job appends an event (`storage.file_event`) in the same transaction, with its actor.
- **Security.** JWT in HttpOnly SameSite=Strict cookies, BCrypt, revocable refresh tokens, account lock; every file belongs to an owner and someone else's file behaves as not found; ids are never trusted; downloads are opaque attachments.

## Assumptions

- Files go up to 2 GB; any type of content is accepted (documents, reports, exports), none is rendered by the service.
- A file is served only once scanned and clean; the status is visible to its owner at any time.
- A user only sees and downloads their own files. There is no sharing between users and no deletion yet.
- The scan thresholds (queue split at 50 MB, leases, retries, slots, polling delays) are working hypotheses, not measured values.
- The API is consumed from the same site as the interface (a reverse proxy or the dev proxy), which is what `SameSite=Strict` cookies require.
- ClamAV runs as a service reachable over TCP; its signatures are updated by its own container.

## Possible improvements

- **Heartbeat** of running scans (one batched update per worker) and a **graceful shutdown** that releases the leases, instead of a long lease and the reaper.
- **Verdict cache** keyed by SHA-256 and signature version (the table exists, see `ARCHITECTURE.md` section 6), and a re-scan of stored clean files after a signature update.
- **Limits and quotas:** the maximum size enforced in the domain, a per-user cap on concurrent uploads and on stored bytes, a concurrency semaphore on the upload endpoint with `429` and `Retry-After`.
- **Direct-to-S3 upload** with presigned multipart URLs for very large files, once a load test shows the application is the bottleneck.
- **Load test** (k6 or Gatling) to calibrate the slots, the leases, the part size and the queue threshold; metrics and tracing around the queues and the scans.
- **Deployment at scale:** several ClamAV instances behind one address (balanced per connection, ideally by least connections), separate `web` and `worker` instances (the same image with `WORKER_ENABLED` true or false today, Spring profiles later), and Prometheus metrics (`/actuator/prometheus` on every pod, aggregated at query time). See `ARCHITECTURE.md`, section 10.
- **Adaptive capacity and runtime control:** slots shared between the queues (`SMALL` may borrow idle `LARGE` slots, not the reverse), scaling of the workers from the queue depth, and an administration role to pause or resume a queue, retry an exhausted job, cancel a running scan and see what runs. The slots are configuration today (`app.worker.small-slots`, `app.worker.large-slots`) and need a restart to change. See `ARCHITECTURE.md`, section 10.
- **Content checks:** an allow-list of types, sniffing of the real type, and archive handling.
- **Push instead of polling** (Server-Sent Events) for the file status, file deletion and retention, separate `web` and `worker` deployments.

## Documentation

- `ARCHITECTURE.md`: design, boundaries, scan cache, queue failure handling
- `CLAUDE.md`: guidelines and rules for AI-assisted development
- `PROMPTS.md`: log of the prompts used to build this project
