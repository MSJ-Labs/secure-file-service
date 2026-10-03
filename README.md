# secure-file-service

Secure file management microservice: files are uploaded (streamed, up to 2 GB) into a quarantine zone, scanned by ClamAV, and can be downloaded only once they are CLEAN. Each user can only access their own files.

Status: authentication (register, login, refresh, logout, current user) is implemented. Upload/download endpoints are not implemented yet. See `ARCHITECTURE.md` for the design.

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

## Documentation

- `ARCHITECTURE.md`: design, boundaries, scan cache, queue failure handling
- `CLAUDE.md`: guidelines and rules for AI-assisted development
- `PROMPTS.md`: log of the prompts used to build this project
