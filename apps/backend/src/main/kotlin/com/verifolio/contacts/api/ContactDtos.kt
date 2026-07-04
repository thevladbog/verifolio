package com.verifolio.contacts.api

import com.verifolio.contacts.domain.RelationshipType
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ContactRequest(
    @field:NotBlank val name: String,
    @field:NotBlank @field:Email val email: String,
    val companyName: String? = null,
    val companyDomain: String? = null,
    val title: String? = null,
    val relationshipType: RelationshipType,
)

data class ContactResponse(
    val id: String,
    val name: String,
    @field:Schema(format = "email") val email: String,
    val companyName: String?,
    val companyDomain: String?,
    val title: String?,
    val relationshipType: String,
    val createdAt: String,
    val updatedAt: String?,
)

data class ContactListResponse(
    @field:ArraySchema(maxItems = 50) val items: List<ContactResponse>,
    val nextCursor: String?,
)
