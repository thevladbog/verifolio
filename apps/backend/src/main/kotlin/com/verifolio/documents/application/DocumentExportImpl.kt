package com.verifolio.documents.application

import com.verifolio.documents.DocumentExport
import com.verifolio.documents.DocumentExportData
import com.verifolio.documents.VersionExportData
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class DocumentExportImpl(private val dsl: DSLContext) : DocumentExport {

    @Transactional(readOnly = true)
    override fun forOwner(ownerProfileId: UUID): List<DocumentExportData> {
        val d = DOCUMENT
        val dv = DOCUMENT_VERSION

        // Owner is resolved directly via document.owner_profile_id (documents carry the owner
        // FK; the reference_request link is optional). Metadata columns only — no content.
        val documents = dsl.select(d.ID, d.TYPE)
            .from(d)
            .where(d.OWNER_PROFILE_ID.eq(ownerProfileId))
            .orderBy(d.CREATED_AT.asc(), d.ID.asc())
            .fetch()

        return documents.map { docRow ->
            val documentId = docRow[d.ID]!!
            val versions = dsl.select(
                dv.VERSION_NUMBER, dv.SHA256_HASH, dv.STATUS,
                dv.LOCKED_AT, dv.RETRACTED_AT, dv.TOMBSTONED_AT,
            )
                .from(dv)
                .where(dv.DOCUMENT_ID.eq(documentId))
                .orderBy(dv.VERSION_NUMBER.asc())
                .fetch()
                .map {
                    VersionExportData(
                        versionNumber = it[dv.VERSION_NUMBER]!!,
                        sha256 = it[dv.SHA256_HASH]!!,
                        status = it[dv.STATUS]!!,
                        lockedAt = it[dv.LOCKED_AT],
                        retractedAt = it[dv.RETRACTED_AT],
                        tombstonedAt = it[dv.TOMBSTONED_AT],
                    )
                }
            DocumentExportData(
                documentId = documentId,
                type = docRow[d.TYPE],
                versions = versions,
            )
        }
    }
}
