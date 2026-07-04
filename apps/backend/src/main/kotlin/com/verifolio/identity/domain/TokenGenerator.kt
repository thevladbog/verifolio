package com.verifolio.identity.domain

import java.security.SecureRandom
import java.util.Base64

object TokenGenerator {
    private val random = SecureRandom()
    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
