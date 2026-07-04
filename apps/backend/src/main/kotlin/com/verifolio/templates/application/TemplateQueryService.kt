package com.verifolio.templates.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.platform.ApiException
import com.verifolio.templates.api.TemplateListItem
import com.verifolio.templates.api.TemplateListResponse
import com.verifolio.templates.api.TemplateResponse
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class TemplateQueryService(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun list(locale: String = "en"): TemplateListResponse {
        val t = TEMPLATE
        val items = dsl.selectFrom(t)
            .where(t.LOCALE.eq(locale))
            .orderBy(t.TYPE.asc())
            .fetch()
            .map { record ->
                TemplateListItem(
                    id = record.id!!.toString(),
                    type = record.type!!,
                    locale = record.locale!!,
                    name = record.name!!,
                    description = record.description!!,
                )
            }
        return TemplateListResponse(items = items)
    }

    @Transactional(readOnly = true)
    fun getById(id: UUID): TemplateResponse {
        val t = TEMPLATE
        val record = dsl.selectFrom(t)
            .where(t.ID.eq(id))
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Template not found")

        return TemplateResponse(
            id = record.id!!.toString(),
            type = record.type!!,
            locale = record.locale!!,
            name = record.name!!,
            description = record.description!!,
            questionSchema = parseJsonb(record.questionSchemaJson),
            outputSchema = parseJsonb(record.outputSchemaJson),
            requiredFields = parseJsonb(record.requiredFieldsJson),
            verificationRecommendations = parseJsonb(record.verificationRecommendationsJson),
        )
    }

    private fun parseJsonb(jsonb: JSONB?) =
        objectMapper.readTree(jsonb?.data() ?: "null")
}
