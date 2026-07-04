/**
 * Workflows module: shared Temporal infrastructure — client configuration,
 * worker registration, and shared workflow testing utilities.
 * Must contain no domain logic; workflow definitions live in owning domain modules.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.workflows

/** Module marker — declares the workflows infrastructure module to Spring Modulith. */
internal object WorkflowsModule
