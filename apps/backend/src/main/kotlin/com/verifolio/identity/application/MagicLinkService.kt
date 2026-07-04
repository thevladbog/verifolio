package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.identity.infrastructure.IdentityProperties
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.notifications.MailPort
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class MagicLinkService(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val mail: MailPort,
    private val audit: AuditService,
    private val props: IdentityProperties,
) {

    @Transactional
    fun requestMagicLink(rawEmail: String, ipHash: String?, userAgentHash: String?) {
        val email = rawEmail.trim().lowercase()
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

        mail.send(
            to = email,
            subject = "Your Verifolio sign-in link",
            textBody = "Sign in to Verifolio: ${props.auth.frontendBaseUrl}/auth/callback?token=$rawToken\n" +
                "The link is valid for ${props.auth.magicLinkTtl.toMinutes()} minutes and can be used once.",
        )
        audit.record(
            actorType = "USER", actorId = null, action = "MAGIC_LINK_REQUESTED",
            entityType = "MAGIC_LINK_TOKEN", metadata = mapOf("region" to props.region),
            ipHash = ipHash, userAgentHash = userAgentHash,
        )
    }
}
