/**
 * Audit module: audit events, sensitive action logging,
 * append-only event records, and actor/action/entity metadata.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.audit

/** Module marker — declares the audit bounded context to Spring Modulith. */
internal object AuditModule
