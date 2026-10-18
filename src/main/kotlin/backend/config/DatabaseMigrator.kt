package backend.config

import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime

object DatabaseMigrator {
    private const val LOCK_ID = 4_149_453_877L

    fun migrate() {
        val url = requireNotNull(Env.get("DB_URL")) { "DB_URL is required for database migrations" }
        val user = requireNotNull(Env.get("DB_USER")) { "DB_USER is required for database migrations" }
        val password = requireNotNull(Env.get("DB_PASSWORD")) { "DB_PASSWORD is required for database migrations" }
        val baseline = Env.get("DB_MIGRATION_BASELINE")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        Class.forName("org.postgresql.Driver")
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("SELECT pg_advisory_lock($LOCK_ID)") }
            try {
                val createdFromSnapshot = ensureBaseSchema(connection)
                ensureTrackingTable(connection)
                val migrations = loadMigrations()
                baselineExistingDatabase(connection, migrations, if (createdFromSnapshot) migrations.maxOf { it.version } else baseline)
                migrations.forEach { migration -> applyMigration(connection, migration) }
            } finally {
                runCatching { connection.createStatement().use { it.execute("SELECT pg_advisory_unlock($LOCK_ID)") } }
            }
        }
    }

    private fun ensureBaseSchema(connection: Connection): Boolean {
        val exists = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT to_regclass('public.users') IS NOT NULL").use { result -> result.next(); result.getBoolean(1) }
        }
        if (exists) return false
        val sql = DatabaseMigrator::class.java.classLoader.getResourceAsStream("db/schema.sql")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Base database schema resource is missing")
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute(sql) }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw IllegalStateException("Base database schema creation failed", failure)
        } finally {
            connection.autoCommit = true
        }
        return true
    }

    private fun ensureTrackingTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS schema_migrations (
                  version INTEGER PRIMARY KEY,
                  name VARCHAR(240) NOT NULL,
                  checksum VARCHAR(64) NOT NULL,
                  applied_at TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    private fun baselineExistingDatabase(connection: Connection, migrations: List<Migration>, baseline: Int) {
        if (baseline <= 0) return
        val count = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM schema_migrations").use { result -> result.next(); result.getLong(1) }
        }
        if (count != 0L) return
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO schema_migrations(version, name, checksum, applied_at) VALUES (?, ?, 'baseline', ?)").use { statement ->
                migrations.filter { it.version <= baseline }.forEach { migration ->
                    statement.setInt(1, migration.version)
                    statement.setString(2, migration.name)
                    statement.setObject(3, LocalDateTime.now())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = true
        }
    }

    private fun applyMigration(connection: Connection, migration: Migration) {
        val recordedChecksum = connection.prepareStatement("SELECT checksum FROM schema_migrations WHERE version = ?").use { statement ->
            statement.setInt(1, migration.version)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }
        if (recordedChecksum != null) {
            require(recordedChecksum == "baseline" || recordedChecksum == migration.checksum) {
                "Migration V${migration.version} checksum changed after it was applied"
            }
            return
        }
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute(migration.sql) }
            connection.prepareStatement("INSERT INTO schema_migrations(version, name, checksum, applied_at) VALUES (?, ?, ?, ?)").use { statement ->
                statement.setInt(1, migration.version)
                statement.setString(2, migration.name)
                statement.setString(3, migration.checksum)
                statement.setObject(4, LocalDateTime.now())
                statement.executeUpdate()
            }
            connection.commit()
        } catch (failure: Throwable) {
            connection.rollback()
            throw IllegalStateException("Database migration ${migration.name} failed", failure)
        } finally {
            connection.autoCommit = true
        }
    }

    private fun loadMigrations(): List<Migration> {
        val loader = DatabaseMigrator::class.java.classLoader
        val names = loader.getResourceAsStream("db/migrations/index.txt")
            ?.bufferedReader()?.use { it.readLines() }
            ?.map(String::trim)?.filter { it.isNotBlank() && !it.startsWith('#') }
            ?: error("Database migration index is missing")
        return names.map { name ->
            val version = Regex("^V(\\d+)__.+\\.sql$").matchEntire(name)?.groupValues?.get(1)?.toInt()
                ?: error("Invalid migration filename: $name")
            val sql = loader.getResourceAsStream("db/migrations/$name")?.bufferedReader()?.use { it.readText() }
                ?: error("Migration resource is missing: $name")
            Migration(version, name, sha256(sql), sql)
        }.also { migrations ->
            require(migrations.map { it.version } == migrations.map { it.version }.sorted()) { "Migration index must be ordered" }
            require(migrations.map { it.version }.distinct().size == migrations.size) { "Migration versions must be unique" }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class Migration(val version: Int, val name: String, val checksum: String, val sql: String)
}
