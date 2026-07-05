package com.verifolio.documents

import com.verifolio.files.DownloadLink
import java.time.OffsetDateTime
import java.util.UUID

data class SharedVersionView(
    val shareLinkId: UUID,
    val documentId: UUID,
    val documentType: String,
    val ownerProfileId: UUID,
    /** Recipient display name snapshotted into the locked version content at publish time. */
    val recipientName: String?,
    val requestId: UUID?,
    val versionId: UUID,
    val versionNumber: Int,
    val lockedAt: OffsetDateTime,
    val versionStatus: String,
    val supersededByNewerVersion: Boolean,
    val shareLinkCreatedAt: OffsetDateTime,
)

/**
 * Public API: token-gated read access to a shared, version-pinned document
 * (docs/PUBLIC_VERIFICATION_PAGE.md access model). The raw token is the only public
 * credential; the DB stores its HMAC hash.
 */
interface ShareLinkAccess {
    /** null when the token is unknown, revoked, expired, or the pinned version is tombstoned. */
    fun resolve(rawToken: String): SharedVersionView?

    /** Presigned GET for the pinned version's generated PDF. Throws NOT_FOUND when invalid. */
    fun presignPinnedPdf(rawToken: String): PinnedPdf
}

/** The file id accompanies the link so callers can emit file-level audit events. */
data class PinnedPdf(val download: DownloadLink, val fileId: UUID)
