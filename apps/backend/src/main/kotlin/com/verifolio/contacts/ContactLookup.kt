package com.verifolio.contacts

import java.util.UUID

data class ContactSnapshot(val id: UUID, val name: String, val email: String)

/**
 * Public API of the contacts module for cross-module reads.
 * Other modules should depend only on types in the com.verifolio.contacts root package.
 */
interface ContactLookup {
    /** Returns the contact only when it belongs to [ownerProfileId]. */
    fun findOwned(contactId: UUID, ownerProfileId: UUID): ContactSnapshot?
}
