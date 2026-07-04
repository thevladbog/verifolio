package com.verifolio.templates.api

import com.fasterxml.jackson.databind.JsonNode

data class TemplateListItem(
    val id: String,
    val type: String,
    val locale: String,
    val name: String,
    val description: String,
)

data class TemplateListResponse(
    val items: List<TemplateListItem>,
)

data class TemplateResponse(
    val id: String,
    val type: String,
    val locale: String,
    val name: String,
    val description: String,
    val questionSchema: JsonNode,
    val outputSchema: JsonNode,
    val requiredFields: JsonNode,
    val verificationRecommendations: JsonNode,
)
