package com.verifolio.identity

import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class MagicLinkRequestIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @Test
    fun `requesting a magic link stores only a hash and mails the raw token`() {
        val response = rest.postForEntity(
            "/api/v1/auth/magic-links",
            mapOf("email" to "User@Example.com "),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val sent = mail.sent.single { it.to == "user@example.com" }
        val rawToken = Regex("token=([A-Za-z0-9_-]+)").find(sent.textBody)!!.groupValues[1]

        val stored = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("user@example.com"))
            .fetch()
        assertThat(stored).hasSize(1)
        assertThat(stored.first().tokenHash).isNotEqualTo(rawToken)
        assertThat(stored.first().tokenHash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `response is identical for unknown emails (anti-enumeration)`() {
        val a = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "nobody@example.com"), String::class.java)
        val b = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "nobody2@example.com"), String::class.java)
        assertThat(a.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(a.body).isEqualTo(b.body)
    }

    @Test
    fun `invalid email returns 202 with same body, sends no mail, stores no token`() {
        val valid = rest.postForEntity(
            "/api/v1/auth/magic-links",
            mapOf("email" to "valid@example.com"),
            Map::class.java,
        )
        val invalid = rest.postForEntity(
            "/api/v1/auth/magic-links",
            mapOf("email" to "not-an-email"),
            Map::class.java,
        )
        assertThat(invalid.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(invalid.body).isEqualTo(valid.body)
        assertThat(mail.sent.none { it.to == "not-an-email" }).isTrue()
        val stored = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("not-an-email"))
            .fetch()
        assertThat(stored).isEmpty()
    }

    @Test
    fun `re-requesting invalidates previous tokens`() {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "twice@example.com"), Map::class.java)
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "twice@example.com"), Map::class.java)
        val active = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("twice@example.com"))
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .fetch()
        assertThat(active).hasSize(1)
    }
}
