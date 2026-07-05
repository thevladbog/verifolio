package com.verifolio.verification


import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class TrustSummaryTest {

    private fun verified(type: String) = SignalView(type, "VERIFIED", OffsetDateTime.now())

    @Test
    fun `counts verified signals per category`() {
        val summary = TrustSummary.derive(
            listOf(
                verified("EMAIL_CONFIRMED"),
                verified("CORPORATE_DOMAIN_CONFIRMED"),
                verified("RECIPIENT_CONFIRMED"),
                verified("RECOMMENDER_RELATIONSHIP_CONFIRMED"),
                verified("VERSION_LOCKED"),
                verified("DOCUMENT_HASH_LOCKED"),
                verified("PUBLIC_VERIFICATION_ENABLED"),
            ),
        )
        assertThat(summary).containsEntry("identity", 2)
        assertThat(summary).containsEntry("relationship", 2)
        assertThat(summary).containsEntry("documentIntegrity", 2)
        assertThat(summary).containsEntry("signature", 0)
        assertThat(summary).containsEntry("publication", 1)
    }

    @Test
    fun `non-verified statuses never count`() {
        val summary = TrustSummary.derive(
            listOf(
                SignalView("EMAIL_CONFIRMED", "REVOKED", null),
                SignalView("VERSION_LOCKED", "PENDING", null),
            ),
        )
        assertThat(summary.values.sum()).isZero()
    }

    @Test
    fun `unknown signal types are ignored`() {
        val summary = TrustSummary.derive(listOf(verified("SOME_FUTURE_SIGNAL")))
        assertThat(summary.values.sum()).isZero()
    }
}
