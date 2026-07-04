package com.verifolio.profiles.api

import jakarta.validation.constraints.NotBlank

data class ProfileResponse(
    val profileId: String,
    val displayName: String,
    val legalName: String?,
    val preferredLocale: String,
    val profileVerificationStatus: String,
)

data class ProfileUpdateRequest(
    @field:NotBlank val displayName: String,
    val legalName: String?,
    @field:NotBlank val preferredLocale: String,
)
