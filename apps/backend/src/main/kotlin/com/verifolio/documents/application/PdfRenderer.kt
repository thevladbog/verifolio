package com.verifolio.documents.application

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/** HTML → PDF via openhtmltopdf (PDFBox backend). */
@Component
class PdfRenderer {
    fun render(html: String): ByteArray {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .withHtmlContent(html, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }
}
