package com.verifolio.documents.application

import com.verifolio.documents.DocumentTombstone
import com.verifolio.documents.OwnerErasure
import com.verifolio.jooq.tables.references.DOCUMENT
import com.verifolio.jooq.tables.references.DOCUMENT_VERSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class OwnerErasureImpl(
    private val dsl: DSLContext,
    private val tombstone: DocumentTombstone,
) : OwnerErasure {

    // Deliberately NOT @Transactional: DocumentTombstone.tombstone drives S3-delete-then-DB
    // per version, committing each in its own transaction (see DocumentTombstoneImpl). Holding
    // a transaction across those storage round-trips would be unsafe.
    override fun tombstoneForOwner(ownerProfileId: UUID): List<UUID> {
        val d = DOCUMENT
        val dv = DOCUMENT_VERSION
        // All non-tombstoned versions of the owner's documents. Already-TOMBSTONED versions are
        // excluded here (and the tombstone call is itself idempotent), so a re-run is a no-op.
        val versionIds = dsl.select(dv.ID)
            .from(dv)
            .join(d).on(dv.DOCUMENT_ID.eq(d.ID))
            .where(d.OWNER_PROFILE_ID.eq(ownerProfileId).and(dv.STATUS.ne("TOMBSTONED")))
            .orderBy(dv.ID.asc())
            .fetch(dv.ID)
            .filterNotNull()

        versionIds.forEach { tombstone.tombstone(it) }
        return versionIds
    }
}
