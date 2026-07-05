package com.verifolio.admin.api

import jakarta.validation.constraints.NotBlank

// Email normalization (trim + lowercase) happens downstream; @Email is intentionally omitted so the
// response stays identical for malformed and unknown addresses (anti-enumeration).
data class AdminMagicLinkRequest(@field:NotBlank val email: String)

data class AdminConsumeRequest(@field:NotBlank val token: String)

data class AdminMfaCodeRequest(@field:NotBlank val code: String)

data class AdminMessageResponse(val message: String)

/** magic-links/consume result: the MFA branch the client must follow. */
data class AdminConsumeResponse(val state: String)

/** GET mfa/enrollment: the one-time enrollment material for an authenticator app. */
data class AdminEnrollmentResponse(val secretBase32: String, val otpauthUri: String)

/** mfa/enroll + mfa/verify success acknowledgement (the admin session is set via cookie). */
data class AdminOkResponse(val ok: Boolean = true)

/** GET /me: the authenticated admin identity. */
data class AdminMeResponse(val id: String, val email: String, val role: String, val region: String)
