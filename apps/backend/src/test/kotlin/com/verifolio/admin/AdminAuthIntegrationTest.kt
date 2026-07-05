package com.verifolio.admin

import com.verifolio.admin.application.AdminTotpCipher
import com.verifolio.admin.application.AdminTotpService
import com.verifolio.jooq.tables.references.ADMIN_ACCOUNT
import com.verifolio.jooq.tables.references.ADMIN_MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.platform.VerifolioProperties
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * Task 3 admin auth integration: full ENROLL, CHALLENGE + attempt cap, anti-enumeration, cross-chain
 * isolation (user↔admin), and CSRF on an admin mutation. The ENROLL test uses the config-bootstrapped
 * SUPERADMIN (spec §Bootstrap); other tests insert their own fresh admin accounts so their required
 * MFA state is deterministic regardless of test ordering (single shared context/DB).
 */
@Import(RecordingMailConfig::class)
class AdminAuthIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext
    @Autowired lateinit var totp: AdminTotpService
    @Autowired lateinit var cipher: AdminTotpCipher
    @Autowired lateinit var props: VerifolioProperties

    @BeforeEach
    fun resetMail() {
        mail.sent.clear()
        mail.failFor = null
    }

    companion object {
        private const val BOOTSTRAP_EMAIL = "root-admin@verifolio.local"

        @JvmStatic
        @DynamicPropertySource
        fun adminProps(registry: DynamicPropertyRegistry) {
            registry.add("verifolio.admin.bootstrap-emails") { BOOTSTRAP_EMAIL }
        }
    }

    // --- HTTP helpers --------------------------------------------------------

    private fun requestLink(email: String) =
        rest.postForEntity("/api/v1/admin/auth/magic-links", mapOf("email" to email), Map::class.java)

    private fun capturedToken(email: String): String =
        Regex("token=([A-Za-z0-9_-]+)").find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]

    private fun cookieValue(headers: HttpHeaders, name: String): String? =
        headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("$name=") }?.substringBefore(";")

    private fun post(path: String, body: Any?, cookie: String?) =
        rest.exchange(
            path, HttpMethod.POST,
            HttpEntity(body, HttpHeaders().apply { cookie?.let { add(HttpHeaders.COOKIE, it) } }),
            Map::class.java,
        )

    private fun get(path: String, cookie: String?) =
        rest.exchange(
            path, HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { cookie?.let { add(HttpHeaders.COOKIE, it) } }),
            Map::class.java,
        )

    /** magic-link → consume → (pendingCookie, state). */
    private fun consumeFor(email: String): Pair<String, String> {
        requestLink(email)
        val resp = post("/api/v1/admin/auth/magic-links/consume", mapOf("token" to capturedToken(email)), null)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return cookieValue(resp.headers, "verifolio_admin_pending")!! to resp.body!!["state"] as String
    }

    /** Inserts a fresh un-enrolled ACTIVE SUPERADMIN and returns its email. */
    private fun freshAdmin(): String {
        val email = "admin-${UUID.randomUUID()}@verifolio.local"
        val userId = dsl.insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.EMAIL, email).set(USER_ACCOUNT.REGION, props.region).set(USER_ACCOUNT.STATUS, "ACTIVE")
            .returning(USER_ACCOUNT.ID).fetchOne()!!.id!!
        dsl.insertInto(ADMIN_ACCOUNT)
            .set(ADMIN_ACCOUNT.USER_ACCOUNT_ID, userId)
            .set(ADMIN_ACCOUNT.EMAIL, email)
            .set(ADMIN_ACCOUNT.REGION, props.region)
            .set(ADMIN_ACCOUNT.ROLE, "SUPERADMIN")
            .set(ADMIN_ACCOUNT.STATUS, "ACTIVE")
            .execute()
        return email
    }

    /** Full first-login (ENROLL) → returns (sessionCookie, secretBase32). */
    private fun enroll(email: String): Pair<String, String> {
        val (pending, state) = consumeFor(email)
        assertThat(state).isEqualTo("ENROLL")
        val secret = get("/api/v1/admin/auth/mfa/enrollment", pending).body!!["secretBase32"] as String
        val resp = post("/api/v1/admin/auth/mfa/enroll", mapOf("code" to totp.currentCode(secret)), pending)
        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        return cookieValue(resp.headers, "verifolio_admin_session")!! to secret
    }

    // --- tests ---------------------------------------------------------------

    @Test
    fun `full ENROLL flow mints an admin session and audits, logout revokes`() {
        val (pending, state) = consumeFor(BOOTSTRAP_EMAIL)
        assertThat(state).isEqualTo("ENROLL")

        val enrollment = get("/api/v1/admin/auth/mfa/enrollment", pending)
        assertThat(enrollment.statusCode).isEqualTo(HttpStatus.OK)
        val secret = enrollment.body!!["secretBase32"] as String

        val enrollResp = post("/api/v1/admin/auth/mfa/enroll", mapOf("code" to totp.currentCode(secret)), pending)
        assertThat(enrollResp.statusCode).isEqualTo(HttpStatus.OK)
        val session = cookieValue(enrollResp.headers, "verifolio_admin_session")!!
        assertThat(session).isNotBlank()
        // pending cookie cleared on the same response (Max-Age=0).
        assertThat(enrollResp.headers[HttpHeaders.SET_COOKIE]!!
            .any { it.startsWith("verifolio_admin_pending=") && it.contains("Max-Age=0") }).isTrue()

        assertThat(dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION))
            .contains("ADMIN_SESSION_CREATED", "ADMIN_LOGIN_SUCCEEDED")

        val me = get("/api/v1/admin/me", session)
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(me.body!!["role"]).isEqualTo("SUPERADMIN")
        assertThat(me.body!!["email"]).isEqualTo(BOOTSTRAP_EMAIL)

        // logout requires CSRF: obtain the XSRF token from the authenticated GET response.
        val xsrf = get("/api/v1/admin/me", session).headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")
        val logout = rest.exchange(
            "/api/v1/admin/auth/logout", HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply {
                add(HttpHeaders.COOKIE, session)
                xsrf?.let { add(HttpHeaders.COOKIE, "XSRF-TOKEN=$it"); add("X-XSRF-TOKEN", it) }
            }),
            Void::class.java,
        )
        assertThat(logout.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION))
            .contains("ADMIN_SESSION_REVOKED")

        // revoked: subsequent /me is unauthorized.
        assertThat(get("/api/v1/admin/me", session).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `CHALLENGE flow verifies with a correct code, then caps after wrong codes`() {
        val email = freshAdmin()
        val (_, secret) = enroll(email)

        // Second login → CHALLENGE, correct code mints a session.
        val (pending, state) = consumeFor(email)
        assertThat(state).isEqualTo("CHALLENGE")
        val ok = post("/api/v1/admin/auth/mfa/verify", mapOf("code" to totp.currentCode(secret)), pending)
        assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)

        // Third login → exhaust the attempt cap with wrong codes; then even a correct code fails.
        val (capPending, _) = consumeFor(email)
        val wrong = if (totp.currentCode(secret) == "000000") "000001" else "000000"
        repeat(6) {
            val r = post("/api/v1/admin/auth/mfa/verify", mapOf("code" to wrong), capPending)
            assertThat(r.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(r.body!!["code"]).isEqualTo("CODE_INVALID")
        }
        val afterCap = post("/api/v1/admin/auth/mfa/verify", mapOf("code" to totp.currentCode(secret)), capPending)
        assertThat(afterCap.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `magic-link for an unknown email returns 202 with no token and no mail`() {
        val unknown = "nobody-here@verifolio.local"
        assertThat(requestLink(unknown).statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(mail.sent.none { it.to == unknown }).isTrue()
        assertThat(dsl.fetchCount(dsl.selectFrom(ADMIN_MAGIC_LINK_TOKEN).where(ADMIN_MAGIC_LINK_TOKEN.EMAIL.eq(unknown))))
            .isEqualTo(0)
    }

    @Test
    fun `a user session does not authenticate an admin endpoint`() {
        val userCookie = mintUserSession("regular-user@example.com")
        assertThat(get("/api/v1/admin/me", userCookie).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `an admin session does not authenticate a user endpoint`() {
        val (adminSession, _) = enroll(freshAdmin())
        // A real user endpoint on the identity chain must reject the admin cookie.
        assertThat(get("/api/v1/auth/sessions/current", adminSession).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `admin logout without CSRF token is rejected`() {
        val (adminSession, _) = enroll(freshAdmin())
        val resp = rest.exchange(
            "/api/v1/admin/auth/logout", HttpMethod.POST,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, adminSession) }),
            Map::class.java,
        )
        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    private fun mintUserSession(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)").find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val resp = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return resp.headers[HttpHeaders.SET_COOKIE]!!.first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }
}
