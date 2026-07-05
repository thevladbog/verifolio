package com.verifolio.requests

import com.verifolio.jooq.tables.references.PERSON_PROFILE
import com.verifolio.jooq.tables.references.RECOMMENDER_CONTACT
import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

class RequestExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var requestExport: RequestExport
    @Autowired lateinit var dsl: DSLContext

    private fun templateId(): UUID =
        dsl.select(TEMPLATE.ID).from(TEMPLATE).limit(1).fetchOne(TEMPLATE.ID)!!

    private fun seedProfile(): UUID {
        val userId = UUID.randomUUID()
        val ua = USER_ACCOUNT
        dsl.insertInto(ua)
            .set(ua.ID, userId).set(ua.EMAIL, "req-owner-${UUID.randomUUID()}@example.com")
            .set(ua.REGION, "EU").set(ua.STATUS, "ACTIVE").execute()
        val profileId = UUID.randomUUID()
        val pp = PERSON_PROFILE
        dsl.insertInto(pp)
            .set(pp.ID, profileId).set(pp.USER_ACCOUNT_ID, userId)
            .set(pp.DISPLAY_NAME, "Requester").set(pp.PREFERRED_LOCALE, "en").execute()
        return profileId
    }

    private fun seedContact(ownerProfileId: UUID, email: String): UUID {
        val id = UUID.randomUUID()
        val rc = RECOMMENDER_CONTACT
        dsl.insertInto(rc)
            .set(rc.ID, id).set(rc.OWNER_PROFILE_ID, ownerProfileId)
            .set(rc.NAME, "Rec").set(rc.EMAIL, email).set(rc.RELATIONSHIP_TYPE, "MANAGER").execute()
        return id
    }

    private fun seedRequest(
        requesterProfileId: UUID,
        contactId: UUID,
        recommenderName: String,
        recommenderEmail: String,
        purpose: String?,
    ): UUID {
        val id = UUID.randomUUID()
        val rr = REFERENCE_REQUEST
        dsl.insertInto(rr)
            .set(rr.ID, id)
            .set(rr.REQUESTER_PROFILE_ID, requesterProfileId)
            .set(rr.RECOMMENDER_CONTACT_ID, contactId)
            .set(rr.RECOMMENDER_NAME, recommenderName)
            .set(rr.RECOMMENDER_EMAIL, recommenderEmail)
            .set(rr.TEMPLATE_ID, templateId())
            .set(rr.PURPOSE, purpose)
            .set(rr.EXPIRES_AT, OffsetDateTime.now().plusDays(30))
            .execute()
        return id
    }

    @Test
    fun `forRequester returns only the subject's requests, metadata only`() {
        val owner = seedProfile()
        val other = seedProfile()
        val ownerContact = seedContact(owner, "rec1@example.com")
        val otherContact = seedContact(other, "rec2@example.com")
        val mine = seedRequest(owner, ownerContact, "Rec One", "rec1@example.com", "Visa")
        seedRequest(other, otherContact, "Rec Two", "rec2@example.com", "Job")

        val data = requestExport.forRequester(owner)

        assertThat(data).hasSize(1)
        assertThat(data[0].id).isEqualTo(mine)
        assertThat(data[0].recommenderName).isEqualTo("Rec One")
        assertThat(data[0].recommenderEmail).isEqualTo("rec1@example.com")
        assertThat(data[0].purpose).isEqualTo("Visa")
        assertThat(data[0].status).isEqualTo("CREATED")
        assertThat(data[0].createdAt).isNotNull()
        assertThat(data[0].updatedAt).isNotNull()
    }

    @Test
    fun `forRecommenderEmail matches on the snapshot email across owners`() {
        val owner = seedProfile()
        val contact = seedContact(owner, "shared-rec@example.com")
        seedRequest(owner, contact, "Shared Rec", "shared-rec@example.com", "Purpose A")

        val data = requestExport.forRecommenderEmail("shared-rec@example.com")

        assertThat(data).hasSize(1)
        assertThat(data[0].recommenderEmail).isEqualTo("shared-rec@example.com")
        assertThat(requestExport.forRecommenderEmail("nobody@example.com")).isEmpty()
    }

    @Test
    fun `forRecommenderEmail matches case-insensitively`() {
        val owner = seedProfile()
        val contact = seedContact(owner, "Mixed.Case@Example.com")
        seedRequest(owner, contact, "Mixed Rec", "Mixed.Case@Example.com", "Purpose B")

        // A lowercased subject email (as carried through the privacy flow) still matches the
        // mixed-case snapshot stored on the request.
        val data = requestExport.forRecommenderEmail("mixed.case@example.com")

        assertThat(data).hasSize(1)
        assertThat(data[0].recommenderEmail).isEqualTo("Mixed.Case@Example.com")
    }
}
