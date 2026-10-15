# ktor-aitsm

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:

- [Ktor Documentation](https://ktor.io/docs/home.html)
- [Ktor GitHub page](https://github.com/ktorio/ktor)
- The [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). You'll need
  to [request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up) to join.

## Features

Here's a list of features included in this project:

| Name                                               | Description                                                 |
|----------------------------------------------------|-------------------------------------------------------------|
| [Routing](https://start.ktor.io/p/routing-default) | Allows to define structured routes and associated handlers. |

## Building & Running

To build or run the project, use one of the following tasks:

| Task                                    | Description                                                          |
|-----------------------------------------|----------------------------------------------------------------------|
| `./gradlew test`                        | Run the tests                                                        |
| `./gradlew build`                       | Build everything                                                     |
| `./gradlew buildFatJar`                 | Build an executable JAR of the server with all dependencies included |
| `./gradlew buildImage`                  | Build the docker image to use with the fat JAR                       |
| `./gradlew publishImageToLocalRegistry` | Publish the docker image locally                                     |
| `./gradlew run`                         | Run the server                                                       |
| `./gradlew runDocker`                   | Run using the local docker image                                     |

The repository includes a production multi-stage `Dockerfile`. Build it with `docker build -t aitsm .`; inject production variables from a secret manager and mount durable storage at `/app/uploads`.

Follow `DEPLOYMENT.md` for database migration order, required secrets, bootstrap, smoke tests, and rollback.

If the server starts successfully, it listens on the configured `PORT` (default `8070`).

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```


## Runtime environment variables

Copy `.env.example` to `.env` and set values for your environment before startup.

The server now requires credentials and provider configuration via environment variables (no hard-coded defaults for secrets).

Development has local PostgreSQL defaults for compatibility. Explicitly set these in shared development, CI, staging, and production:

- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`

Production (`APP_ENV=production`) fails closed unless these are valid:

- `DB_URL` with `sslmode=verify-full` (or the authenticated Cloud SQL Java connector), `DB_USER`, `DB_PASSWORD`
- HTTPS `APP_BASE_URL` and HTTPS-only `CORS_ALLOWED_ORIGINS`
- `COMPANY_NAME` and a base64-encoded 32-byte `MFA_ENCRYPTION_KEY`
- `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM` with STARTTLS enabled
- `GEMINI_API_KEY` (`AI_PROVIDER=gemini`)
- `AUTH_EXPOSE_DEV_VERIFICATION_CODE=false`

Optional:

- `BOOTSTRAP_SUPERADMIN` (normally `false`)
- `SUPERADMIN_EMAIL` and a 16+ character `SUPERADMIN_PASSWORD` only while bootstrap is enabled
- `SUPERADMIN_NAME`
- `SUPERADMIN_COMPANY`
- `SUPERADMIN_DEPARTMENT`
- `GEMINI_MODEL`
- `AI_TIMEOUT_MILLIS`


## Email verification

Account creation is invitation-only. An administrator sends an invitation, and the recipient accepts it from the signup page.

- `POST /api/auth/register` is disabled.
- `POST /api/auth/invitations/accept` consumes the single-use invitation and creates the account.
- Verification endpoints remain available for legacy pending-verification accounts.

Verification codes are delivered through SMTP when `SMTP_USERNAME`, `SMTP_PASSWORD`, and `SMTP_FROM` are configured. Brevo can be configured with `SMTP_HOST=smtp-relay.brevo.com`, port `587`, and TLS enabled. Keep the SMTP key in `.env` or a deployment secret manager. Set `AUTH_EXPOSE_DEV_VERIFICATION_CODE=true` only for local development without email delivery.

## Production security model

AITSM uses opaque 256-bit session tokens. The browser receives the raw value only in an `HttpOnly`, `SameSite=Lax` cookie; PostgreSQL stores only its SHA-256 hash. Production cookies are `Secure`, protected API responses are non-cacheable, legacy identity headers are rejected, and mutations require a session-bound CSRF header plus origin validation. Browser code obtains display identity from `/api/auth/me` and does not persist trusted identity or roles in local storage.

Registration is invitation-only. Invitation and password-reset tokens are random, hashed at rest, expiring, revocable, and single-use. Company is assigned from `COMPANY_NAME`. Verification codes are hashed with attempt and resend limits. Login has per-IP rate limits and temporary account lockout. Administrative accounts must enroll and complete TOTP MFA; secrets use AES-GCM under `MFA_ENCRYPTION_KEY`, replayed steps are rejected, and recovery codes are one-time bcrypt hashes.

Sensitive administration, MFA changes, exports, account deletion, recovery-code regeneration, and security configuration use five-minute reauthentication grants. Only hashes are persisted; grants are bound to the current user, session, and exact scope, and mutation grants are single-use. The user-management page includes invitation lifecycle controls and an audited superadmin-only recovery path for another administrator's MFA.

For a new database, apply `db/schema.sql` and then every file in `db/migrations` in version order. For an existing database, apply all unapplied migrations through `V013__archive_revoked_collectors.sql`. V004 separates transaction notifications from direct messages and adds idempotency keys. V005 adds collectors, reported devices, and queued discovery requests. V006 safely links matching legacy colleague notifications to the responsible employee. V008 adds one-time, owner- and role-scoped collector enrollment. V013 adds non-destructive archival for revoked collectors. Production performs no schema mutation at startup. See `SECURITY.md` for the full release gate.

Focused verification: `./gradlew test --tests backend.security.SecurityBoundariesTest`. Full verification: `./gradlew test`. CI also runs OWASP Dependency-Check and Gitleaks.

## Admin role management

Admins and superadmins can manage user roles from the Admin Dashboard (User Access Management) or via API: `PUT /api/users/{id}/role` with role `admin` or `end-user`.


## LAN collector

Cloud Run cannot inspect a private LAN. The portal generates a one-click Windows x64 EXE that enrolls once, installs an automatic background service, protects its machine credential with Windows DPAPI, and reports outbound over HTTPS. Administrators can deploy company/LAN or self-device collectors; end users can deploy only a self-device collector that cannot scan peers. Enrollment codes expire after 10 minutes, work once, are stored only as hashes, and remain bound to the account that requested the download. Configure only approved private ranges in **Settings → Asset Detection Configuration**. See [`collector/windows/README.md`](collector/windows/README.md) for installation, permissions, health, repair, revocation, troubleshooting, and uninstall steps.

The UI distinguishes service health, collector connectivity, discovery status, observable peers, and devices with detailed agent telemetry. `timestamp` is the server receipt time; collector measurement time remains separate. Browser reloads and failed refreshes do not manufacture new update times.

## Schema note

Primary keys remain integer-based in this release to minimize API breakage; UUID migration is planned as a staged change with backward-compatible ID translation.
