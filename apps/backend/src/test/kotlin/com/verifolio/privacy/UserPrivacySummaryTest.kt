package com.verifolio.privacy

import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/** Privacy user summary: a user's consents + DSR counts by status (owner-scoped). */
class UserPrivacySummaryTest : IntegrationTest() {

    @Autowired lateinit var summary: UserPrivacySummary
    @Autowired lateinit var dsl: DSLContext

    private fun seedUser(): UUID {
        val id = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, id)
            .set(ua.EMAIL, "privacy-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU")
            .set(ua.STATUS, "ACTIVE")
            .execute()
        return id
    }

    private fun seedConsent(userId: UUID, status: String, createdAt: OffsetDateTime) {
        val c = CONSENT_RECORD
        val now = OffsetDateTime.now()
        dsl.insertInto(c)
            .set(c.ID, UUID.randomUUID())
            .set(c.SUBJECT_TYPE, "REQUESTER")
            .set(c.USER_ID, userId)
            .set(c.CONSENT_TYPE, "REQUESTER_VERBAL_CONSENT_ATTESTATION")
            .set(c.POLICY_TEXT_VERSION, "v1")
            .set(c.REGION, "EU")
            .set(c.STATUS, status)
            .set(c.GRANTED_AT, if (status == "GRANTED" || status == "WITHDRAWN") now else null)
            .set(c.WITHDRAWN_AT, if (status == "WITHDRAWN") now else null)
            .set(c.CREATED_AT, createdAt)
            .execute()
    }

    private fun seedDsr(userId: UUID, status: String) {
        val d = DATA_SUBJECT_REQUEST
        val now = OffsetDateTime.now()
        dsl.insertInto(d)
            .set(d.ID, UUID.randomUUID())
            .set(d.TYPE, "EXPORT")
            .set(d.STATUS, status)
            .set(d.REGION, "EU")
            .set(d.SUBJECT_EMAIL, "subj-${UUID.randomUUID()}@example.com")
            .set(d.USER_ID, userId)
            .set(d.DUE_AT, now.plusDays(30))
            .execute()
    }

    @Test
    fun `forUser returns the user's consents and DSR counts by status`() {
        val userId = seedUser()
        val base = OffsetDateTime.now()
        seedConsent(userId, "GRANTED", base)
        seedConsent(userId, "WITHDRAWN", base.plusSeconds(1))
        seedDsr(userId, "RECEIVED")
        seedDsr(userId, "RECEIVED")
        seedDsr(userId, "EXECUTED")

        val data = summary.forUser(userId)

        assertThat(data.consents.map { it.status }).containsExactly("GRANTED", "WITHDRAWN")
        val withdrawn = data.consents.first { it.status == "WITHDRAWN" }
        assertThat(withdrawn.withdrawnAt).isNotNull()
        assertThat(withdrawn.policyTextVersion).isEqualTo("v1")
        assertThat(data.dsrCountsByStatus).isEqualTo(mapOf("RECEIVED" to 2, "EXECUTED" to 1))
    }

    @Test
    fun `forUser is empty for a user with no consents or DSRs`() {
        val data = summary.forUser(seedUser())
        assertThat(data.consents).isEmpty()
        assertThat(data.dsrCountsByStatus).isEmpty()
    }
}
