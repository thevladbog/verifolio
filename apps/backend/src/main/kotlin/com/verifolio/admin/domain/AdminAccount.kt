package com.verifolio.admin.domain

import java.time.OffsetDateTime
import java.util.UUID

/**
 * An admin account (spec §Schema `admin_account`). `totpSecretEnc` holds the AES-256-GCM
 * ciphertext of the TOTP seed (never plaintext at rest); null until MFA enrollment completes.
 */
data class AdminAccount(
    val id: UUID,
    val userAccountId: UUID,
    val email: String,
    val region: String,
    val role: AdminRole,
    val status: String,
    val totpSecretEnc: String?,
    val mfaEnrolledAt: OffsetDateTime?,
) {
    val isActive: Boolean get() = status == "ACTIVE"
    val isEnrolled: Boolean get() = mfaEnrolledAt != null
}
