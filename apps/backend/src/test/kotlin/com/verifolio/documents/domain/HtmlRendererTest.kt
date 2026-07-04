package com.verifolio.documents.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlRendererTest {

    @Test
    fun `letter text is escaped - no user HTML passes through`() {
        val html = HtmlRenderer.render(
            letterText = "<script>alert(1)</script>\nSecond & <b>line</b>",
            recommenderName = "Jane <img src=x>",
            purpose = "Visa & relocation",
            lockedAtIso = "2026-07-05T12:00:00Z",
        )
        assertThat(html).doesNotContain("<script>")
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;")
        assertThat(html).contains("Jane &lt;img src=x&gt;")
        assertThat(html).contains("Visa &amp; relocation")
        assertThat(html).contains("Second &amp; &lt;b&gt;line&lt;/b&gt;")
    }

    @Test
    fun `newlines become separate paragraphs`() {
        val html = HtmlRenderer.render("Line one\nLine two", "Rec", null, "2026-07-05T12:00:00Z")
        assertThat(html).contains("<p>Line one</p>")
        assertThat(html).contains("<p>Line two</p>")
    }

    @Test
    fun `output is a complete xhtml document`() {
        val html = HtmlRenderer.render("Text", "Rec", null, "2026-07-05T12:00:00Z")
        assertThat(html).startsWith("<!DOCTYPE html>")
        assertThat(html).contains("</html>")
    }
}
