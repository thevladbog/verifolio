package com.verifolio.admin.application

import com.verifolio.admin.domain.AdminAccount
import com.verifolio.jooq.tables.records.AdminMfaPendingRecord
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.ADMIN_MFA_PENDING
import com.verifolio.platform.ApiException
import com.verifolio.platform.TokenGenerator
import com.verifolio.platform.TokenHasher
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime

/** MFA branch after magic-link consume (spec §Flow step 2-3). ENROLL on first login, else CHALLENGE. */
enum class AdminMfaState { ENROLL, CHALLENGE }

/** A freshly created pending-MFA record: raw cookie value, branch state, TTL seconds. */
data class AdminPendingCreated(val rawToken: String, val state: AdminMfaState, val ttlSeconds: Long)

/** Enrollment material shown once during ENROLL: the Base32 secret and its otpauth URI. */
data class AdminEnrollmentInfo(val secretBase32: String, val otpauthUri: String)

/**
 * Pending-MFA lifecycle (spec §Flow, §Schema `admin_mfa_pending`). Post-magic-link, pre-session:
 * holds the challenge state and, during enrollment, the not-yet-committed encrypted secret. Short
 * TTL (5m), attempt-capped (5) via an atomic REQUIRES_NEW claim. An admin session is minted ONLY
 * after a factor here succeeds (the caller mints it from the returned account). All failures throw
 * a single CODE_INVALID (400) — never leaking whether the state was ENROLL/CHALLENGE/expired.
 */
