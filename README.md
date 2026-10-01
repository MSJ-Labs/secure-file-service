# secure-file-service

Secure file management microservice: files are uploaded (streamed, up to 2 GB) into a quarantine zone, scanned by ClamAV, and can be downloaded only once they are CLEAN. Each user can only access their own files.

Status: project foundations (architecture, domain, ports). Upload/download endpoints and authentication are not implemented yet. See `ARCHITECTURE.md` for the design.

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

# start PostgreSQL, LocalStack (S3), ClamAV and the app
docker compose up --build
```

ClamAV downloads its signatures on first start and takes a couple of minutes to become ready.

## Tests

| Command | What it runs | Docker |
|---|---|---|
| `mvn test` | Unit tests and ArchUnit architecture rules | no |
| `mvn verify` | Unit + integration tests (Testcontainers: PostgreSQL, LocalStack S3) and the JaCoCo 80% gate | yes |

GitHub Actions (`.github/workflows/ci.yml`) runs `mvn verify` with JDK 25 on every push and pull request, and uploads the JaCoCo report as the `jacoco-report` artifact (also when the coverage gate fails).

## Database and jOOQ

The schema lives only in Flyway migrations (`src/main/resources/db/migration`). The jOOQ code generated from it is committed under `com.msj.securefile.shared.infrastructure.persistence.jooq`. After adding a migration, regenerate it (needs Docker) and commit the result:

```bash
mvn -Pjooq-codegen generate-sources
```

## Documentation

- `ARCHITECTURE.md`: design, boundaries, scan cache, queue failure handling
- `CLAUDE.md`: guidelines and rules for AI-assisted development
- `PROMPTS.md`: log of the prompts used to build this project

Copy `.env.example` to `.env` before the first `docker compose up`. If port 5432 is already used on your machine, change `POSTGRES_HOST_PORT` in `.env`. The app listens on `localhost:8080`; only `/actuator/health` is exposed.
