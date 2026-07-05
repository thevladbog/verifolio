package com.verifolio.identity

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Subject-scoped account metadata for a DSR EXPORT package (GDPR Art. 15/20).
 * Metadata only — no credentials, tokens, or sessions.
 */
data class AccountExportData(
    val email: String,
    val region: String,
    val status: String,
    val createdAt: OffsetDateTime,
)

/**
 * One-way export read port of the identity module. Consumed by privacy's EXPORT executor.
 * Returns only the subject's own account row, or null if the account does not exist.
 */
interface AccountExport {
    fun forUser(userId: UUID): AccountExportData?
}
