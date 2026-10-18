# AITSMSystem

AITSMSystem is an AI-assisted IT service management portal for ticket operations, SLA management, device inventory, Windows telemetry, knowledge management, notifications, and role-controlled administration.

The current public environment is a beta system. Changes are validated in beta before an explicit production release to `aitsmportal.com`.

## Architecture

AITSM is a three-tier application implemented as a layered modular monolith:

```text
Browser / Windows Collector
            |
            v
Kotlin + Ktor application on Cloud Run
            |
            v
PostgreSQL on Cloud SQL
```

The backend follows this logical flow:

```text
Routes -> Services -> Repositories / Queries -> PostgreSQL
```

- **Frontend:** static HTML, CSS, and JavaScript served by Ktor
- **Backend:** Kotlin, Ktor, Exposed, and HikariCP
- **Database:** PostgreSQL with ordered SQL migrations
- **Collector:** self-contained Windows service using outbound HTTPS
- **Hosting:** Google Cloud Run and Cloud SQL
- **AI:** provider-backed assistance with deterministic fallbacks

## Repository layout

```text
collector/windows/       Windows collector and installer
db/                      Base schema and ordered migrations
docs/                    Deployment, security, and user guides
src/main/kotlin/         Ktor application and backend layers
src/main/resources/      Frontend assets and packaged collector
src/test/                Automated tests
```

## Local development

1. Copy `.env.example` to `.env` and configure local values.
2. Start PostgreSQL and apply `db/schema.sql`, followed by every migration in `db/migrations` in version order.
3. Run the application:

```powershell
.\gradlew.bat run
```

The default local address is `http://localhost:8070` unless `PORT` is configured.

## Verification

Run the complete automated test suite before deployment:

```powershell
.\gradlew.bat --no-daemon test
```

Build the deployable application artifact with:

```powershell
.\gradlew.bat --no-daemon clean test buildFatJar
```

## Documentation

- [Deployment runbook](docs/DEPLOYMENT.md)
- [Security runbook](docs/SECURITY.md)
- [User guide](docs/USER_GUIDE.md)
- [Windows collector guide](collector/windows/README.md)

API behavior is maintained in route definitions and automated tests. A generated API reference can be introduced later to prevent a manually maintained endpoint list from becoming stale.

## Important release rules

- Registration is invitation-only.
- Secrets belong in environment variables or Google Secret Manager and must never be committed.
- Production startup does not apply database migrations automatically.
- Beta changes must not be promoted to production without regression testing, a database backup, and a rollback plan.
- The Windows collector requires outbound HTTPS only; never expose a local collector or Ollama port publicly.
