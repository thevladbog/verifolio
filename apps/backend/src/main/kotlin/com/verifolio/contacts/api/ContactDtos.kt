package com.verifolio.contacts.api

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ContactRequest(
    @field:NotBlank val name: String,
    @field:NotBlank @field:Email val email: String,
    val companyName: String? = null,
    val companyDomain: String? = null,
    val title: String? = null,
    @field:NotBlank val relationshipType: String,
)

data class ContactResponse(
    val id: String,
    val name: String,
    val email: String,
    val companyName: String?,
    val companyDomain: String?,
    val title: String?,
    val relationshipType: String,
    val createdAt: String,
    val updatedAt: String?,
)

data class ContactListResponse(
    val items: List<ContactResponse>,
    val nextCursor: String?,
)
