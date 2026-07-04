package com.verifolio.profiles.application

import com.verifolio.identity.UserAccountCreated
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
 * Trade-off: if the profile insert fails after commit the event is lost (no retry).
 * createIfAbsent uses ON CONFLICT DO NOTHING so a duplicate insert is safe.
 * A persistent event store (@ApplicationModuleListener) is tracked for production
 * hardening in a follow-up.
 */
@Component
internal class UserAccountCreatedListener(
    private val profileApplicationService: ProfileApplicationService,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onUserAccountCreated(event: UserAccountCreated) {
        profileApplicationService.createIfAbsent(event.userAccountId, event.email)
    }
}
