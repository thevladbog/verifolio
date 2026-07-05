package com.verifolio.templates.api

import com.verifolio.platform.ApiException
import com.verifolio.platform.web.ApiError
import com.verifolio.templates.application.ConsentTextService
import com.verifolio.templates.application.ConsentType
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

data class ConsentTextView(
    val consentType: String,
    val textId: String,
    val version: Int,
    val locale: String,
    val title: String,
    val body: String,
)

/**
 * Public (permitAll) read-only consent policy texts — the copy behind the versioned
 * identifiers recorded in consent_record.policy_text_version. Reads are not audited
 * (static policy documents, no authorization boundary).
 */
@RestController
@RequestMapping("/api/v1/consent-texts")
internal class ConsentTextController(
    private val consentTextService: ConsentTextService,
) {

    @ApiResponses(
        ApiResponse(responseCode = "200"),
        ApiResponse(responseCode = "404", description = "Unknown consent type", content = [Content(schema = Schema(implementation = ApiError::class))]),
    )
    @GetMapping("/{consentType}")
    fun getConsentText(
        @PathVariable consentType: String,
        @RequestParam(defaultValue = "en") locale: String,
    ): ConsentTextView {
        val type = ConsentType.entries.firstOrNull { it.name == consentType }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Unknown consent type '$consentType'")
        return consentTextService.resolve(type, locale)
    }
}
