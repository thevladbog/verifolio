package com.verifolio.files.domain

/**
 * Magic-byte content sniffing (docs/FILES_AND_STORAGE.md validation): the uploaded bytes
 * must match the declared MIME type. `application/octet-stream` is accepted as opaque
 * (used only for detached signature containers, which make no verification claims).
 */
object MimeSniffer {

    fun matches(bytes: ByteArray, declaredMime: String): Boolean = when (declaredMime) {
        "application/pdf" -> bytes.startsWith("%PDF-".toByteArray(Charsets.US_ASCII))
        "image/jpeg" -> bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        "image/png" -> bytes.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        // DER-encoded CMS container starts with a SEQUENCE tag.
        "application/pkcs7-signature" -> bytes.isNotEmpty() && bytes[0] == 0x30.toByte()
        "application/octet-stream" -> true
        else -> false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
