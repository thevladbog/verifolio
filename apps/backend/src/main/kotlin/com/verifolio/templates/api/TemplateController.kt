package com.verifolio.templates.api

import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import com.verifolio.templates.application.TemplateQueryService
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

private val ALLOWED_LOCALES = setOf("en", "ru")

@RestController
@RequestMapping("/api/v1/templates")
internal class TemplateController(
    private val templateQueryService: TemplateQueryService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "400", description = "Unsupported locale", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping
    fun listTemplates(
        @RequestParam(defaultValue = "en") locale: String,
    ): TemplateListResponse {
        if (locale !in ALLOWED_LOCALES) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported locale '$locale'. Allowed: $ALLOWED_LOCALES")
        }
        return templateQueryService.list(locale)
    }

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "401", description = "Not authenticated", content = [Content(schema = Schema(implementation = ApiError::class))]),
        ApiResponse(responseCode = "404", description = "Template not found", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{id}")
    fun getTemplate(
        @PathVariable id: UUID,
    ): TemplateResponse = templateQueryService.getById(id)
}
