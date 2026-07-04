package com.verifolio.profiles

import java.util.UUID

/**
 * Public API for the profiles module.
 * Other modules should depend only on types in the com.verifolio.profiles root package.
 */
interface ProfileService {
    /**
     * Returns the profile ID for the given user account.
     * If no profile exists yet (e.g. the AFTER_COMMIT listener failed during signup)
     * the profile is created on-the-fly (self-heal) and the new id is returned.
     */
    fun requireProfileId(userAccountId: UUID, email: String): UUID

    /** Display name of a profile, or null if the profile does not exist. */
    fun displayName(profileId: UUID): String?
}
