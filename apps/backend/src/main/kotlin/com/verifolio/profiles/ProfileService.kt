package com.verifolio.profiles

import java.util.UUID

/**
 * Public API for the profiles module.
 * Other modules should depend only on types in the com.verifolio.profiles root package.
 */
interface ProfileService {
    /** Returns the profile ID for the given user account, or throws ApiException(404) if not found. */
    fun requireProfileId(userAccountId: UUID): UUID
}
