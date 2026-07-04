/**
 * Platform module: shared cross-cutting infrastructure — configuration primitives,
 * error handling conventions, web filter chains, and utilities shared by domain modules.
 * Domain modules may depend on platform; platform must not depend on domain modules.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.platform

/** Module marker — declares the platform infrastructure module to Spring Modulith. */
internal object PlatformModule
