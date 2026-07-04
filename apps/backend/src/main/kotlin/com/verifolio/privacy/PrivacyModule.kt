/**
 * Privacy module: data subject requests (DELETION, EXPORT, REGION_MIGRATION,
 * CONSENT_WITHDRAWAL, CORRECTION), per-region SLA tracking, and
 * erasure/export orchestration (delegating module-owned data deletion to
 * each owning module).
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.privacy

/** Module marker — declares the privacy bounded context to Spring Modulith. */
internal object PrivacyModule
