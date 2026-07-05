package com.verifolio.privacy

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.CONSENT_RECORD
import com.verifolio.jooq.tables.references.DATA_SUBJECT_REQUEST
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.VERIFICATION_SIGNAL
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Account-less recommender DSR channel: anti-enumeration intake, emailed code, and immediate
 * CONSENT_WITHDRAWAL execution across the subject's completed request (consents withdrawn,
 * signals revoked, versions retracted, PII erased, DSR EXECUTED), plus the code lockout.
 */
class RecommenderDsrChannelTest : PrivacyFlowSupport() {

    private fun intake(email: String): HttpStatus {
        val response = rest.postForEntity(
            "/api/v1/privacy/recommender-requests",
            mapOf("email" to email),
            Void::class.java,
        )
        return response.statusCode as HttpStatus
    }

    private fun codeMail(email: String): Pair<UUID, String> {
        val body = mail.sent.last { it.to == email }.textBody
        val dsrId = Regex("/data-requests/([0-9a-fA-F-]{36})").find(body)!!.groupValues[1]
        val code = Regex("Verification code: (\\d{6})").find(body)!!.groupValues[1]
        return UUID.fromString(dsrId) to code
    }

    private fun verify(dsrId: UUID, code: String, type: String = "CONSENT_WITHDRAWAL") =
        rest.exchange(
            "/api/v1/privacy/recommender-requests/$dsrId/verify", HttpMethod.POST,
            HttpEntity(
                mapOf("code" to code, "type" to type),
                HttpHeaders().apply { set(HttpHeaders.CONTENT_TYPE, "application/json") },
            ),
            Map::class.java,
        )

    @Test
    fun `unknown email is accepted with no code and no DSR row`() {
        val unknown = "nobody_${UUID.randomUUID()}@example.com"
        assertThat(intake(unknown)).isEqualTo(HttpStatus.ACCEPTED)

        assertThat(mail.sent.any { it.to == unknown }).isFalse()
        assertThat(dsl.fetchCount(DATA_SUBJECT_REQUEST, DATA_SUBJECT_REQUEST.SUBJECT_EMAIL.eq(unknown))).isZero()
    }

    @Test
    fun `verified consent withdrawal withdraws consents, revokes signals, retracts, erases PII, EXECUTED`() {
        val recommender = "cw_rec@corp.example.com"
        val completed = driveToCompleted("cw_owner@example.com", recommender)
        val requestId = completed.requestId
        val contactId = completed.contactId

        // Preconditions: GRANTED consents for the contact + VERIFIED version signals exist.
        assertThat(
            dsl.fetchCount(
                CONSENT_RECORD,
                CONSENT_RECORD.RECOMMENDER_CONTACT_ID.eq(contactId).and(CONSENT_RECORD.STATUS.eq("GRANTED")),
            ),
        ).isGreaterThan(0)
        val versionIds = dsl.select(DOCUMENT_VERSION.ID).from(DOCUMENT_VERSION)
            .where(DOCUMENT_VERSION.DOCUMENT_ID.`in`(dsl.select(DOCUMENT.ID).from(DOCUMENT).where(DOCUMENT.REQUEST_ID.eq(requestId))))
            .fetch(DOCUMENT_VERSION.ID).filterNotNull()
        assertThat(versionIds).isNotEmpty()
        assertThat(
            dsl.fetchCount(
                VERIFICATION_SIGNAL,
                VERIFICATION_SIGNAL.ENTITY_TYPE.eq("DOCUMENT_VERSION")
                    .and(VERIFICATION_SIGNAL.ENTITY_ID.`in`(versionIds))
                    .and(VERIFICATION_SIGNAL.STATUS.eq("VERIFIED")),
            ),
        ).isGreaterThan(0)

        assertThat(intake(recommender)).isEqualTo(HttpStatus.ACCEPTED)
        val (dsrId, code) = codeMail(recommender)
        val response = verify(dsrId, code)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["executed"]).isEqualTo(true)
        assertThat(response.body!!["status"]).isEqualTo("EXECUTED")

        // Consents withdrawn (none GRANTED left for the contact; at least one WITHDRAWN).
        assertThat(
            dsl.fetchCount(
                CONSENT_RECORD,
                CONSENT_RECORD.RECOMMENDER_CONTACT_ID.eq(contactId).and(CONSENT_RECORD.STATUS.eq("GRANTED")),
            ),
        ).isZero()
        assertThat(
            dsl.fetchCount(
                CONSENT_RECORD,
                CONSENT_RECORD.RECOMMENDER_CONTACT_ID.eq(contactId).and(CONSENT_RECORD.STATUS.eq("WITHDRAWN")),
            ),
        ).isGreaterThan(0)

        // Version signals revoked (no VERIFIED remaining), retracted_at stamped.
        assertThat(
            dsl.fetchCount(
                VERIFICATION_SIGNAL,
                VERIFICATION_SIGNAL.ENTITY_TYPE.eq("DOCUMENT_VERSION")
                    .and(VERIFICATION_SIGNAL.ENTITY_ID.`in`(versionIds))
                    .and(VERIFICATION_SIGNAL.STATUS.eq("VERIFIED")),
            ),
        ).isZero()
        assertThat(
            dsl.fetchCount(
                VERIFICATION_SIGNAL,
                VERIFICATION_SIGNAL.ENTITY_TYPE.eq("DOCUMENT_VERSION")
                    .and(VERIFICATION_SIGNAL.ENTITY_ID.`in`(versionIds))
                    .and(VERIFICATION_SIGNAL.STATUS.eq("REVOKED")),
            ),
        ).isGreaterThan(0)
        assertThat(
            dsl.fetchCount(
                DOCUMENT_VERSION,
                DOCUMENT_VERSION.ID.`in`(versionIds).and(DOCUMENT_VERSION.RETRACTED_AT.isNotNull),
            ),
        ).isEqualTo(versionIds.size)

        // Recommender PII erased; DSR EXECUTED.
        val rr = REFERENCE_REQUEST
        val request = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).fetchOne()!!
        assertThat(request.recommenderPiiErasedAt).isNotNull()
        assertThat(request.recommenderEmail).isNull()
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("EXECUTED")

        // Full audit trail.
        listOf(
            "DATA_SUBJECT_REQUEST_RECEIVED",
            "CONSENT_WITHDRAWN",
            "VERIFICATION_SIGNAL_UPDATED",
            "RECOMMENDATION_RETRACTED",
            "RECOMMENDER_PII_ERASED",
            "DATA_SUBJECT_REQUEST_EXECUTED",
        ).forEach { action ->
            assertThat(dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq(action)))
                .describedAs("audit event %s", action).isGreaterThan(0)
        }
    }

    @Test
    fun `five wrong codes lock the request out`() {
        val recommender = "lockout_rec@corp.example.com"
        driveToCompleted("lockout_owner@example.com", recommender)
        assertThat(intake(recommender)).isEqualTo(HttpStatus.ACCEPTED)
        val (dsrId, correct) = codeMail(recommender)
        val wrong = if (correct == "000000") "111111" else "000000"

        repeat(5) {
            assertThat(verify(dsrId, wrong).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        // Attempts exhausted: even the correct code is now rejected.
        assertThat(verify(dsrId, correct).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(
            dsl.select(DATA_SUBJECT_REQUEST.STATUS).from(DATA_SUBJECT_REQUEST)
                .where(DATA_SUBJECT_REQUEST.ID.eq(dsrId)).fetchOne(DATA_SUBJECT_REQUEST.STATUS),
        ).isEqualTo("RECEIVED")
    }
}
