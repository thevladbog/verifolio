package com.verifolio.verification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BadgeCatalogTest {

    @Test
    fun `all shipped signal types have plain-language titles`() {
        val shipped = listOf(
            "EMAIL_CONFIRMED", "CORPORATE_DOMAIN_CONFIRMED", "RECIPIENT_CONFIRMED",
            "RECOMMENDER_RELATIONSHIP_CONFIRMED", "VERSION_LOCKED", "DOCUMENT_HASH_LOCKED",
            "PUBLIC_VERIFICATION_ENABLED",
        )
        shipped.forEach { type ->
            val badge = BadgeCatalog.describe(type)
            assertThat(badge.title).describedAs(type).isNotBlank().doesNotContain("_")
        }
    }

    @Test
    fun `limitations follow the signal catalog`() {
        assertThat(BadgeCatalog.describe("EMAIL_CONFIRMED").limitation).contains("mailbox")
        assertThat(BadgeCatalog.describe("CORPORATE_DOMAIN_CONFIRMED").limitation).contains("point-in-time")
        assertThat(BadgeCatalog.describe("VERSION_LOCKED").limitation).isNull()
    }

    @Test
    fun `unknown types fall back to the raw name`() {
        assertThat(BadgeCatalog.describe("FUTURE_THING").title).isEqualTo("FUTURE_THING")
    }
}
