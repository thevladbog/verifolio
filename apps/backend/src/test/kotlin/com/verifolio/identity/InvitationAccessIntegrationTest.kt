package com.verifolio.identity

import com.verifolio.jooq.tables.references.EMAIL_CONFIRMATION_CODE
import com.verifolio.jooq.tables.references.INVITATION_TOKEN
import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.platform.ApiException
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

@Import(RecordingMailConfig::class)
class InvitationAccessIntegrationTest : IntegrationTest() {

    @Autowired lateinit var invitationAccess: InvitationAccess
    @Autowired lateinit var invitationTokens: InvitationTokenService
    @Autowired lateinit var recommenderSessions: RecommenderSessions
    @Autowired lateinit var dsl: DSLContext

    /** Inserts the FK chain directly and mints a real invitation token; returns the raw token. */
    private fun mintInvitation(email: String): Pair<String, UUID> {
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, "req_$email")
            .set(USER_ACCOUNT.REGION, "local")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        val profileId = dsl.insertInto(PERSON_PROFILE)
            .set(PERSON_PROFILE.USER_ACCOUNT_ID, userId)
            .set(PERSON_PROFILE.DISPLAY_NAME, "Requester")
            .returning(PERSON_PROFILE.ID).fetchOne()!!.id!!
        val contactId = dsl.insertInto(RECOMMENDER_CONTACT)
            .set(RECOMMENDER_CONTACT.OWNER_PROFILE_ID, profileId)
            .set(RECOMMENDER_CONTACT.NAME, "Rec")
            .set(RECOMMENDER_CONTACT.EMAIL, email)
            .set(RECOMMENDER_CONTACT.RELATIONSHIP_TYPE, "MANAGER")
            .returning(RECOMMENDER_CONTACT.ID).fetchOne()!!.id!!
        val templateId = dsl.select(TEMPLATE.ID).from(TEMPLATE).limit(1).fetchOne()!!.value1()!!
        val requestId = dsl.insertInto(REFERENCE_REQUEST)
            .set(REFERENCE_REQUEST.REQUESTER_PROFILE_ID, profileId)
            .set(REFERENCE_REQUEST.RECOMMENDER_CONTACT_ID, contactId)
            .set(REFERENCE_REQUEST.RECOMMENDER_NAME, "Rec")
            .set(REFERENCE_REQUEST.RECOMMENDER_EMAIL, email)
            .set(REFERENCE_REQUEST.TEMPLATE_ID, templateId)
            .set(REFERENCE_REQUEST.STATUS, "SENT")
            .set(REFERENCE_REQUEST.EXPIRES_AT, OffsetDateTime.now().plusDays(21))
            .returning(REFERENCE_REQUEST.ID).fetchOne()!!.id!!
        val raw = invitationTokens.mint(requestId, email, Duration.ofDays(21))
        return raw to requestId
    }

    @Test
    fun `wrong code five times exhausts the code even if the right code follows`() {
        val (raw, _) = mintInvitation("attempts@example.com")
        val code = invitationAccess.issueEmailConfirmation(raw)

        repeat(5) {
            assertThatThrownBy { invitationAccess.confirmEmail(raw, "000001", null, null) }
                .isInstanceOf(ApiException::class.java)
                .hasMessageContaining("invalid")
        }
        // Correct code no longer works — attempts exhausted.
        assertThatThrownBy { invitationAccess.confirmEmail(raw, code, null, null) }
            .isInstanceOf(ApiException::class.java)

        // A fresh code still works.
        val fresh = invitationAccess.issueEmailConfirmation(raw)
        val grant = invitationAccess.confirmEmail(raw, fresh, null, null)
        assertThat(grant.recommenderEmail).isEqualTo("attempts@example.com")
        assertThat(recommenderSessions.resolve(grant.rawSessionToken)).isNotNull()
    }

    @Test
    fun `expired code is rejected and token stays unconsumed`() {
        val (raw, requestId) = mintInvitation("expired_code@example.com")
        val code = invitationAccess.issueEmailConfirmation(raw)

        val ecc = EMAIL_CONFIRMATION_CODE
        dsl.update(ecc)
            .set(ecc.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(
                ecc.INVITATION_TOKEN_ID.`in`(
                    dsl.select(INVITATION_TOKEN.ID).from(INVITATION_TOKEN)
                        .where(INVITATION_TOKEN.REQUEST_ID.eq(requestId)),
                ),
            )
            .execute()

        assertThatThrownBy { invitationAccess.confirmEmail(raw, code, null, null) }
            .isInstanceOf(ApiException::class.java)

        assertThat(invitationAccess.peek(raw)).isNotNull() // still valid, unconsumed
    }

    @Test
    fun `confirm consumes the invitation token single-use`() {
        val (raw, requestId) = mintInvitation("single_use@example.com")
        val code = invitationAccess.issueEmailConfirmation(raw)
        invitationAccess.confirmEmail(raw, code, null, null)

        assertThat(invitationAccess.peek(raw)).isNull()          // consumed
        assertThat(invitationAccess.identify(raw)).isNotNull()   // still identifiable for decline

        val it = INVITATION_TOKEN
        val row = dsl.selectFrom(it).where(it.REQUEST_ID.eq(requestId)).fetchOne()!!
        assertThat(row.consumedAt).isNotNull()
    }

}
