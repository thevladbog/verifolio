package com.verifolio.platform.web

import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import java.nio.file.Files
import java.nio.file.Path

class OpenApiContractTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate

    /** Normalize the random RANDOM_PORT in `servers:` to a stable placeholder. */
    private fun normalize(yaml: String): String =
        yaml.replace(Regex("url: http://localhost:\\d+"), "url: http://localhost:8080")

    @Test
    fun `spec is OpenAPI 3_1 and documents auth endpoints`() {
        val spec = rest.getForObject("/v3/api-docs.yaml", String::class.java)!!
        assertThat(spec).contains("openapi: 3.1")
        assertThat(spec).contains("/api/v1/auth/magic-links")
        assertThat(spec).contains("/api/v1/auth/sessions")
    }

    @Test
    fun `scalar UI is served at docs`() {
        val html = rest.getForObject("/docs", String::class.java)!!
        assertThat(html).contains("api-reference")
    }

    @Test
    fun `committed openapi yaml matches the served spec`() {
        val served = rest.getForObject("/v3/api-docs.yaml", String::class.java)!!
        val normalizedServed = normalize(served)
        val snapshotPath = Path.of(System.getProperty("user.dir"), "api/openapi.yaml")
        if (System.getenv("UPDATE_OPENAPI") == "true") {
            Files.createDirectories(snapshotPath.parent)
            Files.writeString(snapshotPath, normalizedServed)
        }
        assertThat(snapshotPath).exists()
        assertThat(Files.readString(snapshotPath))
            .withFailMessage("openapi.yaml is stale. Re-run with UPDATE_OPENAPI=true and commit the diff.")
            .isEqualTo(normalizedServed)
    }
}
