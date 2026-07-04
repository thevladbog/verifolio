package com.verifolio.profiles.api

import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
import com.verifolio.profiles.application.ProfileApplicationService
import com.verifolio.profiles.application.ProfileRow
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/profile")
internal class ProfileController(
    private val profileService: ProfileApplicationService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Profile not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun getProfile(@AuthenticationPrincipal user: AuthenticatedUser): ProfileResponse =
        profileService.get(user.userId).toResponse()

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Profile not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PutMapping
    fun updateProfile(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody body: ProfileUpdateRequest,
    ): ResponseEntity<ProfileResponse> {
        val updated = profileService.update(user.userId, body.displayName, body.legalName, body.preferredLocale)
        return ResponseEntity.ok(updated.toResponse())
    }

    private fun ProfileRow.toResponse() = ProfileResponse(
        profileId = profileId.toString(),
        displayName = displayName,
        legalName = legalName,
        preferredLocale = preferredLocale,
        profileVerificationStatus = profileVerificationStatus,
    )
}
