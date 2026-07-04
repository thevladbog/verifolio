package com.verifolio.profiles.application

import com.verifolio.identity.UserAccountCreated
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for UserAccountCreated events published by the identity module and
 * auto-creates a profile for the new account.
 *
 * Uses @TransactionalEventListener(AFTER_COMMIT) rather than Spring Modulith's
 * @ApplicationModuleListener because:
 * - No additional DB infrastructure (event publication registry tables) is required.
 * - AFTER_COMMIT guarantees the account row is visible before we insert the profile.
 * - Execution is synchronous in the same thread, which makes integration tests
 *   deterministic without polling delays (the listener runs before the HTTP response
 *   returns from the session-create request).
 *
 * Trade-off: if the profile insert fails the event is lost (no retry). Failures are
 * logged but NOT rethrown — re-throwing from an AFTER_COMMIT listener propagates the
 * exception to the HTTP response layer, which would return a 500 and cause the session
 * cookie to be lost. Instead, the system self-heals: on the next profile access
 * (GET /api/v1/profile or any contact operation) ProfileApplicationService.createIfAbsent
 * is called automatically, so a missed listener never leaves the user permanently stuck.
 *
 * createIfAbsent uses ON CONFLICT DO NOTHING so a duplicate insert is safe.
 * A persistent event store (@ApplicationModuleListener) is tracked for production
 * hardening in a follow-up.
 */
@Component
internal class UserAccountCreatedListener(
    private val profileApplicationService: ProfileApplicationService,
) {
    private val log = LoggerFactory.getLogger(UserAccountCreatedListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUserAccountCreated(event: UserAccountCreated) {
        try {
            profileApplicationService.createIfAbsent(event.userAccountId, event.email)
        } catch (ex: Exception) {
            log.error(
                "Profile auto-creation failed for account {} (will self-heal on next profile access)",
                event.userAccountId,
                ex,
            )
        }
    }
}
