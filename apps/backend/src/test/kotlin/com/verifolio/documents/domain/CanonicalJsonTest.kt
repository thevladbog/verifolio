package com.verifolio.documents.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CanonicalJsonTest {

    @Test
    fun `key order does not change the canonical form or hash`() {
        val a = """{"b":1,"a":{"d":2,"c":3},"list":[1,2,{"z":1,"y":2}]}"""
        val b = """{"a":{"c":3,"d":2},"list":[1,2,{"y":2,"z":1}],"b":1}"""

        assertThat(CanonicalJson.canonicalize(a)).isEqualTo(CanonicalJson.canonicalize(b))
        assertThat(CanonicalJson.sha256Hex(CanonicalJson.canonicalize(a)))
            .isEqualTo(CanonicalJson.sha256Hex(CanonicalJson.canonicalize(b)))
    }

    @Test
    fun `different content produces a different hash`() {
        val a = CanonicalJson.canonicalize("""{"letter":"text one"}""")
        val b = CanonicalJson.canonicalize("""{"letter":"text two"}""")
        assertThat(CanonicalJson.sha256Hex(a)).isNotEqualTo(CanonicalJson.sha256Hex(b))
    }

    @Test
    fun `canonical form is stable across repeated calls`() {
        val json = """{"z":"1","a":"2"}"""
        assertThat(CanonicalJson.canonicalize(json)).isEqualTo(CanonicalJson.canonicalize(json))
        assertThat(CanonicalJson.canonicalize(json)).isEqualTo("""{"a":"2","z":"1"}""")
    }
}
