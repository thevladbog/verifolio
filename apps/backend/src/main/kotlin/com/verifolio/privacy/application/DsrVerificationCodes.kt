package com.verifolio.privacy.application

import com.verifolio.jooq.tables.references.DSR_VERIFICATION_CODE
import com.verifolio.platform.ApiException
import com.verifolio.platform.TokenHasher
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

private const val MAX_CODE_ATTEMPTS = 5
private val CODE_TTL = Duration.ofMinutes(10)

/**
 * 6-digit HMAC-hashed verification codes for the account-less recommender DSR channel
 * (email_confirmation_code precedents: TTL, max-attempts, attempts++ REQUIRES_NEW).
 */
@Service
internal class DsrVerificationCodes(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val attemptRecorder: DsrCodeAttemptRecorder,
) {

    private val random = SecureRandom()

    /** Issues a fresh 6-digit code for [dsrId]; returns the raw code for the caller to email. */
    @Transactional
    fun issue(dsrId: UUID): String {
        val rawCode = "%06d".format(random.nextInt(1_000_000))
        val dvc = DSR_VERIFICATION_CODE
        dsl.insertInto(dvc)
            .set(dvc.ID, UUID.randomUUID())
            .set(dvc.DSR_ID, dsrId)
            .set(dvc.CODE_HASH, hasher.hash(rawCode))
            .set(dvc.EXPIRES_AT, OffsetDateTime.now().plus(CODE_TTL))
            .execute()
        return rawCode
    }

    /**
     * Verifies [rawCode] against the latest unconsumed code for [dsrId]. Every verify first
     * atomically claims one of the [MAX_CODE_ATTEMPTS] attempt slots (REQUIRES_NEW, committed even
     * when this call rolls back), so concurrent guesses can never exceed the cap — closing the
     * former read-check-then-act race. Consumes the code on a hash match; throws CODE_INVALID (400)
     * when expired, when the attempt cap is already reached, or on any mismatch.
     */
    @Transactional
    fun verify(dsrId: UUID, rawCode: String) {
        val dvc = DSR_VERIFICATION_CODE
        val now = OffsetDateTime.now()
        // No FOR UPDATE: the attempt claim runs REQUIRES_NEW and would deadlock against a row
        // lock held here (ConfirmationAttemptRecorder precedent).
        val codeRow = dsl.selectFrom(dvc)
            .where(dvc.DSR_ID.eq(dsrId).and(dvc.CONSUMED_AT.isNull))
            .orderBy(dvc.CREATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?: throw codeInvalid()

        if (!codeRow.expiresAt!!.isAfter(now)) throw codeInvalid()

        // Atomically claim an attempt slot BEFORE comparing the hash. A 0-row result means the cap
        // is already reached (or was reached by a concurrent guess), so at most MAX_CODE_ATTEMPTS
        // verifies ever reach the comparison below.
        if (attemptRecorder.claimAttempt(codeRow.id!!, MAX_CODE_ATTEMPTS) == 0) throw codeInvalid()

        if (codeRow.codeHash != hasher.hash(rawCode)) throw codeInvalid()

        val consumed = dsl.update(dvc)
            .set(dvc.CONSUMED_AT, now)
            .where(dvc.ID.eq(codeRow.id).and(dvc.CONSUMED_AT.isNull))
            .execute()
        if (consumed == 0) throw codeInvalid()
    }

    private fun codeInvalid() =
        ApiException(HttpStatus.BAD_REQUEST, "CODE_INVALID", "The verification code is invalid or expired")
}
