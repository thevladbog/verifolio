package com.verifolio.organizations.application

import com.verifolio.jooq.tables.references.ORGANIZATION
import com.verifolio.jooq.tables.references.ORGANIZATION_DOMAIN
import com.verifolio.organizations.OrganizationLookup
import com.verifolio.organizations.OrganizationMatch
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class OrganizationLookupImpl(private val dsl: DSLContext) : OrganizationLookup {

    @Transactional(readOnly = true)
    override fun findVerifiedByDomain(emailDomain: String): OrganizationMatch? {
        val normalized = emailDomain.trim().lowercase()
        if (normalized.isEmpty()) return null

        val od = ORGANIZATION_DOMAIN
        val o = ORGANIZATION

        // Coarse SQL prefilter: exact match or the email domain being a subdomain of a registered
        // domain. The final suffix check is redone in Kotlin (endsWith ".$domain") to stay safe
        // regardless of any LIKE metacharacters, and to pick the longest (most specific) match.
        val candidates = dsl.select(od.DOMAIN, o.ID, o.NAME)
            .from(od)
            .join(o).on(od.ORGANIZATION_ID.eq(o.ID))
            .where(o.VERIFICATION_STATUS.eq("VERIFIED"))
            .and(
                DSL.lower(od.DOMAIN).eq(normalized)
                    .or(DSL.value(normalized).like(DSL.concat(DSL.value("%."), DSL.lower(od.DOMAIN)))),
            )
            .fetch()

        return candidates
            .mapNotNull { rec ->
                val domain = rec[od.DOMAIN]!!.trim().lowercase()
                if (normalized == domain || normalized.endsWith(".$domain")) {
                    OrganizationMatch(rec[o.ID]!!, rec[o.NAME]!!, domain)
                } else {
                    null
                }
            }
            .maxByOrNull { it.matchedDomain.length }
    }
}
