# AITSM production deployment to Google Cloud

This runbook deploys AITSM to Cloud Run with Cloud SQL for PostgreSQL, Artifact Registry, Cloud Build, Secret Manager, and a Cloud Storage volume for profile-photo uploads.

Commands are written for PowerShell and must be run from the repository root. Replace every `CHANGE_ME` value before continuing.

## 1. Prerequisites

1. Create or select a Google Cloud project with billing enabled.
2. Install the current [Google Cloud CLI](https://cloud.google.com/sdk/docs/install).
3. Install PostgreSQL command-line tools so `psql` is available.
4. Download the current [Cloud SQL Auth Proxy for Windows](https://cloud.google.com/sql/docs/postgres/connect-auth-proxy) as `cloud-sql-proxy.exe`.
5. Prepare a verified SMTP sender, SMTP credentials, strong initial super-admin password, and an NVD API key for GitHub Actions.

Use one region for Cloud Run, Artifact Registry, Cloud SQL, and the uploads bucket. `asia-southeast1` (Singapore) is a reasonable starting point for the Philippines, but confirm availability and cost for your organization.

## 2. Set deployment identifiers

```powershell
$PROJECT_ID = "CHANGE_ME_PROJECT_ID"
$REGION = "asia-southeast1"
$SERVICE = "aitsm"
$REPOSITORY = "aitsm-containers"
$SQL_INSTANCE = "aitsm-postgres"
$DB_NAME = "aitsm"
$DB_RUNTIME_USER = "aitsm_runtime"
$DB_MIGRATOR_USER = "aitsm_migrator"
$BUCKET = "${PROJECT_ID}-aitsm-uploads"
$SERVICE_ACCOUNT_NAME = "aitsm-runtime"
$SERVICE_ACCOUNT = "${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
$IMAGE_TAG = git rev-parse --short HEAD
$IMAGE = "${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/aitsm:${IMAGE_TAG}"
```

Authenticate and verify the active configuration:

```powershell
gcloud auth login
gcloud auth application-default login
gcloud config set project $PROJECT_ID
gcloud config set run/region $REGION
gcloud config list
```

## 3. Enable Google Cloud APIs

```powershell
gcloud services enable `
  run.googleapis.com `
  sqladmin.googleapis.com `
  artifactregistry.googleapis.com `
  cloudbuild.googleapis.com `
  secretmanager.googleapis.com `
  storage.googleapis.com `
  iam.googleapis.com
```

API activation can take a few minutes.

## 4. Pass the release gate

```powershell
.\gradlew.bat --no-daemon clean test buildFatJar

$jsFiles = Get-ChildItem -LiteralPath "src/main/resources/static/frontend" -Recurse -Filter "*.js"
foreach ($file in $jsFiles) {
  node --check $file.FullName
  if ($LASTEXITCODE -ne 0) { throw "JavaScript validation failed: $($file.FullName)" }
}

$env:NVD_API_KEY = "CHANGE_ME_TEMPORARILY"
.\gradlew.bat --no-daemon dependencyCheckAnalyze
Remove-Item Env:NVD_API_KEY
```

Do not deploy if a command fails. Review the worktree, stage only the intended release files, inspect the staged diff, and then commit:

```powershell
git status
git diff
git diff --check
git add CHANGE_ME_REVIEWED_PATHS
git diff --cached
git commit -m "Prepare AITSM production deployment"
git push
```

Do not replace `CHANGE_ME_REVIEWED_PATHS` with `.` or `--all` until every modified and untracked file has been reviewed.

Add `NVD_API_KEY` under **GitHub repository → Settings → Secrets and variables → Actions**. Confirm the repository's **Security checks** workflow passes.

## 5. Create the runtime identity

```powershell
gcloud iam service-accounts create $SERVICE_ACCOUNT_NAME `
  --display-name="AITSM Cloud Run runtime"

gcloud projects add-iam-policy-binding $PROJECT_ID `
  --member="serviceAccount:$SERVICE_ACCOUNT" `
  --role="roles/cloudsql.client"
```

Do not give the runtime service account project Editor or Owner access.

## 6. Create Cloud SQL

This is a production-oriented regional instance. It costs more than a zonal development instance.

```powershell
gcloud sql instances create $SQL_INSTANCE `
  --database-version=POSTGRES_16 `
  --edition=enterprise `
  --tier=db-custom-2-7680 `
  --region=$REGION `
  --availability-type=regional `
  --storage-type=SSD `
  --storage-size=20 `
  --storage-auto-increase `
  --backup-start-time=18:00 `
  --enable-point-in-time-recovery `
  --deletion-protection

gcloud sql databases create $DB_NAME --instance=$SQL_INSTANCE
```

In **Google Cloud Console → SQL → the new instance → Users**:

1. Set a strong password for the built-in `postgres` administrator.
2. Create `$DB_MIGRATOR_USER` with a unique migration-only password.
3. Create `$DB_RUNTIME_USER` with a different runtime password.
4. Store all passwords in a password manager. Only the runtime password goes to Cloud Run.

Get the connector name:

```powershell
$INSTANCE_CONNECTION_NAME = gcloud sql instances describe $SQL_INSTANCE --format="value(connectionName)"
$INSTANCE_CONNECTION_NAME
```

## 7. Apply the schema and migrations

Start the Auth Proxy on port `5433` to avoid a local PostgreSQL server on `5432`:

```powershell
$proxy = Start-Process `
  -FilePath ".\cloud-sql-proxy.exe" `
  -ArgumentList "--address","127.0.0.1","--port","5433",$INSTANCE_CONNECTION_NAME `
  -WindowStyle Hidden `
  -PassThru
```

Define a helper that masks password input and exposes it to `psql` only for the current process:

```powershell
function Set-PgPasswordFromPrompt([string]$Prompt) {
  $secureValue = Read-Host $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureValue)
  try {
    $env:PGPASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
  } finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
  }
}
```

Restrict schema creation and authorize the migration identity:

```powershell
Set-PgPasswordFromPrompt "Cloud SQL postgres password"
psql -h 127.0.0.1 -p 5433 -U postgres -d $DB_NAME -v ON_ERROR_STOP=1 `
  -c "REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE, CREATE ON SCHEMA public TO $DB_MIGRATOR_USER;"
Remove-Item Env:PGPASSWORD
```

