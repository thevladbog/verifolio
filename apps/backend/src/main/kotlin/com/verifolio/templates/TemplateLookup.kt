package com.verifolio.templates

import java.util.UUID

/**
 * Public API of the templates module for cross-module reads.
 * Other modules should depend only on types in the com.verifolio.templates root package.
 */
interface TemplateLookup {
    fun exists(templateId: UUID): Boolean
}
