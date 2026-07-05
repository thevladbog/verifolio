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
)

data class VersionDto(
    val versionNumber: Int,
    val lockedAt: String,
    val status: String,
    val supersededByNewerVersion: Boolean,
)

data class TimelineEntryDto(val event: String, val at: String)

data class VerificationPageResponse(
    val header: PageHeaderDto,
    val recipient: RecipientDto,
    val recommender: RecommenderDto?,
    val badges: List<BadgeDto>,
    val trustSummary: Map<String, Int>,
    val version: VersionDto,
    val timeline: List<TimelineEntryDto>,
    val disclaimer: String,
    val privacyNotice: String,
)

data class PublicDownloadLinkResponse(
    val url: String,
    val expiresAt: String,
)
