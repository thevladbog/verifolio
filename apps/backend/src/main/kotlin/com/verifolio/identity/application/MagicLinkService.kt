package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.notifications.MailPort
import com.verifolio.platform.VerifolioProperties
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

@Service
class MagicLinkService(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val mail: MailPort,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun requestMagicLink(rawEmail: String, ipHash: String?, userAgentHash: String?) {
        val email = rawEmail.trim().lowercase()

        if (!EMAIL_REGEX.matches(email)) {
            audit.record(
                actorType = "USER", actorId = null, action = "MAGIC_LINK_REQUESTED",
                entityType = "MAGIC_LINK_TOKEN",
                metadata = mapOf("region" to props.region, "outcome" to "invalid_email"),
                ipHash = ipHash, userAgentHash = userAgentHash,
            )
            return
        }

        val now = OffsetDateTime.now()

        // Reissue invalidates all previous unconsumed tokens (docs/AUTHENTICATION.md).
        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.INVALIDATED_AT, now)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq(email))
            .and(MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .execute()

        val rawToken = TokenGenerator.generate()
        dsl.insertInto(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.EMAIL, email)
            .set(MAGIC_LINK_TOKEN.TOKEN_HASH, hasher.hash(rawToken))
            .set(MAGIC_LINK_TOKEN.EXPIRES_AT, now.plus(props.auth.magicLinkTtl))
            .execute()

        try {
            mail.send(
                to = email,
                subject = "Your Verifolio sign-in link",
                textBody = "Sign in to Verifolio: ${props.auth.frontendBaseUrl}/auth/callback?token=$rawToken\n" +
                    "The link is valid for ${props.auth.magicLinkTtl.toMinutes()} minutes and can be used once.",
            )
        } catch (ex: Exception) {
            // 202 must be returned regardless (anti-enumeration); the stored token stays claimable via re-request.
            log.error("Failed to send magic-link email (address withheld from logs)", ex)
        }
        audit.record(
            actorType = "USER", actorId = null, action = "MAGIC_LINK_REQUESTED",
            entityType = "MAGIC_LINK_TOKEN", metadata = mapOf("region" to props.region, "outcome" to "sent"),
            ipHash = ipHash, userAgentHash = userAgentHash,
        )
    }
}
