package com.verifolio.workflows.infrastructure

import com.verifolio.platform.VerifolioProperties
import com.verifolio.workflows.RecurringTask
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Component

/**
 * Registers every RecurringTask bean with a fixed-delay schedule. Single-instance only
 * (no distributed locking) — consistent with the in-process rate limiters; the Temporal
 * migration or a DB lock arrives before multi-instance cells.
 */
@Component
internal class RecurringTaskRunner(
    private val tasks: List<RecurringTask>,
    private val props: VerifolioProperties,
) {

    private val log = LoggerFactory.getLogger(RecurringTaskRunner::class.java)

    private val scheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("workflows-")
        initialize()
    }

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!props.workflows.enabled) {
            log.info("Recurring tasks disabled (verifolio.workflows.enabled=false)")
            return
        }
        tasks.forEach { task ->
            scheduler.scheduleWithFixedDelay({ runSafely(task) }, task.interval)
            log.info("Scheduled recurring task '{}' every {}", task.name, task.interval)
        }
    }

    private fun runSafely(task: RecurringTask) {
        try {
            task.run()
        } catch (e: Exception) {
            // Never let one failing tick kill the schedule; the next tick retries.
            log.error("Recurring task '{}' failed", task.name, e)
        }
    }
}
