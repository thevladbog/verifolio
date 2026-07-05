package com.verifolio.verification



/**
 * Trust summary derivation (docs/VERIFICATION_SIGNALS.md): counts of VERIFIED signals per
 * category. Never reduced to a single number or percentage.
 */
object TrustSummary {

    private val categories: Map<String, String> = mapOf(
        "EMAIL_CONFIRMED" to "identity",
        "CORPORATE_DOMAIN_CONFIRMED" to "identity",
        "PHONE_CONFIRMED" to "identity",
        "IDENTITY_VERIFIED" to "identity",
        "NAME_MATCH" to "identity",
        "RECIPIENT_CONFIRMED" to "relationship",
        "RECOMMENDER_RELATIONSHIP_CONFIRMED" to "relationship",
        "VERSION_LOCKED" to "documentIntegrity",
        "DOCUMENT_HASH_LOCKED" to "documentIntegrity",
        "SCAN_ATTACHED" to "signature",
        "SIGNATURE_ATTACHED" to "signature",
        "SIGNATURE_VERIFIED" to "signature",
        "PUBLIC_VERIFICATION_ENABLED" to "publication",
    )

    private val allCategories = listOf("identity", "relationship", "documentIntegrity", "signature", "publication")

    fun derive(signals: List<SignalView>): Map<String, Int> {
        val counts = allCategories.associateWith { 0 }.toMutableMap()
        signals.asSequence()
            .filter { it.status == "VERIFIED" }
            .mapNotNull { categories[it.signalType] }
            .forEach { counts[it] = counts.getValue(it) + 1 }
        return counts
    }
}
