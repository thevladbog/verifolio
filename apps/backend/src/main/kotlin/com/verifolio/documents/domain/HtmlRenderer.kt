package com.verifolio.documents.domain

/**
 * Sanitized rendering pipeline (docs/DATA_MODEL.md): all inputs are plain text and are
 * HTML-escaped — user-provided markup never passes through.
 */
object HtmlRenderer {

    fun render(letterText: String, recommenderName: String, purpose: String?, lockedAtIso: String): String {
        val paragraphs = letterText.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "<p>${escape(it)}</p>" }

        val purposeBlock = purpose?.let { "<p class=\"meta\">Context: ${escape(it)}</p>" } ?: ""

        return """
            |<!DOCTYPE html>
            |<html xmlns="http://www.w3.org/1999/xhtml">
            |<head>
            |<meta charset="utf-8"/>
            |<title>Reference letter</title>
            |<style>
            |body { font-family: sans-serif; margin: 48px; color: #1a1a1a; }
            |h1 { font-size: 18px; }
            |.meta { color: #555; font-size: 12px; }
            |p { line-height: 1.5; }
            |</style>
            |</head>
            |<body>
            |<h1>Reference letter</h1>
            |$purposeBlock
            |$paragraphs
            |<p class="meta">Provided by ${escape(recommenderName)} · locked at ${escape(lockedAtIso)} · Verifolio</p>
            |</body>
            |</html>
        """.trimMargin()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
