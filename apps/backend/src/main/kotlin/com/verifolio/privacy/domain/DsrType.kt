package com.verifolio.privacy.domain

/** Data-subject-request types accepted from both channels (DATA_MODEL.md §532-569). */
enum class DsrType {
    DELETION,
    EXPORT,
    REGION_MIGRATION,
    CONSENT_WITHDRAWAL,
    CORRECTION,
}
