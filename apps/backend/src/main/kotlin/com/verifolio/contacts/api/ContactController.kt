package com.verifolio.contacts.api

import com.verifolio.contacts.application.ContactService
import com.verifolio.identity.AuthenticatedUser
import com.verifolio.platform.web.ApiError
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/contacts")
internal class ContactController(
    private val contactService: ContactService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Invalid cursor", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Profile not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun listContacts(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @RequestParam cursor: String? = null,
    ): ContactListResponse = contactService.list(user, cursor)

    @ApiResponses(
        ApiResponse(responseCode = "201"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Profile not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PostMapping
    fun createContact(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @Valid @RequestBody body: ContactRequest,
    ): ResponseEntity<ContactResponse> {
        val created = contactService.create(user, body)
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Contact not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun getContact(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ContactResponse = contactService.get(user, id)

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Validation failed", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Contact not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @PutMapping("/{id}")
    fun updateContact(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
        @Valid @RequestBody body: ContactRequest,
    ): ContactResponse = contactService.update(user, id, body)

    @ApiResponses(
        ApiResponse(responseCode = "204"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Contact not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @DeleteMapping("/{id}")
    fun deleteContact(
        @AuthenticationPrincipal user: AuthenticatedUser,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        contactService.delete(user, id)
        return ResponseEntity.noContent().build()
    }
}
