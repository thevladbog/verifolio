package com.verifolio.documents.domain

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/**
 * Canonical JSON for DocumentVersion.sha256_hash: recursively sorted object keys,
 * no insignificant whitespace. The hash is stable regardless of producer key order
 * (docs/DATA_MODEL.md hash semantics).
 */
object CanonicalJson {
    private val mapper = ObjectMapper()

    fun canonicalize(json: String): String = mapper.writeValueAsString(sort(mapper.readTree(json)))

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sort(node: JsonNode): JsonNode = when (node) {
        is ObjectNode -> {
            val sorted = mapper.createObjectNode()
            node.propertyNames().sorted().forEach { name -> sorted.set(name, sort(node.get(name))) }
            sorted
        }
        is ArrayNode -> {
            val arr = mapper.createArrayNode()
            node.forEach { arr.add(sort(it)) }
            arr
        }
        else -> node
    }
}
