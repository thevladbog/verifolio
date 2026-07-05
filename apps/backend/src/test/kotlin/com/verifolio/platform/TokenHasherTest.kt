package com.verifolio.platform

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenHasherTest {

    @Test
    fun `same input and pepper produce the same hash`() {
        val hasher = TokenHasher("pepper-1")
        assertThat(hasher.hash("token")).isEqualTo(hasher.hash("token"))
    }

    @Test
    fun `different pepper produces a different hash (keyed HMAC, not plain digest)`() {
        assertThat(TokenHasher("pepper-1").hash("token"))
            .isNotEqualTo(TokenHasher("pepper-2").hash("token"))
    }

    @Test
    fun `hash is lowercase hex and does not contain the input`() {
        val hash = TokenHasher("pepper").hash("secret-token")
        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(hash).doesNotContain("secret-token")
    }

    @Test
    fun `generated tokens are url-safe and unique`() {
        val tokens = (1..100).map { TokenGenerator.generate() }.toSet()
        assertThat(tokens).hasSize(100)
        tokens.forEach { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
    }
}
