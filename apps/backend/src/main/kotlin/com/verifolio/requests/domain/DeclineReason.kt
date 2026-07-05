package com.verifolio.requests.domain

/**
 * Optional decline reason category chosen by the recommender. Enum-only by design:
 * free text from an unauthenticated one-click link is a PII/abuse/erasure liability.
 * Values mirror the `reference_request.declined_reason` CHECK constraint (V10).
 */
enum class DeclineReason {
    DONT_KNOW_REQUESTER,
    TOO_BUSY,
    NOT_COMFORTABLE,
    OTHER,
}
