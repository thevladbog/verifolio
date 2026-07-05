package com.verifolio.identity.application

import com.verifolio.identity.RecommenderActor
import com.verifolio.identity.RecommenderSessions
import com.verifolio.platform.TokenHasher
import com.verifolio.jooq.tables.references.RECOMMENDER_SESSION
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
internal class RecommenderSessionsImpl(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
) : RecommenderSessions {

    @Transactional(readOnly = true)
    override fun resolve(rawSessionToken: String): RecommenderActor? {
        val rs = RECOMMENDER_SESSION
        return dsl.selectFrom(rs)
            .where(rs.TOKEN_HASH.eq(hasher.hash(rawSessionToken)))
            .fetchOne()
            ?.takeIf { it.revokedAt == null && it.expiresAt!!.isAfter(OffsetDateTime.now()) }
            ?.let { RecommenderActor(it.requestId!!, it.recommenderEmail!!) }
    }

    @Transactional
    override fun revokeForRequest(requestId: UUID): Int {
        val rs = RECOMMENDER_SESSION
        return dsl.update(rs)
            .set(rs.REVOKED_AT, OffsetDateTime.now())
            .where(rs.REQUEST_ID.eq(requestId).and(rs.REVOKED_AT.isNull))
            .execute()
    }
}
