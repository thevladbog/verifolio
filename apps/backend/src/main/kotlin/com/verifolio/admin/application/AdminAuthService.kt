package com.verifolio.admin.application

import com.verifolio.admin.domain.AdminAccount
import com.verifolio.audit.AuditService
import com.verifolio.notifications.MailPort
import com.verifolio.platform.ApiException
import com.verifolio.platform.SlidingWindowRateLimiter
import com.verifolio.platform.VerifolioProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Use-case orchestration for admin auth (spec §Flow), keeping [com.verifolio.admin.api.AdminAuthController]
 * thin (MODULES.md — controllers hold only HTTP concerns). Owns the magic-link request (rate limit +
 * anti-enumeration + mail), session completion (mint + audit), and logout (revoke + audit). Audit is
 * best-effort infra (AuditService uses REQUIRES_NEW): a login/logout is never failed by an audit hiccup —
 * the session mutation has already committed, so an audit-store error is logged and swallowed. Cookie
 * assembly stays in the controller (an HTTP concern).
 */
@Service
internal class AdminAuthService(
    private val magicLinks: AdminMagicLinks,
    private val accounts: AdminAccounts,
    private val sessions: AdminSessions,
    private val audit: AuditService,
    private val mail: MailPort,
    private val props: VerifolioProperties,
    @Qualifier("adminMagicLinkEmailLimiter") private val emailLimiter: SlidingWindowRateLimiter,
    @Qualifier("adminMagicLinkIpLimiter") private val ipLimiter: SlidingWindowRateLimiter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Rate-limits (per email + per ip hash), then — only for an ACTIVE admin — mints a magic link and
     * emails it. Anti-enumeration: the caller returns an identical 202 whether or not an admin exists;
     * this method signals only rate-limit exhaustion (429) to the caller.
     */
    fun requestMagicLink(email: String, ipHash: String?) {
        val emailAllowed = emailLimiter.tryAcquire(email)
        val ipAllowed = ipHash == null || ipLimiter.tryAcquire(ipHash)
        if (!emailAllowed || !ipAllowed) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests, try again later")
        }

        if (accounts.activeByEmail(email) != null) {
            val rawToken = magicLinks.mint(email)
            try {
                sendMail(email, rawToken)
            } catch (ex: Exception) {
                log.error("Failed to send admin magic-link email (address withheld from logs)", ex)
            }
        }
    }

    /**
     * Mints the admin session for an MFA-verified [account] and best-effort audits login + session
     * creation. Returns the created session so the controller can set the cookie. An audit failure never
     * fails the login: the session is already committed.
     */
    fun completeSession(account: AdminAccount, ipHash: String?, userAgentHash: String?): CreatedAdminSession {
        val session = sessions.mint(account.id, ipHash, userAgentHash)
        bestEffortAudit {
            audit.record(
                actorType = "ADMIN", actorId = account.id.toString(),
                action = "ADMIN_LOGIN_SUCCEEDED", entityType = "ADMIN_ACCOUNT",
                metadata = mapOf("region" to props.region, "adminId" to account.id.toString()),
                ipHash = ipHash, userAgentHash = userAgentHash,
            )
            audit.record(
                actorType = "ADMIN", actorId = account.id.toString(),
                action = "ADMIN_SESSION_CREATED", entityType = "ADMIN_SESSION",
                metadata = mapOf("region" to props.region, "adminId" to account.id.toString()),
                ipHash = ipHash, userAgentHash = userAgentHash,
            )
        }
        return session
    }

    /**
     * Revokes the admin session for [rawToken] (idempotent) and best-effort audits the revocation. An
     * audit failure never fails the logout: the revocation is already committed.
     */
    fun logout(rawToken: String?, adminId: UUID, ipHash: String?, userAgentHash: String?) {
        if (rawToken != null) sessions.revoke(rawToken)
        bestEffortAudit {
            audit.record(
                actorType = "ADMIN", actorId = adminId.toString(),
                action = "ADMIN_SESSION_REVOKED", entityType = "ADMIN_SESSION",
                metadata = mapOf("region" to props.region, "adminId" to adminId.toString()),
                ipHash = ipHash, userAgentHash = userAgentHash,
            )
        }
    }

    /** Runs [record] but never lets an audit-store failure propagate (audit is best-effort infra). */
    private inline fun bestEffortAudit(record: () -> Unit) {
        try {
            record()
        } catch (ex: Exception) {
            log.warn("Best-effort admin audit failed after a committed session mutation; continuing", ex)
        }
    }

    private fun sendMail(email: String, rawToken: String) {
        mail.send(
            to = email,
            subject = "Your Verifolio admin sign-in link",
            textBody = "Sign in to the Verifolio admin console: " +
                "${props.auth.frontendBaseUrl}/admin/auth/callback?token=$rawToken\n" +
                "The link is valid for 15 minutes and can be used once.",
        )
    }
}
