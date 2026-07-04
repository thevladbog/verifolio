package com.verifolio.identity

import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

@Import(RecordingMailConfig::class)
class LogoutAndExpiryIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
    }

    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    @Test
    fun `logout revokes the session`() {
        val cookie = login("dave@example.com")
        val csrf = rest.exchange( // obtain XSRF cookie via a GET
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java,
        ).headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
        val xsrf = csrf?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, cookie)
            if (xsrf != null) {
                add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrf")
                add("X-XSRF-TOKEN", xsrf)
            }
        }
        val logout = rest.exchange("/api/v1/auth/sessions/current", HttpMethod.DELETE, HttpEntity<Void>(headers), Void::class.java)
        assertThat(logout.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val after = rest.exchange(
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java,
        )
        assertThat(after.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `expired magic link is rejected`() {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "erin@example.com"), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "erin@example.com" }.textBody)!!.groupValues[1]
        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("erin@example.com"))
            .execute()
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `logout without CSRF token is rejected`() {
        val cookie = login("frank@example.com")
        val logout = rest.exchange("/api/v1/auth/sessions/current", HttpMethod.DELETE,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java)
        assertThat(logout.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }
}