For a new database, apply files in this exact order:

```powershell
Set-PgPasswordFromPrompt "Migration database password"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/schema.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V001__secure_auth_foundation.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V002__complete_runtime_schema_and_ai_ownership.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V003__reconcile_verified_account_state.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V004__transaction_notification_idempotency.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V005__collector_monitoring.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V006__backfill_colleague_notification_actors.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V007__reviewed_ticket_resolutions.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V008__collector_self_service_enrollment.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V009__collector_diagnostics_and_device_identity.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V010__sla_lifecycle_and_prediction.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V011__canonical_devices_and_address_history.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V012__merge_stale_adapter_identity_into_uuid_device.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V013__archive_revoked_collectors.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V014__secure_ollama_ai_bridge.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V015__ticket_transition_notes.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V016__atomic_ticket_ownership.sql"
psql -h 127.0.0.1 -p 5433 -U $DB_MIGRATOR_USER -d $DB_NAME -v ON_ERROR_STOP=1 -f "db/migrations/V017__notification_history_pagination.sql"
Remove-Item Env:PGPASSWORD
```

Grant only runtime data access after every migration succeeds:

```powershell
Set-PgPasswordFromPrompt "Cloud SQL postgres password"
psql -h 127.0.0.1 -p 5433 -U postgres -d $DB_NAME -v ON_ERROR_STOP=1 `
  -c "GRANT CONNECT ON DATABASE $DB_NAME TO $DB_RUNTIME_USER; GRANT USAGE ON SCHEMA public TO $DB_RUNTIME_USER; GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO $DB_RUNTIME_USER; GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO $DB_RUNTIME_USER; ALTER DEFAULT PRIVILEGES FOR ROLE $DB_MIGRATOR_USER IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $DB_RUNTIME_USER; ALTER DEFAULT PRIVILEGES FOR ROLE $DB_MIGRATOR_USER IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO $DB_RUNTIME_USER;"
