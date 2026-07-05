package com.verifolio.publicpages.application

import com.verifolio.audit.AuditService
import com.verifolio.documents.ShareLinkAccess
import com.verifolio.documents.SharedVersionView
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import com.verifolio.profiles.ProfileService
import com.verifolio.requests.RequestPublicView
import com.verifolio.verification.SignalView
import com.verifolio.verification.VerificationSignals
import com.verifolio.publicpages.api.BadgeDto
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
        "Recommender name, title, organization, and relationship are stated by the recommender."

private const val PRIVACY_NOTICE =
    "Verifolio records anonymous, aggregated telemetry about page views and downloads " +
        "(hashed network metadata) for security and audit purposes."

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
        val view = shareLinkAccess.resolve(rawToken) ?: throw pageNotFound()

        val signals = collectSignals(view)
        val requestInfo = view.requestId?.let { requestPublicView.forRequest(it) }

        recordSampledView(view.shareLinkId, ipHash, userAgentHash)

        return VerificationPageResponse(
            header = PageHeaderDto(
                documentType = view.documentType,
                verificationId = view.shareLinkId.toString(),
                lastVerifiedAt = signals.mapNotNull { it.verifiedAt }.maxOrNull()?.toString(),
            ),
            recipient = RecipientDto(
                name = profileService.displayName(view.ownerProfileId) ?: "Verifolio user",
            ),
            recommender = requestInfo?.let {
                RecommenderDto(name = it.recommenderName, relationshipType = it.relationshipType)
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
            ),
            timeline = timeline(view, requestInfo?.requestCreatedAt, requestInfo?.responseSubmittedAt),
            disclaimer = DISCLAIMER,
            privacyNotice = PRIVACY_NOTICE,
        )
    }

    @Transactional
    fun downloadUrl(rawToken: String, ipHash: String?, userAgentHash: String?): PublicDownloadLinkResponse {
        val view = shareLinkAccess.resolve(rawToken) ?: throw pageNotFound()
        val link = shareLinkAccess.presignPinnedPdf(rawToken)

        // Downloads are always fully audited (docs/PUBLIC_VERIFICATION_PAGE.md).
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
        return PublicDownloadLinkResponse(url = link.url, expiresAt = link.expiresAt.toString())
    }

    // ---- helpers ----

    private fun collectSignals(view: SharedVersionView): List<SignalView> {
        val versionSignals = verificationSignals.listVerified("DOCUMENT_VERSION", view.versionId)
        val responseSignals = view.requestId
            ?.let { requestPublicView.latestResponseId(it) }
            ?.let { verificationSignals.listVerified("REFERENCE_RESPONSE", it) }
            ?: emptyList()
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
