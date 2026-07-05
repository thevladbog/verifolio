package com.verifolio.templates

import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

class ConsentTextIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate

    // All requests are anonymous (no cookies): the endpoint is public policy content.

    @Test
    fun `returns the english processing consent text by default`() {
        val response = rest.getForEntity(
            "/api/v1/consent-texts/RECOMMENDER_PROCESSING_CONSENT",
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["consentType"]).isEqualTo("RECOMMENDER_PROCESSING_CONSENT")
        assertThat(body["textId"]).isEqualTo("local-processing")
        assertThat(body["version"]).isEqualTo(1)
        assertThat(body["locale"]).isEqualTo("en")
        assertThat(body["title"]).isEqualTo("Before you start: data processing consent")
        assertThat(body["body"] as String).contains("Verifolio will process your name, email")
    }

    @Test
    fun `returns the russian text when locale=ru`() {
        val response = rest.getForEntity(
            "/api/v1/consent-texts/RECOMMENDER_PROCESSING_CONSENT?locale=ru",
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["locale"]).isEqualTo("ru")
        assertThat(body["title"]).isEqualTo("Перед началом: согласие на обработку данных")
        assertThat(body["body"] as String).contains("Verifolio обработает ваше имя")
    }

    @Test
    fun `unknown locale falls back to english without an error`() {
        val response = rest.getForEntity(
            "/api/v1/consent-texts/CROSS_BORDER_TRANSFER_CONSENT?locale=de",
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["locale"]).isEqualTo("en")
        assertThat(body["body"] as String)
            .contains("I consent to the cross-border transfer of my data")
    }

    @Test
    fun `all four consent types resolve in english`() {
        val expectedTextIds = mapOf(
            "REQUESTER_VERBAL_CONSENT_ATTESTATION" to "local-requester-attestation",
            "RECOMMENDER_PROCESSING_CONSENT" to "local-processing",
            "CROSS_BORDER_TRANSFER_CONSENT" to "local-cross-border",
            "RECOMMENDER_PUBLIC_SHARING_CONSENT" to "local-public-sharing",
        )

        expectedTextIds.forEach { (type, textId) ->
            val response = rest.getForEntity("/api/v1/consent-texts/$type", Map::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            assertThat(body["textId"]).isEqualTo(textId)
            assertThat(body["version"]).isEqualTo(1)
            assertThat(body["title"] as String).isNotBlank()
            assertThat(body["body"] as String).isNotBlank()
        }
    }

    @Test
    fun `unknown consent type returns 404 NOT_FOUND`() {
        val response = rest.getForEntity(
            "/api/v1/consent-texts/SOMETHING_ELSE",
            Map::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["code"]).isEqualTo("NOT_FOUND")
    }
}
