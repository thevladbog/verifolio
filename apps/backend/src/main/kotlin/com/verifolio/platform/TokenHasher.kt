package com.verifolio.platform

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Keyed HMAC-SHA256; raw tokens are never stored (docs/SECURITY.md). */
class TokenHasher(pepper: String) {
    private val keySpec = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
