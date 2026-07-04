package com.verifolio.contacts

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class ContactIntegrationTest : IntegrationTest() {

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

    /** GET /api/v1/contacts to obtain the XSRF token from the Set-Cookie header. */
    private fun xsrf(cookie: String): String? {
        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        return response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
    }

    private fun authHeaders(cookie: String, xsrfToken: String?): HttpHeaders = HttpHeaders().apply {
        add(HttpHeaders.COOKIE, cookie)
        set(HttpHeaders.CONTENT_TYPE, "application/json")
        if (xsrfToken != null) {
            add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrfToken")
            add("X-XSRF-TOKEN", xsrfToken)
        }
    }

    @Test
    fun `create contact returns 201 and is audited`() {
        val cookie = login("contact_creator@example.com")
        val xsrfToken = xsrf(cookie)

        val body = mapOf(
            "name" to "Ivan Petrov",
            "email" to "ivan@corp.example.com",
            "companyName" to "Corp",
            "companyDomain" to "corp.example.com",
            "title" to "CTO",
            "relationshipType" to "MANAGER",
        )

        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        val resp = response.body!!
        assertThat(resp["id"]).isNotNull()
        assertThat(resp["name"]).isEqualTo("Ivan Petrov")
        assertThat(resp["email"]).isEqualTo("ivan@corp.example.com")
        assertThat(resp["relationshipType"]).isEqualTo("MANAGER")
        assertThat(resp["createdAt"]).isNotNull()

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("CONTACT_CREATED")
    }

    @Test
    fun `malformed UUID path variable returns 400 not 500`() {
        val cookie = login("uuid_validation_user@example.com")

        val response = rest.exchange(
            "/api/v1/contacts/not-a-uuid", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `invalid relationship type is rejected`() {
        val cookie = login("invalid_rel_user@example.com")
        val xsrfToken = xsrf(cookie)

        val body = mapOf(
            "name" to "Bad Contact",
            "email" to "bad@corp.example.com",
            "relationshipType" to "BUDDY",
        )

        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `invalid email format is rejected`() {
        val cookie = login("email_validation_user@example.com")
        val xsrfToken = xsrf(cookie)

        val body = mapOf(
            "name" to "Bad Email Contact",
            "email" to "not-an-email",
            "relationshipType" to "COLLEAGUE",
        )

        val response = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(body, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }

    @Test
    fun `contacts are isolated per owner`() {
        val cookieA = login("owner_a_contacts@example.com")
        val xsrfA = xsrf(cookieA)

        val createBody = mapOf(
            "name" to "Alice Contact",
            "email" to "alice.contact@example.com",
            "relationshipType" to "COLLEAGUE",
        )

        val createResponse = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(createBody, authHeaders(cookieA, xsrfA)),
            Map::class.java,
        )
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val contactId = createResponse.body!!["id"].toString()

        // User B cannot access User A's contact by ID
        val cookieB = login("owner_b_contacts@example.com")
        val xsrfB = xsrf(cookieB)

        val getResponse = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookieB) }),
            Map::class.java,
        )
        assertThat(getResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(getResponse.body!!["code"]).isEqualTo("NOT_FOUND")

        // User B's list does not contain User A's contact
        val listResponse = rest.exchange(
            "/api/v1/contacts", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookieB) }),
            Map::class.java,
        )
        assertThat(listResponse.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = listResponse.body!!["items"] as List<Map<*, *>>
        assertThat(items.map { it["id"] }).doesNotContain(contactId)

        // User B's PUT on User A's contact → 404
        val putBody = mapOf(
            "name" to "Alice Contact",
            "email" to "alice.contact@example.com",
            "relationshipType" to "COLLEAGUE",
        )
        val putResponse = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.PUT,
            HttpEntity(putBody, authHeaders(cookieB, xsrfB)),
            Map::class.java,
        )
        assertThat(putResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(putResponse.body!!["code"]).isEqualTo("NOT_FOUND")

        // User B's DELETE on User A's contact → 404
        val deleteResponse = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(cookieB, xsrfB)),
            Map::class.java,
        )
        assertThat(deleteResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(deleteResponse.body!!["code"]).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `contact can be updated and deleted with audit`() {
        val cookie = login("updater_contacts@example.com")
        val xsrfToken = xsrf(cookie)

        val createBody = mapOf(
            "name" to "Update Me",
            "email" to "updateme.contact@example.com",
            "title" to "Engineer",
            "relationshipType" to "COLLEAGUE",
        )
        val createResponse = rest.exchange(
            "/api/v1/contacts", HttpMethod.POST,
            HttpEntity(createBody, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(createResponse.statusCode).isEqualTo(HttpStatus.CREATED)
        val contactId = createResponse.body!!["id"].toString()
        val updatedAtAfterCreate = createResponse.body!!["updatedAt"] as String?

        // Update the title
        val updateBody = mapOf(
            "name" to "Update Me",
            "email" to "updateme.contact@example.com",
            "title" to "Senior Engineer",
            "relationshipType" to "COLLEAGUE",
        )
        val updateResponse = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.PUT,
            HttpEntity(updateBody, authHeaders(cookie, xsrfToken)),
            Map::class.java,
        )
        assertThat(updateResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(updateResponse.body!!["title"]).isEqualTo("Senior Engineer")

        // updatedAt must be present and must differ from the value after create
        val updatedAtAfterPut = updateResponse.body!!["updatedAt"] as String?
        assertThat(updatedAtAfterPut).isNotNull()
        assertThat(updatedAtAfterPut).isNotEqualTo(updatedAtAfterCreate)

        // Delete the contact
        val deleteResponse = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(cookie, xsrfToken)),
            Void::class.java,
        )
        assertThat(deleteResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        // GET after delete returns 404
        val getAfterDelete = rest.exchange(
            "/api/v1/contacts/$contactId", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(getAfterDelete.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        // Audit events
        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("CONTACT_UPDATED")
        assertThat(actions).contains("CONTACT_DELETED")
    }

    @Test
    fun `list is keyset paginated`() {
        val cookie = login("paginated_contacts@example.com")
        val xsrfToken = xsrf(cookie)

        // Create 55 contacts
        for (i in 1..55) {
            val body = mapOf(
                "name" to "Contact $i",
                "email" to "contact${i}@pagtest.example.com",
                "relationshipType" to "COLLEAGUE",
            )
            rest.exchange(
                "/api/v1/contacts", HttpMethod.POST,
                HttpEntity(body, authHeaders(cookie, xsrfToken)),
                Map::class.java,
            )
        }

        // First page: expect 50 items and a non-null nextCursor
        val page1 = rest.exchange(
            "/api/v1/contacts", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(page1.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items1 = page1.body!!["items"] as List<Map<*, *>>
        val nextCursor1 = page1.body!!["nextCursor"] as String?

        assertThat(items1).hasSize(50)
        assertThat(nextCursor1).isNotNull()

        // Second page: expect the remaining 5 items and null nextCursor
        val page2 = rest.exchange(
            "/api/v1/contacts?cursor=$nextCursor1", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(page2.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items2 = page2.body!!["items"] as List<Map<*, *>>
        val nextCursor2 = page2.body!!["nextCursor"] as String?

        assertThat(items2).hasSize(5)
        assertThat(nextCursor2).isNull()

        // No overlaps or gaps — all 55 unique IDs
        val allIds = (items1 + items2).map { it["id"] }
        assertThat(allIds).hasSize(55)
        assertThat(allIds.toSet()).hasSize(55)

        // Invalid cursor → 400 VALIDATION_ERROR
        val badCursorResponse = rest.exchange(
            "/api/v1/contacts?cursor=garbage", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
            Map::class.java,
        )
        assertThat(badCursorResponse.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(badCursorResponse.body!!["code"]).isEqualTo("VALIDATION_ERROR")
    }
}
