package com.verifolio.platform

/**
 * Single source of truth for supported UI locales.
 * Referenced by ProfileApplicationService (locale validation) and
 * TemplateController (locale query-param guard) to prevent drift.
 */
object SupportedLocales {
    val ALL: Set<String> = setOf("en", "ru")
}
