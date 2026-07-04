package com.verifolio.documents.application

import com.verifolio.documents.domain.HtmlRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PdfRendererTest {

    @Test
    fun `renders valid pdf bytes from generated html`() {
        val html = HtmlRenderer.render(
            letterText = "A short recommendation letter.\nWith two paragraphs.",
            recommenderName = "Jane Doe",
            purpose = "Employment reference",
            lockedAtIso = "2026-07-05T12:00:00Z",
        )
        val bytes = PdfRenderer().render(html)
        assertThat(bytes.size).isGreaterThan(500)
        assertThat(String(bytes.copyOfRange(0, 5), Charsets.US_ASCII)).isEqualTo("%PDF-")
    }
}
