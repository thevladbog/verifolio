package com.verifolio.admin

import com.verifolio.admin.application.AdminAccounts
import com.verifolio.admin.application.AdminMagicLinks
import com.verifolio.admin.application.AdminMfa
import com.verifolio.admin.application.AdminMfaState
import com.verifolio.admin.application.AdminSessions
import com.verifolio.admin.application.AdminTotpService
import com.verifolio.admin.domain.AdminRole
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.ADMIN_MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.ADMIN_MFA_PENDING
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.platform.ApiException
import com.verifolio.platform.TokenHasher
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB-backed slice for the admin auth core (magic links, MFA enroll/challenge, attempt cap).
 * Bootstrap is off (bootstrap-emails empty by default), so this seeds admin rows directly.
 */
class AdminAuthUnitTest : IntegrationTest() {

    @Autowired lateinit var dsl: DSLContext
    @Autowired internal lateinit var magicLinks: AdminMagicLinks
    @Autowired internal lateinit var mfa: AdminMfa
    @Autowired internal lateinit var sessions: AdminSessions
    @Autowired lateinit var totp: AdminTotpService
    @Autowired internal lateinit var accounts: AdminAccounts
    @Autowired lateinit var hasher: TokenHasher

    private fun seedAdmin(email: String): UUID {
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.REGION, "local")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        return dsl.insertInto(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.USER_ACCOUNT_ID, userId)
            .set(ADMIN_ACCOUNT.EMAIL, email)
            .set(ADMIN_ACCOUNT.REGION, "local")
            .set(ADMIN_ACCOUNT.ROLE, AdminRole.SUPERADMIN.name)
            .set(ADMIN_ACCOUNT.STATUS, "ACTIVE")
            .returning(ADMIN_ACCOUNT.ID).fetchOne()!!.id!!
    }

    @Test
    fun `magic link mint then consume returns the active admin`() {
        val email = "ml-happy-${UUID.randomUUID()}@example.com"
        val adminId = seedAdmin(email)
        val raw = magicLinks.mint(email)
        val account = magicLinks.consume(raw)
        assertThat(account).isNotNull
        assertThat(account!!.id).isEqualTo(adminId)
        assertThat(account.role).isEqualTo(AdminRole.SUPERADMIN)
    }

    @Test
    fun `consumed magic link cannot be reused`() {
        val email = "ml-reuse-${UUID.randomUUID()}@example.com"
        seedAdmin(email)
        val raw = magicLinks.mint(email)
        assertThat(magicLinks.consume(raw)).isNotNull
        assertThat(magicLinks.consume(raw)).isNull() // single-use
    }

    @Test
    fun `expired magic link is rejected`() {
        val email = "ml-exp-${UUID.randomUUID()}@example.com"
        seedAdmin(email)
        val raw = magicLinks.mint(email)
        dsl.update(ADMIN_MAGIC_LINK_TOKEN)
            .set(ADMIN_MAGIC_LINK_TOKEN.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(ADMIN_MAGIC_LINK_TOKEN.TOKEN_HASH.eq(hasher.hash(raw)))
            .execute()
        assertThat(magicLinks.consume(raw)).isNull()
    }

    @Test
    fun `reissue invalidates the prior magic link`() {
        val email = "ml-reissue-${UUID.randomUUID()}@example.com"
        seedAdmin(email)
        val first = magicLinks.mint(email)
        magicLinks.mint(email) // reissue invalidates `first`
        assertThat(magicLinks.consume(first)).isNull()
    }

    @Test
    fun `enroll flow stores encrypted secret and sets mfa_enrolled_at then challenge succeeds`() {
        val email = "mfa-${UUID.randomUUID()}@example.com"
        val adminId = seedAdmin(email)

        // ENROLL: first login (mfa_enrolled_at null).
        val account = accounts.byId(adminId)!!
        val pending = mfa.startPending(account)
        assertThat(pending.state).isEqualTo(AdminMfaState.ENROLL)
        val enrollment = mfa.enrollment(pending.rawToken)
        val secret = enrollment.secretBase32
        assertThat(enrollment.otpauthUri).contains("secret=$secret")

        val enrolled = mfa.enroll(pending.rawToken, totp.currentCode(secret))
        assertThat(enrolled.mfaEnrolledAt).isNotNull
        val stored = dsl.select(ADMIN_ACCOUNT.TOTP_SECRET_ENC, ADMIN_ACCOUNT.MFA_ENROLLED_AT)
            .from(ADMIN_ACCOUNT).where(ADMIN_ACCOUNT.ID.eq(adminId)).fetchOne()!!
        assertThat(stored.value1()).isNotNull() // encrypted at rest
        assertThat(stored.value1()).doesNotContain(secret) // not plaintext
        assertThat(stored.value2()).isNotNull()

        // CHALLENGE: second login (now enrolled). The stored secret equals the enrollment secret.
        val account2 = accounts.byId(adminId)!!
        val pending2 = mfa.startPending(account2)
        assertThat(pending2.state).isEqualTo(AdminMfaState.CHALLENGE)
        val challenged = mfa.verifyChallenge(pending2.rawToken, totp.currentCode(secret))
        assertThat(challenged.id).isEqualTo(adminId)

        // A minted session resolves back to the admin actor.
        val session = sessions.mint(adminId, ipHash = null, userAgentHash = null)
        val actor = sessions.resolve(session.rawToken)
        assertThat(actor).isNotNull
        assertThat(actor!!.adminId).isEqualTo(adminId)
        sessions.revoke(session.rawToken)
        assertThat(sessions.resolve(session.rawToken)).isNull()
    }

    @Test
    fun `five wrong challenge codes cap and invalidate the pending`() {
        val email = "mfa-cap-${UUID.randomUUID()}@example.com"
        val adminId = seedAdmin(email)
        // Enroll first so a CHALLENGE is possible.
        val enrollPending = mfa.startPending(accounts.byId(adminId)!!)
        val secret = mfa.enrollment(enrollPending.rawToken).secretBase32
        mfa.enroll(enrollPending.rawToken, totp.currentCode(secret))

        val pending = mfa.startPending(accounts.byId(adminId)!!)
        val correct = totp.currentCode(secret)
        val wrong = if (correct == "000000") "111111" else "000000"

        repeat(5) {
            assertThatThrownBy { mfa.verifyChallenge(pending.rawToken, wrong) }
                .isInstanceOf(ApiException::class.java)
        }
        // Cap reached: even the correct code is now rejected and the pending is retired.
        assertThatThrownBy { mfa.verifyChallenge(pending.rawToken, correct) }
            .isInstanceOf(ApiException::class.java)
        val consumedAt = dsl.select(ADMIN_MFA_PENDING.CONSUMED_AT)
            .from(ADMIN_MFA_PENDING)
            .where(ADMIN_MFA_PENDING.TOKEN_HASH.eq(hasher.hash(pending.rawToken)))
            .fetchOne()!!.value1()
        assertThat(consumedAt).isNotNull()
    }
}
