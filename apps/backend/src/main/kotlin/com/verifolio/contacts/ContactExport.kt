package com.verifolio.contacts

import java.time.OffsetDateTime
import java.util.UUID

/**
 * Subject-scoped contact metadata for a DSR EXPORT package. Metadata only.
 */
data class ContactExportData(
    val name: String,
    val email: String,
    val companyName: String?,
    val relationshipType: String,
    val createdAt: OffsetDateTime,
)

/**
 * One-way export read port of the contacts module. Returns only the contacts owned by
 * [ownerProfileId]; an owner with no contacts yields an empty list.
 */
interface ContactExport {
    fun forOwner(ownerProfileId: UUID): List<ContactExportData>
}
