package com.verifolio.templates

import java.util.UUID

/**
 * Public API of the templates module for cross-module reads.
 * Other modules should depend only on types in the com.verifolio.templates root package.
 */
data class TemplateSnapshot(val id: UUID, val name: String, val questionSchemaJson: String)

interface TemplateLookup {
    fun exists(templateId: UUID): Boolean

    fun snapshot(templateId: UUID): TemplateSnapshot?
}
