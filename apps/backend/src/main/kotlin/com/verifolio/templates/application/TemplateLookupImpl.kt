package com.verifolio.templates.application

import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.templates.TemplateLookup
import com.verifolio.templates.TemplateSnapshot
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class TemplateLookupImpl(private val dsl: DSLContext) : TemplateLookup {
    override fun exists(templateId: UUID): Boolean =
        dsl.fetchExists(dsl.selectFrom(TEMPLATE).where(TEMPLATE.ID.eq(templateId)))

    override fun snapshot(templateId: UUID): TemplateSnapshot? =
        dsl.select(TEMPLATE.ID, TEMPLATE.TYPE, TEMPLATE.NAME, TEMPLATE.QUESTION_SCHEMA_JSON)
            .from(TEMPLATE)
            .where(TEMPLATE.ID.eq(templateId))
            .fetchOne()
            ?.let {
                TemplateSnapshot(
                    it[TEMPLATE.ID]!!,
                    it[TEMPLATE.TYPE]!!,
                    it[TEMPLATE.NAME]!!,
                    it[TEMPLATE.QUESTION_SCHEMA_JSON]!!.data(),
                )
            }
}
