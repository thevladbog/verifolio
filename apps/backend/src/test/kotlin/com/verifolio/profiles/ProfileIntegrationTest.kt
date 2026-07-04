package com.verifolio.profiles

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
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
class ProfileIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    /** POST magic-link, extract token, POST session, return the session cookie value. */
    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    /** Poll GET /api/v1/profile until 200 or timeout (5 seconds). */
    private fun pollUntilProfileReady(cookie: String): Map<*, *> {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val response = rest.exchange(
                "/api/v1/profile", HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
                Map::class.java,
            )
            if (response.statusCode == HttpStatus.OK) return response.body!!
            Thread.sleep(100)
        }
        error("Profile was not created within 5 seconds")
    }

    @Test
    fun `first login auto-creates a profile with defaults and audit event`() {
        val cookie = login("newuser@example.com")

        val profile = pollUntilProfileReady(cookie)

        assertThat(profile["displayName"]).isEqualTo("newuser")
        assertThat(profile["preferredLocale"]).isEqualTo("en")

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("PROFILE_CREATED")
    }

    @Test
    fun `second login does not create a second profile`() {
        login("sameuser@example.com")
        login("sameuser@example.com")

        val account = dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq("sameuser@example.com"))
            .fetchOne()!!

        val profileCount = dsl.selectCount().from(PERSON_PROFILE)
            .where(PERSON_PROFILE.USER_ACCOUNT_ID.eq(account.id))
            .fetchOne(0, Int::class.java)!!

        assertThat(profileCount).isEqualTo(1)
    }

    @Test
    fun `profile can be updated and update is audited`() {
        val cookie = login("updateme@example.com")

        // Wait for profile to be auto-created, then extract XSRF token
        pollUntilProfileReady(cookie)

        val getResponse = rest.exchange(
            "/api/v1/profile", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        val xsrf = getResponse.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")

        // Valid update
        val putHeaders = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, cookie)
            set(HttpHeaders.CONTENT_TYPE, "application/json")
            if (xsrf != null) {
                add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrf")
                add("X-XSRF-TOKEN", xsrf)
            }
        }
        val putBody = mapOf("displayName" to "New Name", "legalName" to "Legal Name", "preferredLocale" to "ru")
        val putResponse = rest.exchange(
            "/api/v1/profile", HttpMethod.PUT,
            HttpEntity(putBody, putHeaders),
            Map::class.java,
        )
        assertThat(putResponse.statusCode).isEqualTo(HttpStatus.OK)

        // GET reflects changes
        val getAfter = rest.exchange(
            "/api/v1/profile", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(getAfter.body!!["displayName"]).isEqualTo("New Name")
        assertThat(getAfter.body!!["legalName"]).isEqualTo("Legal Name")
        assertThat(getAfter.body!!["preferredLocale"]).isEqualTo("ru")

        // Audit contains PROFILE_UPDATED
        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("PROFILE_UPDATED")

        // Invalid locale → 400 with VALIDATION_ERROR
        val badBody = mapOf("displayName" to "X", "legalName" to null, "preferredLocale" to "xx")
        val badResponse = rest.exchange(
            "/api/v1/profile", HttpMethod.PUT,
            HttpEntity(badBody, putHeaders),
            Map::class.java,
        )
        assertThat(badResponse.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(badResponse.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `unauthenticated profile access is rejected`() {
        val response = rest.getForEntity("/api/v1/profile", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
