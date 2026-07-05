package com.verifolio.publicpages.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.ShareLinkAccess
import com.verifolio.documents.SharedVersionView
import com.verifolio.documents.TombstonedVersionView
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.RequestPublicView
import com.verifolio.verification.SignalView
import com.verifolio.verification.VerificationSignals
import com.verifolio.publicpages.api.BadgeDto
import com.verifolio.publicpages.api.DownloadDto
import com.verifolio.publicpages.api.PageHeaderDto
import com.verifolio.publicpages.api.PublicDownloadLinkResponse
import com.verifolio.publicpages.api.RecipientDto
import com.verifolio.publicpages.api.RecommenderDto
import com.verifolio.publicpages.api.TimelineEntryDto
import com.verifolio.publicpages.api.VerificationPageResponse
import com.verifolio.publicpages.api.VersionDto
import com.verifolio.verification.BadgeCatalog
import com.verifolio.verification.TrustSummary
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.util.UUID

private const val DISCLAIMER =
    "Verifolio verifies identity signals, recommender confirmation methods, and document integrity. " +
        "It does not independently guarantee the truth of every statement inside the document. " +
        "The recommender's name was provided by the requester; the relationship was confirmed " +
        "by the recommender at submission."

private const val PRIVACY_NOTICE =
    "Verifolio records page views and downloads for security and audit purposes, including " +
        "keyed hashes of your IP address and browser identifier. These hashes are treated as " +
        "personal data and are retained under Verifolio's audit retention policy."

private const val TOMBSTONE_NOTICE =
    "The content of this document was removed at the data subject's request. The verification " +
        "record remains, but the document and its attachments are no longer available."