Remove-Item Env:PGPASSWORD
Stop-Process -Id $proxy.Id
```

For an existing database, create and verify a backup first:

```powershell
gcloud sql backups create --instance=$SQL_INSTANCE --description="pre-aitsm-release"
```

Then apply only unapplied migrations. `V002` deliberately removes legacy AI messages without user ownership; review it before applying it to existing data.

## 8. Create runtime secrets

In **Google Cloud Console → Security → Secret Manager**, create these secrets with one enabled version. Commands later assume version `1`.

| Secret name | Value |
| --- | --- |
| `aitsm-db-password` | password for `$DB_RUNTIME_USER` |
| `aitsm-mfa-key` | base64-encoded 32-byte key |
| `aitsm-smtp-username` | SMTP username |
| `aitsm-smtp-password` | SMTP password/API key |
| `aitsm-superadmin-password` | temporary initial admin password of at least 16 characters |

Generate the MFA encryption key:

```powershell
$mfaBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($mfaBytes)
[Convert]::ToBase64String($mfaBytes)
```

Do not regenerate or delete this key after MFA enrollment; losing it makes stored MFA secrets unreadable.

Grant access only to the runtime identity:

```powershell
$secretNames = @(
  "aitsm-db-password",
  "aitsm-mfa-key",
  "aitsm-smtp-username",
  "aitsm-smtp-password",
  "aitsm-superadmin-password"
)

foreach ($secretName in $secretNames) {
  gcloud secrets add-iam-policy-binding $secretName `
    --member="serviceAccount:$SERVICE_ACCOUNT" `
    --role="roles/secretmanager.secretAccessor"
}
```

Pin numeric secret versions in production. Do not use `latest`; version pinning makes releases and rollbacks reproducible.

## 9. Create persistent upload storage

```powershell
gcloud storage buckets create "gs://$BUCKET" `
  --location=$REGION `
  --uniform-bucket-level-access

gcloud storage buckets add-iam-policy-binding "gs://$BUCKET" `
  --member="serviceAccount:$SERVICE_ACCOUNT" `
  --role="roles/storage.objectUser"
```

The volume will use UID/GID `10001`, matching the Docker image's non-root user.

## 10. Build the image

```powershell
gcloud artifacts repositories create $REPOSITORY `
  --repository-format=docker `
  --location=$REGION `
  --description="AITSM production images"

gcloud builds submit --region=$REGION --tag=$IMAGE .
```

The repository Dockerfile runs tests in its build stage. Do not continue unless Cloud Build reports `SUCCESS`.

## 11. Deploy the first revision privately

The app requires an HTTPS base URL, but Cloud Run assigns its URL only after the service exists. Deploy privately with a placeholder, read the generated URL, correct the environment, and only then allow public access.

```powershell
$DB_URL = "jdbc:postgresql:///$DB_NAME`?cloudSqlInstance=$INSTANCE_CONNECTION_NAME&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable"
$PLACEHOLDER_ORIGIN = "https://placeholder.invalid"
$COMPANY_NAME = "CHANGE_ME_COMPANY"
$SUPERADMIN_EMAIL = "CHANGE_ME_ADMIN_EMAIL"
$SMTP_FROM = "CHANGE_ME_VERIFIED_SENDER"
```

```powershell
gcloud run deploy $SERVICE `
  --image=$IMAGE `
  --region=$REGION `
  --execution-environment=gen2 `
  --service-account=$SERVICE_ACCOUNT `
  --no-allow-unauthenticated `
  --cpu=1 `
  --memory=1Gi `
  --concurrency=40 `
  --timeout=300 `
  --min-instances=1 `
  --max-instances=5 `
  --set-env-vars="APP_ENV=production,DB_URL=$DB_URL,DB_USER=$DB_RUNTIME_USER,APP_BASE_URL=$PLACEHOLDER_ORIGIN,CORS_ALLOWED_ORIGINS=$PLACEHOLDER_ORIGIN,COMPANY_NAME=$COMPANY_NAME,OLLAMA_MODEL=qwen3:8b,SMTP_HOST=smtp-relay.brevo.com,SMTP_PORT=587,SMTP_FROM=$SMTP_FROM,SMTP_FROM_NAME=AITSM No Reply,SMTP_STARTTLS=true,AUTH_EXPOSE_DEV_VERIFICATION_CODE=false,PROFILE_UPLOAD_DIR=/app/uploads/profile-photos,BOOTSTRAP_SUPERADMIN=true,SUPERADMIN_EMAIL=$SUPERADMIN_EMAIL,SUPERADMIN_NAME=System Super Admin,SUPERADMIN_COMPANY=$COMPANY_NAME,SUPERADMIN_DEPARTMENT=Platform" `
  --set-secrets="DB_PASSWORD=aitsm-db-password:1,MFA_ENCRYPTION_KEY=aitsm-mfa-key:1,SMTP_USERNAME=aitsm-smtp-username:1,SMTP_PASSWORD=aitsm-smtp-password:1,SUPERADMIN_PASSWORD=aitsm-superadmin-password:1" `
  --add-volume="mount-path=/app/uploads,type=cloud-storage,bucket=$BUCKET,readonly=false,mount-options=uid=10001;gid=10001"
