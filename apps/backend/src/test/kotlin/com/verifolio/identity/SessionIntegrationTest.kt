package com.verifolio.identity

import com.verifolio.jooq.tables.references.AUDIT_EVENT
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

@Import(RecordingMailConfig::class)
class SessionIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    private fun obtainRawToken(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val sent = mail.sent.last { it.to == email }
        return Regex("token=([A-Za-z0-9_-]+)").find(sent.textBody)!!.groupValues[1]
    }

    @Test
    fun `consuming a valid token creates account, session cookie and audit trail`() {
        val token = obtainRawToken("alice@example.com")
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val cookie = response.headers[HttpHeaders.SET_COOKIE]!!.first { it.startsWith("verifolio_session=") }
        assertThat(cookie).contains("HttpOnly")
        assertThat(cookie).contains("SameSite=Strict")

        val me = rest.exchange(
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie.substringBefore(";")) }),
            Map::class.java,
        )
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(me.body!!["email"]).isEqualTo("alice@example.com")

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("MAGIC_LINK_CONSUMED", "LOGIN_SUCCEEDED", "SESSION_CREATED")
    }

    @Test
    fun `a token cannot be consumed twice`() {
        val token = obtainRawToken("bob@example.com")
        rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        val second = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        assertThat(second.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("LOGIN_FAILED")
    }

    @Test
    fun `an invalidated token is rejected`() {
        val first = obtainRawToken("carol@example.com")
        obtainRawToken("carol@example.com") // reissue invalidates the first
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to first), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `unauthenticated current-session request is rejected`() {
        val me = rest.getForEntity("/api/v1/auth/sessions/current", Map::class.java)
        assertThat(me.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
