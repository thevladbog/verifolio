/**
 * Verification module: verification signal records (single owner), trust summary derivation,
 * verification status, evidence metadata, and verification page display model.
 * Other modules must consume read models only; direct internal access is forbidden.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.verification

/** Module marker — declares the verification bounded context to Spring Modulith. */
internal object VerificationModule