```

Cloud Run supplies `PORT`; do not override it.

Read the assigned URL and replace the placeholder:

```powershell
$APP_URL = gcloud run services describe $SERVICE `
  --region=$REGION `
  --format="value(status.url)"

$APP_URL

gcloud run services update $SERVICE `
  --region=$REGION `
  --update-env-vars="APP_BASE_URL=$APP_URL,CORS_ALLOWED_ORIGINS=$APP_URL"
```

Inspect the revision while it is still private:

```powershell
gcloud run services describe $SERVICE --region=$REGION
gcloud run services logs read $SERVICE --region=$REGION --limit=100
```

Logs must show successful database connection and startup, without missing-production-configuration errors.

## 12. Make the application reachable

AITSM enforces its own authentication, roles, CSRF, and MFA, so users must be able to reach the Cloud Run login page:

```powershell
gcloud run services add-iam-policy-binding $SERVICE `
  --region=$REGION `
  --member="allUsers" `
  --role="roles/run.invoker"
```

If organization policy blocks `allUsers`, ask the Google Cloud administrator to approve the public frontend or use an approved external load balancer.

```powershell
$health = Invoke-RestMethod "$APP_URL/api/health"
$health
```

The endpoint must report OK. Then open `$APP_URL` in a private browser window.

## 13. Complete and remove bootstrap

1. Sign in with the initial super-admin account.
2. Enroll MFA and verify a current authenticator code.
3. Generate recovery codes and store them offline.
4. Confirm the dashboard and Settings page load.

Disable bootstrap immediately:

```powershell
gcloud run services update $SERVICE `
  --region=$REGION `
  --update-env-vars="BOOTSTRAP_SUPERADMIN=false" `
  --remove-secrets="SUPERADMIN_PASSWORD"

gcloud secrets versions destroy 1 --secret="aitsm-superadmin-password"
```

Confirm the new revision is healthy before removing old revisions.

## 14. Production smoke test

Test with separate administrator and end-user accounts:

1. Login, logout, session expiry, and failed-password lockout.
2. Admin MFA and one recovery-code login; a recovery code must work only once.
3. Invitation, registration, verification, reset, and SMTP delivery.
4. Correct admin and end-user routing.
5. Ticket creation, assignment, updates, history, and notifications.
6. Knowledge creation and end-user reading.
7. User approval, role management, and deactivation.
8. Assets, monitoring, inventory, SLA, and Settings.
9. Deterministic AI fallback behavior, Ollama model settings, AI Bridge enable/disable, bridge heartbeat, test job, timeout fallback, and rate limiting.
10. Profile-photo upload, approval, display, and persistence after a new revision.
11. Colleague requests and messaging in two browser sessions.
12. From each role, create and download the Windows collector package. Confirm end users receive `SELF` scope, administrators can receive `LAN` scope, the enrollment redeems only once, the Windows service starts automatically, an HTTPS report is accepted, health changes to Online, and revocation causes later reports to receive HTTP 401.

Apply every ordered migration in `db/migrations/index.txt` before deploying this release. GitHub deployment creates a no-traffic candidate whose startup migrator holds a PostgreSQL advisory lock; traffic moves only after migrations and the candidate health check succeed. Production still disables Exposed runtime schema mutation. Collector installation, scope, credential storage, firewall, AI Bridge verification, and uninstall guidance is in [`../collector/windows/README.md`](../collector/windows/README.md).

Unauthenticated security checks:

```powershell
try { Invoke-WebRequest "$APP_URL/metrics" -UseBasicParsing } catch { $_.Exception.Response.StatusCode.value__ }
try { Invoke-WebRequest "$APP_URL/api/users" -UseBasicParsing } catch { $_.Exception.Response.StatusCode.value__ }
try { Invoke-WebRequest "$APP_URL/uploads/profile-photos/not-a-real-file.png" -UseBasicParsing } catch { $_.Exception.Response.StatusCode.value__ }
```

Protected resources must return `401`, `403`, or redirect to login—not application data.

```powershell
gcloud run services logs read $SERVICE --region=$REGION --limit=300
```

Investigate every unexpected `500`. Logs must not contain passwords, cookies, CSRF values, MFA secrets, recovery codes, reset tokens, SMTP credentials, or future AI Bridge credentials.

## 15. Custom domain

Use the `run.app` URL until the app is stable. For production, Google recommends a global external Application Load Balancer; direct Cloud Run domain mapping remains limited and is not the preferred production option.

After the custom HTTPS hostname works:

```powershell
$CUSTOM_ORIGIN = "https://support.example.com"
gcloud run services update $SERVICE `
  --region=$REGION `
  --update-env-vars="APP_BASE_URL=$CUSTOM_ORIGIN,CORS_ALLOWED_ORIGINS=$CUSTOM_ORIGIN"
