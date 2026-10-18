package backend.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseMigrationContractTest {
    @Test fun `migration index lists every migration exactly once in order`() {
        val directory = Path.of("db/migrations")
        val indexed = Files.readAllLines(directory.resolve("index.txt")).map(String::trim).filter { it.isNotBlank() }
        val actual = Files.list(directory).use { files ->
            files.filter { it.name.matches(Regex("V\\d+__.+\\.sql")) }.map { it.name }.sorted().toList()
        }
        assertEquals(actual, indexed)
        assertTrue(indexed.last().startsWith("V020__"))
    }

    @Test fun `deployment runs migrations before candidate health verification`() {
        val migrator = Files.readString(Path.of("src/main/kotlin/backend/config/DatabaseMigrator.kt"))
        val workflow = Files.readString(Path.of(".github/workflows/security.yml"))
        assertTrue(migrator.contains("ensureBaseSchema"))
        assertTrue(migrator.contains("db/schema.sql"))
        assertTrue(workflow.contains("--no-traffic"))
        assertTrue(workflow.contains("DB_MIGRATE_ON_START=true"))
        assertTrue(workflow.indexOf("DB_MIGRATE_ON_START=true") < workflow.indexOf("CANDIDATE_URL"))
        assertTrue(workflow.contains("traffic was restored"))
    }

    @Test fun `deployment removes legacy Gemini configuration before promotion`() {
        val workflow = Files.readString(Path.of(".github/workflows/security.yml"))
        assertTrue(workflow.contains("LEGACY_AI_ENV_VARS"))
        assertTrue(workflow.contains("AI_PROVIDER"))
        assertTrue(workflow.contains("GEMINI_MODEL"))
        assertTrue(workflow.contains("GEMINI_API_KEY"))
        assertTrue(workflow.contains("OLLAMA_MODEL=qwen3:8b"))
        assertTrue(workflow.contains("LEGACY_AI_ENV_COUNT"))
        assertTrue(workflow.indexOf("LEGACY_AI_ENV_COUNT") < workflow.indexOf("CANDIDATE_URL"))
    }

    @Test fun `deployment baselines only the schema version known to be present in production`() {
        val workflow = Files.readString(Path.of(".github/workflows/security.yml"))
        assertTrue(workflow.contains("DB_MIGRATION_BASELINE=12"))
        assertTrue(Files.readString(Path.of("db/migrations/V018__repair_skipped_release_schema.sql")).contains("archived_at"))
        assertTrue(Files.readString(Path.of("db/migrations/V019__repair_legacy_collector_and_sla_schema.sql")).contains("agent_version"))
        assertTrue(Files.readString(Path.of("db/migrations/V020__repair_legacy_collector_enrollment_schema.sql")).contains("collector_enrollment_codes"))
    }
}
