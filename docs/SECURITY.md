# AITSM production security runbook

## Deployment gate

Production startup requires TLS-verified PostgreSQL credentials, HTTPS application/CORS origins, `COMPANY_NAME`, `MFA_ENCRYPTION_KEY`, and SMTP credentials with STARTTLS. Bootstrap superadministrator credentials are required only while `BOOTSTRAP_SUPERADMIN=true`. Keep all real values in a deployment secret manager; `.env` is ignored and must never be committed. Rotate any credential previously pasted into chat, tickets, logs, or terminal transcripts before deployment. Ollama must remain bound to localhost; never expose port `11434`. The AITSM AI Bridge is restricted to explicitly enabled LAN collectors, authenticates with their DPAPI-protected collector credential, uses outbound HTTPS only, and expires unclaimed or incomplete jobs.

For a new database, apply `db/schema.sql` and then all versioned migrations. For an existing database, apply every unapplied migration through V003 with a dedicated migration identity. V002 removes legacy AI history without user ownership; V003 repairs verified accounts left in the pre-activation state. Production startup does not mutate schema. The runtime PostgreSQL role should have only `CONNECT`, `USAGE` on its schema, and CRUD/sequence privileges on AITSM tables—no database ownership, role creation, extension creation, or schema DDL. Set PostgreSQL TLS in `DB_URL` with `sslmode=verify-full` and a trusted CA, or use the authenticated Cloud SQL Java connector on Google Cloud.

Set `BOOTSTRAP_SUPERADMIN=true` with `SUPERADMIN_EMAIL` and `SUPERADMIN_PASSWORD` only for the initial controlled bootstrap. Store a high-entropy password in the secret manager, start once, sign in, enroll TOTP, retain recovery codes offline, then set the flag to false and remove the password. Bootstrap never overwrites an existing account. Company administrators cannot invite or grant `SUPERADMIN`.

## Backups and keys

- Encrypt database and upload backups with a key independent from database and MFA keys.
- Test a restore into an isolated environment at least quarterly and record recovery time and integrity checks.
- Rotate SMTP, OAuth, database, and MFA encryption keys under a documented change window. MFA key rotation requires decrypt/re-encrypt tooling before replacing the old key.
- Revoke all sessions and rotate affected credentials after suspected compromise. Preserve append-only security and audit events for investigation.

## Monitoring

Alert on bursts of login failures/lockouts, repeated 403 responses, administrator invitations or role/status changes, bulk exports, unusual ticket/asset access, MFA recovery, and unexpected bootstrap activity. Never include passwords, cookies, CSRF values, invitation/reset tokens, verification codes, TOTP secrets, recovery codes, or SMTP/OAuth/database secrets in logs.

CI runs a clean test/package build, JavaScript syntax checks, OWASP Dependency-Check (fails at CVSS 9+), and Gitleaks. Configure the repository secret `NVD_API_KEY` with a free NVD API key so Dependency-Check can refresh its vulnerability database; CI deliberately fails when the key is absent. Every suppression must be narrow, justified, reviewed, and time-limited.
