package com.verifolio.files.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MimeSnifferTest {

    private val pdf = "%PDF-1.7 fake content".toByteArray(Charsets.US_ASCII)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9)
    private val der = byteArrayOf(0x30, 0x82.toByte(), 1, 2, 3)

    @Test
    fun `pdf magic bytes match only the pdf mime`() {
        assertThat(MimeSniffer.matches(pdf, "application/pdf")).isTrue()
        assertThat(MimeSniffer.matches(png, "application/pdf")).isFalse()
        assertThat(MimeSniffer.matches(ByteArray(0), "application/pdf")).isFalse()
    }

    @Test
    fun `jpeg and png magic bytes are recognised`() {
        assertThat(MimeSniffer.matches(jpeg, "image/jpeg")).isTrue()
        assertThat(MimeSniffer.matches(png, "image/png")).isTrue()
        assertThat(MimeSniffer.matches(jpeg, "image/png")).isFalse()
        assertThat(MimeSniffer.matches(png, "image/jpeg")).isFalse()
    }

    @Test
    fun `pkcs7 signature expects a DER sequence`() {
        assertThat(MimeSniffer.matches(der, "application/pkcs7-signature")).isTrue()
        assertThat(MimeSniffer.matches(pdf, "application/pkcs7-signature")).isFalse()
    }

    @Test
    fun `octet-stream is accepted as opaque`() {
        assertThat(MimeSniffer.matches(pdf, "application/octet-stream")).isTrue()
        assertThat(MimeSniffer.matches(der, "application/octet-stream")).isTrue()
    }

    @Test
    fun `unknown mime never matches`() {
        assertThat(MimeSniffer.matches(pdf, "text/html")).isFalse()
    }
}
