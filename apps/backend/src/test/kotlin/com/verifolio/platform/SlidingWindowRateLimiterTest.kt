package com.verifolio.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SlidingWindowRateLimiterTest {

    @Test
    fun `allows up to the limit within the window`() {
        val limiter = SlidingWindowRateLimiter(limit = 3, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-07-04T10:00:00Z")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isFalse()
    }

    @Test
    fun `window slides - old entries expire`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-07-04T10:00:00Z")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0.plusSeconds(901))).isTrue()
    }

    @Test
    fun `keys are independent`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        val t0 = Instant.now()
        assertThat(limiter.tryAcquire("a", t0)).isTrue()
        assertThat(limiter.tryAcquire("b", t0)).isTrue()
    }

    @Test
    fun `release refunds the most recent acquisition`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-07-04T10:00:00Z")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isFalse()
        limiter.release("k")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
    }

    @Test
    fun `release on unknown or empty key is a no-op`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        limiter.release("missing")
        assertThat(limiter.tryAcquire("missing", Instant.now())).isTrue()
    }
}
