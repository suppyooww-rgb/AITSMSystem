package backend.services

import backend.config.Env
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

object CollectorInstallerProvider {
    private val lock = Any()
    @Volatile private var cached: Path? = null

    fun openStream(): InputStream = Files.newInputStream(resolve())

    private fun resolve(): Path {
        cached?.takeIf(Files::exists)?.let { return it }
        synchronized(lock) {
            cached?.takeIf(Files::exists)?.let { return it }
            val url = requireNotNull(Env.get("COLLECTOR_INSTALLER_URL")?.trim()?.takeIf(String::isNotBlank)) {
                "COLLECTOR_INSTALLER_URL is not configured"
            }
            require(url.startsWith("https://")) { "COLLECTOR_INSTALLER_URL must use HTTPS" }
            val expected = requireNotNull(Env.get("COLLECTOR_INSTALLER_SHA256")?.trim()?.uppercase()?.takeIf { it.matches(Regex("[0-9A-F]{64}")) }) {
                "COLLECTOR_INSTALLER_SHA256 must be a 64-character hexadecimal checksum"
            }
            val temporary = Files.createTempFile("aitsm-collector-", ".download")
            try {
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(3)).GET().build()
                val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temporary))
                require(response.statusCode() in 200..299) { "Collector release returned HTTP ${response.statusCode()}" }
                val actual = sha256(temporary)
                require(actual == expected) { "Collector installer checksum verification failed" }
                val verified = temporary.resolveSibling("AITSMCollectorSetup-$expected.exe")
                Files.move(temporary, verified, StandardCopyOption.REPLACE_EXISTING)
                cached = verified
                return verified
            } catch (failure: Throwable) {
                Files.deleteIfExists(temporary)
                throw failure
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }
}
