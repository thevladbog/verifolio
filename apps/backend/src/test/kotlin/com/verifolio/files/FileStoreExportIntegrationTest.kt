package com.verifolio.files

import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.security.MessageDigest

class FileStoreExportIntegrationTest : IntegrationTest() {

    @Autowired lateinit var fileStore: FileStore
    @Autowired lateinit var dsl: DSLContext

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `storeExport creates a READY DATA_EXPORT file with the correct sha256 and a presigned url`() {
        val bytes = """{"generatedAt":"2026-07-05T00:00:00Z","account":{"region":"EU"}}""".toByteArray()

        val stored = fileStore.storeExport(bytes)

        assertThat(stored.sha256).isEqualTo(sha256Hex(bytes))
        assertThat(stored.sizeBytes).isEqualTo(bytes.size.toLong())

        val fo = FILE_OBJECT
        val record = dsl.selectFrom(fo).where(fo.ID.eq(stored.fileId)).fetchOne()!!
        assertThat(record.purpose).isEqualTo("DATA_EXPORT")
        assertThat(record.status).isEqualTo("READY")
        assertThat(record.sha256Hash).isEqualTo(stored.sha256)
        assertThat(record.mimeType).isEqualTo("application/json")

        // The one sanctioned object-storage URL: the subject's own short-lived presigned GET.
        val link = fileStore.presignedDownloadUrl(stored.fileId)
        assertThat(link.url).contains("http")
        assertThat(link.expiresAt).isNotNull
    }
}
