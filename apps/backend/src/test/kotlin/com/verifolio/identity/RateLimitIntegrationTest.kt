package com.verifolio.identity

import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class RateLimitIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate

    @Test
    fun `magic link requests per email are rate limited with RATE_LIMITED code`() {
        repeat(5) {
            val ok = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "spam@example.com"), Map::class.java)
            assertThat(ok.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }
        val sixth = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "spam@example.com"), Map::class.java)
        assertThat(sixth.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(sixth.body!!["code"]).isEqualTo("RATE_LIMITED")
    }
}
