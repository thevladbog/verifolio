package com.verifolio.audit

interface AuditService {
    fun record(
        actorType: String,
        actorId: String?,
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        ipHash: String? = null,
        userAgentHash: String? = null,
    )
}