@Service
internal class AdminMfa(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val cipher: AdminTotpCipher,
    private val totpService: AdminTotpService,
    private val accounts: AdminAccounts,
    private val attemptRecorder: AdminMfaAttemptRecorder,
) {

    /**
     * Opens a pending-MFA row for [account] and returns the raw pending cookie + branch state.
     * Any prior unconsumed pending for the same admin is consumed first (reissue supersedes).
     * In ENROLL, a fresh secret is generated and stored encrypted (not yet on the account).
     */
    @Transactional
    fun startPending(account: AdminAccount): AdminPendingCreated {
        val now = OffsetDateTime.now()
        val state = if (account.isEnrolled) AdminMfaState.CHALLENGE else AdminMfaState.ENROLL

        dsl.update(ADMIN_MFA_PENDING)
            .set(ADMIN_MFA_PENDING.CONSUMED_AT, now)
            .where(ADMIN_MFA_PENDING.ADMIN_ACCOUNT_ID.eq(account.id))
            .and(ADMIN_MFA_PENDING.CONSUMED_AT.isNull)
            .execute()

        val enrollSecretEnc = if (state == AdminMfaState.ENROLL) {
            cipher.encrypt(totpService.generateSecret())
        } else {
            null
        }

        val raw = TokenGenerator.generate()
        dsl.insertInto(ADMIN_MFA_PENDING)
            .set(ADMIN_MFA_PENDING.ADMIN_ACCOUNT_ID, account.id)
            .set(ADMIN_MFA_PENDING.TOKEN_HASH, hasher.hash(raw))
            .set(ADMIN_MFA_PENDING.ENROLL_SECRET_ENC, enrollSecretEnc)
            .set(ADMIN_MFA_PENDING.EXPIRES_AT, now.plus(TTL))
            .execute()
        return AdminPendingCreated(raw, state, TTL.seconds)
    }

    /** Returns the ENROLL secret + otpauth URI for a valid ENROLL pending; throws CODE_INVALID otherwise. */
    @Transactional(readOnly = true)
    fun enrollment(pendingRaw: String): AdminEnrollmentInfo {
        val pending = loadValidPending(pendingRaw)
        val enc = pending.enrollSecretEnc ?: throw codeInvalid() // not an ENROLL pending
        val secret = cipher.decrypt(enc)
        val account = accounts.byId(pending.adminAccountId!!) ?: throw codeInvalid()
        return AdminEnrollmentInfo(secret, totpService.otpauthUri(account.email, secret, account.region))
    }

    /**
     * Completes ENROLL: verifies [code] against the pending enroll secret; on success re-encrypts
     * the same secret onto the account, sets mfa_enrolled_at, consumes the pending, and returns the
     * account (caller mints the session). Attempt-capped; on cap-exhaustion the pending is
     * invalidated and CODE_INVALID thrown.
     */
    @Transactional
    fun enroll(pendingRaw: String, code: String): AdminAccount {
        val now = OffsetDateTime.now()
        val pending = loadValidPending(pendingRaw)
        val enc = pending.enrollSecretEnc ?: throw codeInvalid()

        if (attemptRecorder.claimAttempt(pending.id!!, MAX_ATTEMPTS) == 0) {
            // Independent tx: retire the pending even though this call is about to roll back.
            attemptRecorder.invalidate(pending.id!!)
            throw codeInvalid()
        }
        val secret = cipher.decrypt(enc)
        if (!totpService.verify(secret, code)) throw codeInvalid()

        dsl.update(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.TOTP_SECRET_ENC, cipher.encrypt(secret))
            .set(ADMIN_ACCOUNT.MFA_ENROLLED_AT, now)
            .set(ADMIN_ACCOUNT.UPDATED_AT, now)
            .where(ADMIN_ACCOUNT.ID.eq(pending.adminAccountId))
            .execute()
        consume(pending.id!!, now)
        return accounts.byId(pending.adminAccountId!!)!!
    }

    /**
     * Completes CHALLENGE: atomically claims one of the [MAX_ATTEMPTS] slots BEFORE comparing, so
     * concurrent guesses can never exceed the cap. On cap-exhaustion consumes the pending and throws;
     * on a valid [code] against the account's decrypted secret consumes the pending and returns the
     * account (caller mints the session).
     */
    @Transactional
    fun verifyChallenge(pendingRaw: String, code: String): AdminAccount {
        val now = OffsetDateTime.now()
        val pending = loadValidPending(pendingRaw)

        if (attemptRecorder.claimAttempt(pending.id!!, MAX_ATTEMPTS) == 0) {
            // Independent tx: retire the pending even though this call is about to roll back.
            attemptRecorder.invalidate(pending.id!!)
            throw codeInvalid()
        }
        val account = accounts.byId(pending.adminAccountId!!) ?: throw codeInvalid()
        val enc = account.totpSecretEnc ?: throw codeInvalid()
        val secret = cipher.decrypt(enc)
        if (!totpService.verify(secret, code)) throw codeInvalid()

        consume(pending.id!!, now)
        return account
    }

    // No FOR UPDATE: the attempt claim runs REQUIRES_NEW and would deadlock against a row lock held
    // here (DsrVerificationCodes precedent).
    private fun loadValidPending(pendingRaw: String): AdminMfaPendingRecord =
        dsl.selectFrom(ADMIN_MFA_PENDING)
            .where(ADMIN_MFA_PENDING.TOKEN_HASH.eq(hasher.hash(pendingRaw)))
            .and(ADMIN_MFA_PENDING.CONSUMED_AT.isNull)
            .and(ADMIN_MFA_PENDING.EXPIRES_AT.gt(OffsetDateTime.now()))
            .fetchOne() ?: throw codeInvalid()

    /**
     * Single-use guard: marks the pending consumed only if it was still unconsumed. Two concurrent
     * valid submissions race here — exactly one updates a row; the loser sees 0 rows affected and is
     * rejected with CODE_INVALID, so it can never proceed to mint a session. Callers MUST invoke this
     * before the session is minted.
     */
    private fun consume(pendingId: java.util.UUID, now: OffsetDateTime) {
        val updated = dsl.update(ADMIN_MFA_PENDING)
            .set(ADMIN_MFA_PENDING.CONSUMED_AT, now)
            .where(ADMIN_MFA_PENDING.ID.eq(pendingId).and(ADMIN_MFA_PENDING.CONSUMED_AT.isNull))
            .execute()
        if (updated == 0) throw codeInvalid()
    }

    private fun codeInvalid() =
        ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "The code is invalid or expired")

    private companion object {
        val TTL: Duration = Duration.ofMinutes(5)
        const val MAX_ATTEMPTS = 5
    }
}
