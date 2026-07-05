package com.verifolio.organizations

import java.util.UUID

/** A VERIFIED organization matched by an email domain. */
data class OrganizationMatch(
    val organizationId: UUID,
    val name: String,
    val matchedDomain: String,
)

/** Read view of an organization and its registered domains. */
data class OrganizationView(
    val id: UUID,
    val name: String,
    val domains: List<String>,
    val verificationStatus: String,
)

/**
 * Public API of the organizations module for cross-module reads.
 * Other modules should depend only on types in the com.verifolio.organizations root package.
 */
interface OrganizationLookup {
    /** The VERIFIED organization owning [emailDomain] (suffix-aware, longest-match wins), or null. */
    fun findVerifiedByDomain(emailDomain: String): OrganizationMatch?
}
