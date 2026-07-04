package com.verifolio.identity.api

import jakarta.validation.constraints.NotBlank

// Note: @Email is intentionally omitted; normalization (trim + lowercase) happens in MagicLinkService
// before any email-format check, so validation here only rejects empty/blank values.
data class MagicLinkRequest(@field:NotBlank val email: String)
data class SessionRequest(@field:NotBlank val token: String)
data class MessageResponse(val message: String)
data class CurrentUserResponse(val userId: String, val email: String, val region: String)
