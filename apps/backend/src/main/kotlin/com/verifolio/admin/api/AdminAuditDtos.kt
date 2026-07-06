package com.verifolio.admin.api

import com.verifolio.audit.AuditLogRow

/**
 * One audit-log row for the viewer. Metadata is IDs/counts-only by construction (returned as-is);
 * `ip_hash`/`user_agent_hash` are never present.
 */
data class AdminAuditLogResponse(
    val id: String,
    val createdAt: String,
    val actorType: String,
    val actorId: String?,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val metadata: Map<String, Any?>,
) {
    companion object {
        fun from(r: AuditLogRow) = AdminAuditLogResponse(
            id = r.id.toString(),
            createdAt = r.createdAt.toString(),
            actorType = r.actorType,
            actorId = r.actorId,
            action = r.action,
            entityType = r.entityType,
            entityId = r.entityId,
            metadata = r.metadata,
        )
    }
}

/** A keyset page of audit-log rows. */
data class AdminAuditLogListResponse(
    val items: List<AdminAuditLogResponse>,
    val nextCursor: String?,
)
