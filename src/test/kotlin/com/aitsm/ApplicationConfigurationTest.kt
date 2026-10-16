package com.aitsm

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ApplicationConfigurationTest {
    private fun productionValues() = mutableMapOf(
        "DB_URL" to "jdbc:postgresql://db.internal:5432/aitsm?sslmode=verify-full",
        "DB_USER" to "aitsm_runtime",
        "DB_PASSWORD" to "not-a-real-password",
        "APP_BASE_URL" to "https://aitsm.example.com",
        "COMPANY_NAME" to "Example Company",
        "CORS_ALLOWED_ORIGINS" to "https://aitsm.example.com",
        "MFA_ENCRYPTION_KEY" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        "SMTP_HOST" to "smtp.example.com",
        "SMTP_PORT" to "587",
        "SMTP_USERNAME" to "smtp-user",
        "SMTP_PASSWORD" to "not-a-real-smtp-password",
        "SMTP_FROM" to "no-reply@example.com",
        "SMTP_STARTTLS" to "true",
        "AUTH_EXPOSE_DEV_VERIFICATION_CODE" to "false"
    )

    @Test
    fun `valid production configuration passes the deployment gate`() {
        val values = productionValues()
        validateRuntimeConfiguration("production", values::get)
    }

    @Test
    fun `production rejects insecure database transport`() {
        val values = productionValues().apply {
            this["DB_URL"] = "jdbc:postgresql://db.internal:5432/aitsm?sslmode=require"
        }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `production rejects misleading database TLS parameters`() {
        val values = productionValues().apply {
            this["DB_URL"] = "jdbc:postgresql://db.internal:5432/aitsm?note=sslmode=verify-full&sslmode=require"
        }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `production accepts the authenticated Cloud SQL Java connector`() {
        val values = productionValues().apply {
            this["DB_URL"] = "jdbc:postgresql:///aitsm?cloudSqlInstance=my-project:asia-southeast1:aitsm-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory&sslmode=disable"
        }
        validateRuntimeConfiguration("production", values::get)
    }

    @Test
    fun `production rejects an unrecognized database socket factory`() {
        val values = productionValues().apply {
            this["DB_URL"] = "jdbc:postgresql:///aitsm?cloudSqlInstance=my-project:asia-southeast1:aitsm-db&socketFactory=example.InsecureSocketFactory&sslmode=disable"
        }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `production requires SMTP STARTTLS explicitly`() {
        val values = productionValues().apply { this["SMTP_STARTTLS"] = "yes" }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `production rejects empty CORS origin entries`() {
        val values = productionValues().apply {
            this["CORS_ALLOWED_ORIGINS"] = "https://aitsm.example.com,"
        }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `production rejects development verification codes`() {
        val values = productionValues().apply { this["AUTH_EXPOSE_DEV_VERIFICATION_CODE"] = "true" }
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("production", values::get)
        }
    }

    @Test
    fun `unknown environment names fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            validateRuntimeConfiguration("prod", emptyMap<String, String>()::get)
        }
    }
}
