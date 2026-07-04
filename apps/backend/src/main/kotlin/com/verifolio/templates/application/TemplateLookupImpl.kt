package com.verifolio.templates.application

import com.verifolio.jooq.tables.references.TEMPLATE
import com.verifolio.templates.TemplateLookup
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class TemplateLookupImpl(private val dsl: DSLContext) : TemplateLookup {
    override fun exists(templateId: UUID): Boolean =
        dsl.fetchExists(dsl.selectFrom(TEMPLATE).where(TEMPLATE.ID.eq(templateId)))
}
