package com.verifolio.requests.domain

import com.verifolio.requests.domain.ReferenceRequestStatus.CANCELLED
import com.verifolio.requests.domain.ReferenceRequestStatus.COMPLETED
import com.verifolio.requests.domain.ReferenceRequestStatus.CORRECTION_REQUESTED
import com.verifolio.requests.domain.ReferenceRequestStatus.CREATED
import com.verifolio.requests.domain.ReferenceRequestStatus.DECLINED
import com.verifolio.requests.domain.ReferenceRequestStatus.EXPIRED
import com.verifolio.requests.domain.ReferenceRequestStatus.IN_PROGRESS
import com.verifolio.requests.domain.ReferenceRequestStatus.NEEDS_REVIEW
import com.verifolio.requests.domain.ReferenceRequestStatus.OPENED
import com.verifolio.requests.domain.ReferenceRequestStatus.SENT
import com.verifolio.requests.domain.ReferenceRequestStatus.SUBMITTED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReferenceRequestStatusTest {

    @Test
    fun `happy path chain is allowed`() {
        val chain = listOf(CREATED, SENT, OPENED, IN_PROGRESS, SUBMITTED, NEEDS_REVIEW, COMPLETED)
        chain.zipWithNext().forEach { (from, to) ->
            assertThat(from.canTransitionTo(to)).describedAs("$from -> $to").isTrue()
        }
    }

    @Test
    fun `correction loop is allowed`() {
        assertThat(NEEDS_REVIEW.canTransitionTo(CORRECTION_REQUESTED)).isTrue()
        assertThat(CORRECTION_REQUESTED.canTransitionTo(IN_PROGRESS)).isTrue()
    }

    @Test
    fun `cancel is allowed from every non-terminal status and no terminal status`() {
        ReferenceRequestStatus.entries.forEach { status ->
            assertThat(status.canTransitionTo(CANCELLED))
                .describedAs("$status -> CANCELLED")
                .isEqualTo(!status.terminal)
        }
    }

    @Test
    fun `terminal statuses have no outgoing transitions`() {
        val terminals = listOf(COMPLETED, DECLINED, EXPIRED, CANCELLED)
        terminals.forEach { from ->
            ReferenceRequestStatus.entries.forEach { to ->
                assertThat(from.canTransitionTo(to)).describedAs("$from -> $to").isFalse()
            }
        }
    }

    @Test
    fun `sent is reachable only from created`() {
        ReferenceRequestStatus.entries.forEach { from ->
            assertThat(from.canTransitionTo(SENT))
                .describedAs("$from -> SENT")
                .isEqualTo(from == CREATED)
        }
    }

    @Test
    fun `decline and expire follow the transition table`() {
        assertThat(SENT.canTransitionTo(DECLINED)).isTrue()
        assertThat(OPENED.canTransitionTo(DECLINED)).isTrue()
        assertThat(IN_PROGRESS.canTransitionTo(DECLINED)).isTrue()
        assertThat(CORRECTION_REQUESTED.canTransitionTo(DECLINED)).isFalse()
        assertThat(CREATED.canTransitionTo(DECLINED)).isFalse()

        assertThat(CORRECTION_REQUESTED.canTransitionTo(EXPIRED)).isTrue()
        assertThat(CREATED.canTransitionTo(EXPIRED)).isFalse()
        assertThat(NEEDS_REVIEW.canTransitionTo(EXPIRED)).isFalse()
    }

    @Test
    fun `nothing transitions back to created`() {
        ReferenceRequestStatus.entries.forEach { from ->
            assertThat(from.canTransitionTo(CREATED)).describedAs("$from -> CREATED").isFalse()
        }
    }
}
