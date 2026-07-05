package com.verifolio.organizations

import com.verifolio.jooq.tables.references.ORGANIZATION
import com.verifolio.jooq.tables.references.ORGANIZATION_DOMAIN
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

@Import(RecordingMailConfig::class)
class OrganizationIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    private val sapId = "a0000000-0000-4000-8000-000000000001"

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    private fun get(path: String, cookie: String) = rest.exchange(
        path, HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }),
        Map::class.java,
    )

    @Test
    fun `list surfaces seeded org with its domains`() {
        val cookie = login("org_list_user@example.com")

        val response = get("/api/v1/organizations?query=SAP", cookie)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<*, *>>
        val sap = items.first { it["id"] == sapId }
        assertThat(sap["name"]).isEqualTo("SAP SE")
        assertThat(sap["verificationStatus"]).isEqualTo("VERIFIED")
        @Suppress("UNCHECKED_CAST")
        val domains = sap["domains"] as List<String>
        assertThat(domains).containsExactlyInAnyOrder("sap.com", "successfactors.com")
    }

    @Test
    fun `query filters by domain prefix`() {
        val cookie = login("org_domain_query_user@example.com")
        insertOrg("Weird Name Co", "uniquedomprefix.test", "VERIFIED")

        val response = get("/api/v1/organizations?query=uniquedomprefix", cookie)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<*, *>>
        assertThat(items.map { it["name"] }).contains("Weird Name Co")
    }

    @Test
    fun `list is keyset paginated`() {
        val cookie = login("org_page_user@example.com")
        for (i in 1..55) {
            insertOrg("Zpage Org ${"%02d".format(i)}", "zpage$i.test", "VERIFIED")
        }

        val page1 = get("/api/v1/organizations?query=Zpage", cookie)
        assertThat(page1.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val items1 = page1.body!!["items"] as List<Map<*, *>>
        val nextCursor1 = page1.body!!["nextCursor"] as String?
        assertThat(items1).hasSize(50)
        assertThat(nextCursor1).isNotNull()

        val page2 = get("/api/v1/organizations?query=Zpage&cursor=$nextCursor1", cookie)
        @Suppress("UNCHECKED_CAST")
        val items2 = page2.body!!["items"] as List<Map<*, *>>
        assertThat(items2).hasSize(5)
        assertThat(page2.body!!["nextCursor"]).isNull()

        val allIds = (items1 + items2).map { it["id"] }
        assertThat(allIds.toSet()).hasSize(55)
    }

    @Test
    fun `get by id returns 200 and 404`() {
        val cookie = login("org_get_user@example.com")

        val ok = get("/api/v1/organizations/$sapId", cookie)
        assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(ok.body!!["name"]).isEqualTo("SAP SE")

        val notFound = get("/api/v1/organizations/${UUID.randomUUID()}", cookie)
        assertThat(notFound.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(notFound.body!!["code"]).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `lookup resolves exact and subdomain, 404 for unknown and unverified`() {
        val cookie = login("org_lookup_user@example.com")

        val exact = get("/api/v1/organizations/lookup?domain=sap.com", cookie)
        assertThat(exact.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(exact.body!!["id"]).isEqualTo(sapId)

        val sub = get("/api/v1/organizations/lookup?domain=careers.sap.com", cookie)
        assertThat(sub.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(sub.body!!["id"]).isEqualTo(sapId)

        val unknown = get("/api/v1/organizations/lookup?domain=gmail.com", cookie)
        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)

        insertOrg("Unverified Co", "unverified-lookup.test", "UNVERIFIED")
        val unverified = get("/api/v1/organizations/lookup?domain=unverified-lookup.test", cookie)
        assertThat(unverified.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `unauthenticated request is rejected`() {
        val response = rest.getForEntity("/api/v1/organizations", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun insertOrg(name: String, domain: String, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(ORGANIZATION)
            .set(ORGANIZATION.ID, id)
            .set(ORGANIZATION.NAME, name)
            .set(ORGANIZATION.DOMAINS, JSONB.valueOf("[\"$domain\"]"))
            .set(ORGANIZATION.VERIFICATION_STATUS, status)
            .execute()
        dsl.insertInto(ORGANIZATION_DOMAIN)
            .set(ORGANIZATION_DOMAIN.ORGANIZATION_ID, id)
            .set(ORGANIZATION_DOMAIN.DOMAIN, domain)
            .execute()
        return id
    }
}
