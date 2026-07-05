package com.verifolio.workflows

import java.time.Duration

/**
 * Public API of the workflows module — the ADR-0005 DB-scheduler fallback.
 * Domain modules implement this interface as Spring beans; the workflows module schedules
 * them without knowing any domain logic. The later Temporal migration replaces the
 * scheduling infrastructure behind this interface without touching implementations'
 * callers (workflow logic must not leak engine APIs into domain modules — ADR-0005).
 */
interface RecurringTask {
    val name: String
    val interval: Duration

    /**
     * One tick. Implementations must be idempotent (re-running immediately must not
     * repeat side effects). Exceptions are logged by the runner and never propagate.
     */
    fun run()
}
