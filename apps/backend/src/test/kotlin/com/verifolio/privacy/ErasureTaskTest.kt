package com.verifolio.privacy

import com.verifolio.jooq.tables.references.REFERENCE_REQUEST
import com.verifolio.privacy.application.RecommenderPiiErasureTask
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/** The decline-grace erasure sweep: past-grace declines are erased, in-grace ones are untouched. */
class ErasureTaskTest : PrivacyFlowSupport() {

    @Autowired internal lateinit var task: RecommenderPiiErasureTask

    private fun backdateDecline(requestId: UUID, ago: java.time.Duration) {
        val rr = REFERENCE_REQUEST
        val updated = dsl.update(rr)
            .set(rr.DECLINED_AT, OffsetDateTime.now().minus(ago))
            .where(rr.ID.eq(requestId))
            .execute()
        assertThat(updated).isEqualTo(1)
    }

    @Test
    fun `declined beyond the grace window is erased`() {
        val recommender = "erase_task_rec@corp.example.com"
        val requestId = driveToDeclined("erase_task_owner@example.com", recommender)
        // Sanity: PII present before the sweep.
        val rr = REFERENCE_REQUEST
        assertThat(dsl.select(rr.RECOMMENDER_EMAIL).from(rr).where(rr.ID.eq(requestId)).fetchOne(rr.RECOMMENDER_EMAIL))
            .isNotNull()

        backdateDecline(requestId, java.time.Duration.ofHours(25)) // grace is 24h
        task.run()

        val after = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).fetchOne()!!
        assertThat(after.recommenderPiiErasedAt).isNotNull()
        assertThat(after.recommenderEmail).isNull()
    }

    @Test
    fun `declined within the grace window is untouched`() {
        val recommender = "grace_rec@corp.example.com"
        val requestId = driveToDeclined("grace_owner@example.com", recommender)

        backdateDecline(requestId, java.time.Duration.ofHours(1)) // still inside the 24h window
        task.run()

        val rr = REFERENCE_REQUEST
        val after = dsl.selectFrom(rr).where(rr.ID.eq(requestId)).fetchOne()!!
        assertThat(after.recommenderPiiErasedAt).isNull()
        assertThat(after.recommenderEmail).isNotNull()
    }
}
