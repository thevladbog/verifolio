package com.verifolio.files.application

import com.verifolio.audit.AuditService
import com.verifolio.files.FileUploads
import com.verifolio.files.RequestedUpload
import com.verifolio.files.UploadOutcome
import com.verifolio.files.domain.MimeSniffer
import com.verifolio.files.infrastructure.S3StorageAdapter
import com.verifolio.jooq.tables.references.FILE_OBJECT
import com.verifolio.platform.ApiException
import com.verifolio.platform.VerifolioProperties
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

private val ALLOWED_MIME_BY_PURPOSE: Map<String, Set<String>> = mapOf(
    "SCAN" to setOf("application/pdf", "image/jpeg", "image/png"),
    "ATTACHMENT" to setOf("application/pdf", "image/jpeg", "image/png"),
    "DETACHED_SIGNATURE" to setOf("application/pkcs7-signature", "application/octet-stream"),
)

@Service
internal class FileUploadsImpl(
    private val dsl: DSLContext,
    private val storage: S3StorageAdapter,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) : FileUploads {

    @Transactional
    override fun requestUpload(
        purpose: String,
        filename: String,
        declaredMime: String,
        declaredSizeBytes: Long,
        actorId: String?,
    ): RequestedUpload {
        val allowed = ALLOWED_MIME_BY_PURPOSE[purpose]
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Unsupported upload purpose")
        if (declaredMime !in allowed) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "MIME type not allowed for this purpose")
        }
        if (declaredSizeBytes <= 0 || declaredSizeBytes > props.storage.maxUploadBytes) {
            throw ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Upload size must be between 1 byte and ${props.storage.maxUploadBytes} bytes",
            )
        }

        val fileId = UUID.randomUUID()
        // Opaque key — IDs only, never user-provided filenames (docs/FILES_AND_STORAGE.md).
        val key = "${props.region}/uploads/$fileId"
        val ttl = props.storage.uploadUrlTtl
        val url = storage.presignPut(key, declaredMime, declaredSizeBytes, ttl)

        val fo = FILE_OBJECT
        dsl.insertInto(fo)
            .set(fo.ID, fileId)
            .set(fo.BUCKET, props.storage.bucket)
            .set(fo.STORAGE_KEY, key)
            .set(fo.ORIGINAL_FILENAME, filename)
            .set(fo.MIME_TYPE, declaredMime)
            .set(fo.SIZE_BYTES, declaredSizeBytes)
            .set(fo.SHA256_HASH, "pending")
            .set(fo.PURPOSE, purpose)
            .set(fo.STATUS, "PENDING")
            .set(fo.UPLOADED_BY_ACTOR_ID, actorId)
            .execute()

        audit.record(
            actorType = "RECOMMENDER",
            actorId = null,
            action = "FILE_UPLOAD_REQUESTED",
            entityType = "FILE_OBJECT",
            entityId = fileId.toString(),
            metadata = mapOf("purpose" to purpose, "declaredSizeBytes" to declaredSizeBytes.toString()),
        )

        return RequestedUpload(fileId = fileId, uploadUrl = url, expiresAt = OffsetDateTime.now().plus(ttl))
    }

    @Transactional
    override fun confirmUpload(fileId: UUID): UploadOutcome {
        val fo = FILE_OBJECT
        val record = dsl.selectFrom(fo)
            .where(fo.ID.eq(fileId))
            .forUpdate()
            .fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Upload not found")
        if (record.status != "PENDING") {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Upload is not awaiting confirmation")
        }

        val key = record.storageKey!!
        val actualSize = storage.headSize(key)
            ?: return reject(fileId, key, "object was never uploaded", deleteObject = false)
        if (actualSize != record.sizeBytes) {
            return reject(fileId, key, "actual size $actualSize does not match declared ${record.sizeBytes}")
        }

        val bytes = storage.getBytes(key)
        if (!MimeSniffer.matches(bytes, record.mimeType!!)) {
            return reject(fileId, key, "content does not match the declared type ${record.mimeType}")
        }

        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        dsl.update(fo)
            .set(fo.STATUS, "READY")
            .set(fo.SHA256_HASH, sha256)
            .where(fo.ID.eq(fileId))
            .execute()

        audit.record(
            actorType = "RECOMMENDER", actorId = null, action = "FILE_UPLOADED",
            entityType = "FILE_OBJECT", entityId = fileId.toString(),
            metadata = mapOf("purpose" to record.purpose!!, "sizeBytes" to actualSize.toString()),
        )
        audit.record(
            actorType = "SYSTEM", actorId = null, action = "FILE_VALIDATED",
            entityType = "FILE_OBJECT", entityId = fileId.toString(),
            metadata = mapOf("result" to "READY"),
        )
        return UploadOutcome(status = "READY", sha256 = sha256, reason = null)
    }

    @Transactional
    override fun deleteUpload(fileId: UUID) {
        val fo = FILE_OBJECT
        val record = dsl.selectFrom(fo).where(fo.ID.eq(fileId)).forUpdate().fetchOne()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Upload not found")
        if (record.status !in listOf("PENDING", "READY", "REJECTED")) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_REQUEST_STATE", "Upload can no longer be deleted")
        }
        runCatching { storage.delete(record.storageKey!!) }
        dsl.update(fo)
            .set(fo.STATUS, "DELETED")
            .set(fo.DELETED_AT, OffsetDateTime.now())
            .where(fo.ID.eq(fileId))
            .execute()
        audit.record(
            actorType = "RECOMMENDER", actorId = null, action = "FILE_DELETED",
            entityType = "FILE_OBJECT", entityId = fileId.toString(),
            metadata = mapOf("purpose" to record.purpose!!),
        )
    }

    private fun reject(fileId: UUID, key: String, reason: String, deleteObject: Boolean = true): UploadOutcome {
        if (deleteObject) runCatching { storage.delete(key) }
        val fo = FILE_OBJECT
        dsl.update(fo).set(fo.STATUS, "REJECTED").where(fo.ID.eq(fileId)).execute()
        audit.record(
            actorType = "SYSTEM", actorId = null, action = "FILE_VALIDATED",
            entityType = "FILE_OBJECT", entityId = fileId.toString(),
            metadata = mapOf("result" to "REJECTED", "reason" to reason),
        )
        return UploadOutcome(status = "REJECTED", sha256 = null, reason = reason)
    }
}