@Service
internal class PublicVerificationPageService(
    private val shareLinkAccess: ShareLinkAccess,
    private val requestPublicView: RequestPublicView,
    private val profileService: ProfileService,
    private val verificationSignals: VerificationSignals,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun page(rawToken: String, ipHash: String?, userAgentHash: String?): VerificationPageResponse {
        val view = shareLinkAccess.resolve(rawToken)
            // A valid token pinned to a tombstoned version renders the neutral notice shape;
            // an unknown/revoked/expired token stays a 404 (no state oracle).
            ?: return shareLinkAccess.resolveTombstonedNotice(rawToken)
                ?.let { tombstonedResponse(it) }
                ?: throw pageNotFound()

        val signals = collectSignals(view)
        val requestInfo = view.requestId?.let { requestPublicView.forRequest(it) }

        recordSampledView(view.shareLinkId, ipHash, userAgentHash)

        return VerificationPageResponse(
            status = "ACTIVE",
            header = PageHeaderDto(
                documentType = view.documentType,
                verificationId = view.shareLinkId.toString(),
                lastVerifiedAt = signals.mapNotNull { it.verifiedAt }.maxOrNull()?.toString(),
            ),
            recipient = RecipientDto(
                // Lock-time snapshot from the pinned version content; the profile lookup
                // covers only pre-snapshot rows.
                name = view.recipientName
                    ?: profileService.displayName(view.ownerProfileId)
                    ?: "Verifolio user",
            ),
            // Omit the recommender block once the name snapshot has been erased (retraction /
            // PII erasure) — otherwise the page would leak an empty name and, before this guard,
            // threw on the erased column.
            recommender = requestInfo?.recommenderName?.let { name ->
                RecommenderDto(name = name, relationshipType = requestInfo.relationshipType)
            },
            badges = signals.map { signal ->
                val text = BadgeCatalog.describe(signal.signalType)
                BadgeDto(
                    signalType = signal.signalType,
                    title = text.title,
                    status = signal.status,
                    date = signal.verifiedAt?.toString(),
                    limitation = text.limitation,
                )
            },
            trustSummary = TrustSummary.derive(signals),
            version = VersionDto(
                versionNumber = view.versionNumber,
                lockedAt = view.lockedAt.toString(),
                status = view.versionStatus,
                supersededByNewerVersion = view.supersededByNewerVersion,
                retractedAt = view.retractedAt?.toString(),
            ),
            downloads = buildList {
                add(DownloadDto(id = "generated-pdf", kind = "GENERATED_PDF", filename = null, downloadable = true))
                view.attachments.forEach {
                    add(DownloadDto(it.attachmentId.toString(), it.kind, it.filename, it.publiclyDownloadable))
                }
            },
            timeline = timeline(view, requestInfo?.requestCreatedAt, requestInfo?.responseSubmittedAt),
            disclaimer = DISCLAIMER,
            privacyNotice = PRIVACY_NOTICE,
        )
    }

    @Transactional
    fun downloadUrl(rawToken: String, ipHash: String?, userAgentHash: String?): PublicDownloadLinkResponse {
        val view = shareLinkAccess.resolve(rawToken) ?: throw pageNotFound()
        val pinned = shareLinkAccess.presignPinnedPdf(rawToken)

        // Downloads are always fully audited (docs/PUBLIC_VERIFICATION_PAGE.md):
        // the page-level event plus the file-level grant, matching the authenticated path.
        audit.record(
            actorType = "PUBLIC_VIEWER",
            actorId = null,
            action = "PUBLIC_VERIFICATION_PAGE_DOWNLOAD",
            entityType = "SHARE_LINK",
            entityId = view.shareLinkId.toString(),
            metadata = mapOf("documentId" to view.documentId.toString(), "versionNumber" to view.versionNumber.toString()),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        audit.record(
            actorType = "PUBLIC_VIEWER",
            actorId = null,
            action = "FILE_DOWNLOAD_GRANTED",
            entityType = "FILE_OBJECT",
            entityId = pinned.fileId.toString(),
            metadata = mapOf(
                "shareLinkId" to view.shareLinkId.toString(),
                "documentId" to view.documentId.toString(),
                "purpose" to "GENERATED_PDF",
            ),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
        return PublicDownloadLinkResponse(url = pinned.download.url, expiresAt = pinned.download.expiresAt.toString())
    }

    @Transactional
    fun attachmentDownloadUrl(
        rawToken: String,
        attachmentId: UUID,
        ipHash: String?,
        userAgentHash: String?,
    ): PublicDownloadLinkResponse {
        val view = shareLinkAccess.resolve(rawToken) ?: throw pageNotFound()
        val pinned = shareLinkAccess.presignAttachment(rawToken, attachmentId)

        audit.record(
            actorType = "PUBLIC_VIEWER", actorId = null,
            action = "PUBLIC_VERIFICATION_PAGE_DOWNLOAD",
            entityType = "SHARE_LINK", entityId = view.shareLinkId.toString(),
            metadata = mapOf("documentId" to view.documentId.toString(), "attachmentId" to attachmentId.toString()),
            ipHash = ipHash, userAgentHash = userAgentHash,
        )
        audit.record(
            actorType = "PUBLIC_VIEWER", actorId = null,
            action = "FILE_DOWNLOAD_GRANTED",
            entityType = "FILE_OBJECT", entityId = pinned.fileId.toString(),
            metadata = mapOf("shareLinkId" to view.shareLinkId.toString(), "attachmentId" to attachmentId.toString()),
            ipHash = ipHash, userAgentHash = userAgentHash,
        )
        return PublicDownloadLinkResponse(url = pinned.download.url, expiresAt = pinned.download.expiresAt.toString())
    }

    // ---- helpers ----

    /**
     * Tombstoned pinned version: header + neutral notice only. No recipient, recommender,
     * signals, downloads or timeline are exposed — the content was erased at the subject's
     * request. The download-url endpoints separately 404 (the version no longer resolves).
     */
    private fun tombstonedResponse(view: TombstonedVersionView): VerificationPageResponse =
        VerificationPageResponse(
            status = "TOMBSTONED",
            header = PageHeaderDto(
                documentType = view.documentType,
                verificationId = view.shareLinkId.toString(),
                lastVerifiedAt = null,
            ),
            recipient = null,
            recommender = null,
            badges = emptyList(),
            trustSummary = emptyMap(),
            version = null,
            downloads = emptyList(),
            timeline = emptyList(),
            disclaimer = null,
            privacyNotice = null,
            notice = TOMBSTONE_NOTICE,
        )

    private fun collectSignals(view: SharedVersionView): List<SignalView> {
        // listForDisplay surfaces VERIFIED + REVOKED so a retracted recommendation shows its
        // signals in their REVOKED state (docs/PUBLIC_VERIFICATION_PAGE.md). Non-retracted pages
        // are unaffected — they carry no REVOKED rows.
        val versionSignals = verificationSignals.listForDisplay("DOCUMENT_VERSION", view.versionId)
        val responseSignals = view.requestId
            ?.let { requestPublicView.latestResponseId(it) }
            ?.let { verificationSignals.listForDisplay("REFERENCE_RESPONSE", it) }
            ?: emptyList()
        // Share-link signals keep the VERIFIED-only read: an EXPIRED link stops resolving before
        // this point, and a live link's PUBLIC_VERIFICATION_ENABLED signal is not part of retraction.
        val linkSignals = verificationSignals.listVerified("SHARE_LINK", view.shareLinkId)
        return responseSignals + versionSignals + linkSignals
    }

    private fun timeline(
        view: SharedVersionView,
        requestCreatedAt: java.time.OffsetDateTime?,
        submittedAt: java.time.OffsetDateTime?,
    ): List<TimelineEntryDto> = buildList {
        requestCreatedAt?.let { add(TimelineEntryDto("Request sent", it.toString())) }
        submittedAt?.let { add(TimelineEntryDto("Response submitted", it.toString())) }
        add(TimelineEntryDto("Recipient accepted", view.lockedAt.toString()))
        add(TimelineEntryDto("Version locked", view.lockedAt.toString()))
        add(TimelineEntryDto("Share link created", view.shareLinkCreatedAt.toString()))
    }

    private fun recordSampledView(shareLinkId: UUID, ipHash: String?, userAgentHash: String?) {
        val rate = props.publicPage.viewAuditSampleRate
        if (rate <= 0.0) return
        if (rate < 1.0 && random.nextDouble() >= rate) return
        audit.record(
            actorType = "PUBLIC_VIEWER",
            actorId = null,
            action = "PUBLIC_VERIFICATION_PAGE_VIEWED",
            entityType = "SHARE_LINK",
            entityId = shareLinkId.toString(),
            metadata = mapOf("sampleRate" to rate.toString()),
            ipHash = ipHash,
            userAgentHash = userAgentHash,
        )
    }

    private fun pageNotFound() =
        ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Verification page not found")
}
