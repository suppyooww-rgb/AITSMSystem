package backend.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiProviderContractTest {
    @Test fun `gemini provider uses generate content API`() {
        val source = Files.readString(Path.of("src/main/kotlin/backend/services/ai/GeminiProvider.kt"))

        assertTrue(source.contains(":generateContent"))
        assertTrue(source.contains("generationConfig"))
        assertTrue(source.contains("candidates"))
        assertTrue(source.contains("gemini-flash-latest"))
        assertTrue(source.contains("response.statusCode() in setOf(408, 429) || response.statusCode() >= 500"))
        assertTrue(source.contains("ThreadLocalRandom"))
        assertFalse(source.contains("/v1beta/interactions"))
    }
}
