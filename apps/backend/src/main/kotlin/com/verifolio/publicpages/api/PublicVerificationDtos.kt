package com.verifolio.publicpages.api

data class PageHeaderDto(
    val documentType: String,
    val verificationId: String,
    val lastVerifiedAt: String?,
)

data class RecipientDto(val name: String)

data class RecommenderDto(
    val name: String,
    /** The recommender never submits a name; it comes from the requester's contact entry. */
    val nameSource: String = "provided-by-requester",
    val relationshipType: String?,
    /** The relationship value is confirmed by the recommender at submission. */
    val relationshipSource: String = "confirmed-by-recommender",
)

data class BadgeDto(
    val signalType: String,
    val title: String,
    val status: String,
    val date: String?,
    val limitation: String?,
    /**
     * The verified organization's public name, present only on a CORPORATE_DOMAIN_CONFIRMED
     * badge whose evidence was snapshotted from a VERIFIED organization record. Null otherwise
     * (including recommender-stated corporate domains and all non-corporate badges).
     */
    val organizationName: String? = null,
    /**
     * Provenance of [organizationName]: "verified-record" when set. Null when no verified
     * organization name is available, so the frontend keeps the existing framing.
     */
    val organizationSource: String? = null,
)

data class VersionDto(
    val versionNumber: Int,
    val lockedAt: String,
    val status: String,
    val supersededByNewerVersion: Boolean,
    /** Set when the recommender retracted; the frontend renders the retracted banner. */
    val retractedAt: String? = null,
)

data class TimelineEntryDto(val event: String, val at: String)

data class DownloadDto(
    /** "generated-pdf" or the attachment id. */
    val id: String,
    val kind: String,
    val filename: String?,
    val downloadable: Boolean,
)

data class VerificationPageResponse(
    /** "ACTIVE" for a live/retracted version, "TOMBSTONED" for the erased-content notice shape. */
    val status: String,
    val header: PageHeaderDto,
    /** Null in the tombstoned shape — no recipient/recommender/signals/downloads are exposed. */
    val recipient: RecipientDto?,
    val recommender: RecommenderDto?,
    val badges: List<BadgeDto>,
    val trustSummary: Map<String, Int>,
    val version: VersionDto?,
    val downloads: List<DownloadDto>,
    val timeline: List<TimelineEntryDto>,
    val disclaimer: String?,
    val privacyNotice: String?,
    /** Neutral notice for the tombstoned shape; null otherwise. */
    val notice: String? = null,
)

data class PublicDownloadLinkResponse(
    val url: String,
    val expiresAt: String,
)
