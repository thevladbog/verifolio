package com.verifolio.files

import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.workflows.RecurringTask
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime
import java.util.UUID

@Import(RecordingMailConfig::class)
class PendingUploadCleanupTest : IntegrationTest() {

    @Autowired lateinit var fileUploads: FileUploads
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var context: ApplicationContext

    private val task: RecurringTask by lazy {
        context.getBeansOfType(RecurringTask::class.java).values
            .first { it.name == "pending-upload-cleanup" }
    }

    @Test
    fun `stale pending uploads are deleted, fresh ones survive`() {
        val stale = fileUploads.requestUpload("SCAN", "old.pdf", "application/pdf", 100, null).fileId
        val fresh = fileUploads.requestUpload("SCAN", "new.pdf", "application/pdf", 100, null).fileId

        val fo = FILE_OBJECT
        dsl.update(fo)
            .set(fo.CREATED_AT, OffsetDateTime.now().minusDays(2))
            .where(fo.ID.eq(stale))
            .execute()

        task.run()

        assertThat(statusOf(stale)).isEqualTo("DELETED")
        assertThat(statusOf(fresh)).isEqualTo("PENDING")

        // Idempotent: a second run changes nothing further.
        task.run()
        assertThat(statusOf(stale)).isEqualTo("DELETED")
    }

    private fun statusOf(fileId: UUID): String {
        val fo = FILE_OBJECT
        return dsl.select(fo.STATUS).from(fo).where(fo.ID.eq(fileId)).fetchOne(fo.STATUS)!!
    }
}
