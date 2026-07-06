package com.verifolio.profiles

import java.util.UUID

/**
 * Subject-scoped profile metadata for a DSR EXPORT package. Metadata only.
 * (person_profile has no `headline` column; displayName is non-null.)
 */
data class ProfileExportData(
    val displayName: String,
    val legalName: String?,
    val preferredLocale: String,
)

/**
 * One-way export read port of the profiles module. Resolves the profile by user account
 * internally. Returns null if the user has no profile row.
 */
interface ProfileExport {
    fun forUser(userId: UUID): ProfileExportData?
}