```

Do not include paths or a trailing slash in either origin.

## 16. Later releases

For each release: back up before schema changes, pass tests and scans, apply only new migrations with the migration identity, build an immutable commit-SHA image, deploy it, and smoke-test the revision.

```powershell
$IMAGE_TAG = git rev-parse --short HEAD
$IMAGE = "${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPOSITORY}/aitsm:${IMAGE_TAG}"
gcloud builds submit --region=$REGION --tag=$IMAGE .
gcloud run deploy $SERVICE --region=$REGION --image=$IMAGE
```

## 17. Rollback

```powershell
gcloud run revisions list --service=$SERVICE --region=$REGION

gcloud run services update-traffic $SERVICE `
  --region=$REGION `
  --to-revisions="CHANGE_ME_GOOD_REVISION=100"
```

Application and database rollback are separate. If a migration is incompatible with the previous application, restore the verified backup or apply a reviewed forward-fix migration. Routing traffic to an older container does not reverse database changes.

### Database recovery procedure

Restoring a Cloud SQL backup replaces the selected target instance. Never restore into the live production instance until the incident lead has confirmed the target backup, outage window, and rollback decision. Prefer a temporary recovery instance for verification first.

```powershell
# Inspect the known-good backup before any recovery action.
gcloud sql backups list --instance=$SQL_INSTANCE --limit=10

# Restore only after explicitly selecting the production recovery target.
# This is destructive to the target instance.
gcloud sql backups restore BACKUP_ID --restore-instance=$RECOVERY_INSTANCE
```

After recovery, verify the expected databases, run the application health endpoint, test an administrator login, and record the backup ID, recovery time, and outcome in the release record. Do not move Cloud Run traffic until those checks pass.

## 18. Final approval checklist

- GitHub security checks pass, including dependency and secret scans.
- Cloud Build succeeds for the committed SHA.
- Cloud SQL backups and point-in-time recovery are enabled.
- The most recent on-demand backup is recorded and the database recovery procedure is available.
- Migrations complete with `ON_ERROR_STOP=1`.
- Cloud Run is healthy with the final HTTPS origin.
- Admin and end-user smoke tests pass.
- SMTP and deterministic AI fallback tests pass.
- Bootstrap credentials are disabled and destroyed.
- Logs contain no secrets or unexplained `500` responses.
- A known-good revision and database recovery plan are available.

## Official references

- [Deploy images to Cloud Run](https://cloud.google.com/run/docs/deploying)
- [Build and push with Cloud Build](https://cloud.google.com/build/docs/build-push-docker-image)
- [Connect Cloud Run to Cloud SQL](https://cloud.google.com/sql/docs/postgres/connect-run)
- [Configure Cloud Run secrets](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Configure Cloud Storage volume mounts](https://cloud.google.com/run/docs/configuring/services/cloud-storage-volume-mounts)
- [Create Secret Manager secrets](https://cloud.google.com/secret-manager/docs/create-secret-quickstart)
- [Manage Cloud SQL backups](https://cloud.google.com/sql/docs/postgres/backup-recovery/manage-standard-backups)
- [Map a custom domain](https://cloud.google.com/run/docs/mapping-custom-domains)
