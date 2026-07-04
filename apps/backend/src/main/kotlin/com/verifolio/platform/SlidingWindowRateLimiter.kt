package com.verifolio.platform

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-process sliding window; per-instance only (single cell, single instance for MVP). */
class SlidingWindowRateLimiter(private val limit: Int, private val window: Duration) {
    private val hits = ConcurrentHashMap<String, MutableList<Instant>>()

    fun tryAcquire(key: String, now: Instant = Instant.now()): Boolean {
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeIf { it.isBefore(now.minus(window)) }
            if (list.size >= limit) return false
            list.add(now)
            return true
        }
    }
}
