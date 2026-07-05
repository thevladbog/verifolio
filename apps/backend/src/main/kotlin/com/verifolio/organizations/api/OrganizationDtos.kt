package com.verifolio.organizations.api

import com.verifolio.organizations.OrganizationView
import io.swagger.v3.oas.annotations.media.ArraySchema

data class OrganizationListResponse(
    @field:ArraySchema(maxItems = 50) val items: List<OrganizationView>,
    val nextCursor: String?,
)
