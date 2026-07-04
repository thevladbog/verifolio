package com.verifolio.templates

import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
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
class TemplateIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    private val expectedTypes = setOf(
        "EMPLOYMENT_REFERENCE",
        "IMMIGRATION_REFERENCE",
        "VISA_SUPPORT_LETTER",
        "ACADEMIC_RECOMMENDATION",
        "CLIENT_TESTIMONIAL",
        "CHARACTER_REFERENCE",
    )

    /** POST magic-link, extract token, POST session, return the session cookie value. */
    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    @Test
    fun `lists the six seeded en templates`() {
        val cookie = login("template_list@example.com")

        val response = rest.exchange(
            "/api/v1/templates",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<*, *>>
        assertThat(items).hasSize(6)

        val types = items.map { it["type"] as String }.toSet()
        assertThat(types).isEqualTo(expectedTypes)

        items.forEach { item ->
            assertThat(item["name"]).isNotNull()
            assertThat(item["description"]).isNotNull()
            assertThat(item["locale"]).isEqualTo("en")
        }
    }

    @Test
    fun `locale filter returns empty for unseeded locale`() {
        val cookie = login("template_locale@example.com")

        val response = rest.exchange(
            "/api/v1/templates?locale=de",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<*, *>>
        assertThat(items).isEmpty()
    }

    @Test
    fun `get by id returns full schemas as JSON objects`() {
        val cookie = login("template_detail@example.com")

        // Get the list first to obtain an ID
        val listResponse = rest.exchange(
            "/api/v1/templates",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        @Suppress("UNCHECKED_CAST")
        val items = listResponse.body!!["items"] as List<Map<*, *>>
        val id = items.first()["id"] as String

        val detailResponse = rest.exchange(
            "/api/v1/templates/$id",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(detailResponse.statusCode).isEqualTo(HttpStatus.OK)
        val body = detailResponse.body!!

        // questionSchema must be a JSON object (map), not a string
        @Suppress("UNCHECKED_CAST")
        val questionSchema = body["questionSchema"] as Map<*, *>
        assertThat(questionSchema["requesterQuestions"]).isInstanceOf(List::class.java)
        assertThat(questionSchema["recommenderQuestions"]).isInstanceOf(List::class.java)

        // requiredFields and verificationRecommendations must be arrays (lists)
        assertThat(body["requiredFields"]).isInstanceOf(List::class.java)
        assertThat(body["verificationRecommendations"]).isInstanceOf(List::class.java)
    }

    @Test
    fun `unknown id returns 404`() {
        val cookie = login("template_404@example.com")

        val response = rest.exchange(
            "/api/v1/templates/00000000-0000-0000-0000-000000000000",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `unauthenticated access is rejected`() {
        val response = rest.getForEntity("/api/v1/templates", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
