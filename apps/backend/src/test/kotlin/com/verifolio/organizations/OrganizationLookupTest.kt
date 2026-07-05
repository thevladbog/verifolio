package com.verifolio.organizations

import com.verifolio.jooq.tables.references.ORGANIZATION
import com.verifolio.jooq.tables.references.ORGANIZATION_DOMAIN
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class OrganizationLookupTest : IntegrationTest() {

    @Autowired lateinit var lookup: OrganizationLookup
    @Autowired lateinit var dsl: DSLContext

    private val sapId = UUID.fromString("a0000000-0000-4000-8000-000000000001")

    @Test
    fun `exact domain match resolves the verified org`() {
        val match = lookup.findVerifiedByDomain("sap.com")
        assertThat(match).isNotNull()
        assertThat(match!!.organizationId).isEqualTo(sapId)
        assertThat(match.name).isEqualTo("SAP SE")
        assertThat(match.matchedDomain).isEqualTo("sap.com")
    }

    @Test
    fun `subdomain match resolves the verified org`() {
        val match = lookup.findVerifiedByDomain("careers.sap.com")
        assertThat(match).isNotNull()
        assertThat(match!!.organizationId).isEqualTo(sapId)
        assertThat(match.matchedDomain).isEqualTo("sap.com")
    }

    @Test
    fun `case and whitespace are normalized`() {
        val match = lookup.findVerifiedByDomain("  SAP.COM ")
        assertThat(match).isNotNull()
        assertThat(match!!.organizationId).isEqualTo(sapId)
    }

    @Test
    fun `second domain of a two-domain org resolves that org`() {
        val match = lookup.findVerifiedByDomain("successfactors.com")
        assertThat(match).isNotNull()
        assertThat(match!!.organizationId).isEqualTo(sapId)
        assertThat(match.matchedDomain).isEqualTo("successfactors.com")
    }

    @Test
    fun `longest domain wins when a subdomain is registered to a different org`() {
        val rootOrg = insertOrg("Acme Corp", "acme.com", "VERIFIED")
        val euOrg = insertOrg("Acme EU", "eu.acme.com", "VERIFIED")

        val match = lookup.findVerifiedByDomain("careers.eu.acme.com")
        assertThat(match).isNotNull()
        assertThat(match!!.organizationId).isEqualTo(euOrg)
        assertThat(match.matchedDomain).isEqualTo("eu.acme.com")

        // A domain only matching the shorter registration still resolves the root org.
        val rootMatch = lookup.findVerifiedByDomain("www.acme.com")
        assertThat(rootMatch!!.organizationId).isEqualTo(rootOrg)
        assertThat(rootMatch.matchedDomain).isEqualTo("acme.com")
    }

    @Test
    fun `unverified org is not matched`() {
        insertOrg("Shady Ltd", "shady.example", "UNVERIFIED")
        assertThat(lookup.findVerifiedByDomain("shady.example")).isNull()
        assertThat(lookup.findVerifiedByDomain("mail.shady.example")).isNull()
    }

    @Test
    fun `unknown domain returns null`() {
        assertThat(lookup.findVerifiedByDomain("gmail.com")).isNull()
        assertThat(lookup.findVerifiedByDomain("")).isNull()
    }

    private fun insertOrg(name: String, domain: String, status: String): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(ORGANIZATION)
            .set(ORGANIZATION.ID, id)
            .set(ORGANIZATION.NAME, name)
            .set(ORGANIZATION.DOMAINS, JSONB.valueOf("[\"$domain\"]"))
            .set(ORGANIZATION.VERIFICATION_STATUS, status)
            .execute()
        dsl.insertInto(ORGANIZATION_DOMAIN)
            .set(ORGANIZATION_DOMAIN.ORGANIZATION_ID, id)
            .set(ORGANIZATION_DOMAIN.DOMAIN, domain)
            .execute()
        return id
    }
}
