package com.verifolio.files.infrastructure

import com.verifolio.platform.VerifolioProperties
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration

/** The only class talking to S3/MinIO. Path-style addressing for MinIO compatibility. */
@Component
internal class S3StorageAdapter(private val props: VerifolioProperties) {

    private val credentials = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(props.storage.accessKey, props.storage.secretKey),
    )

    private val client: S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.storage.endpoint))
        .region(Region.of(props.storage.regionName))
        .credentialsProvider(credentials)
        .forcePathStyle(props.storage.pathStyle)
        .build()

    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(props.storage.endpoint))
        .region(Region.of(props.storage.regionName))
        .credentialsProvider(credentials)
        .serviceConfiguration(
            software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(props.storage.pathStyle)
                .build(),
        )
        .build()

    @PostConstruct
    fun ensureBucket() {
        // Auto-create only makes sense for local/dev MinIO; production buckets are provisioned.
        if (!props.storage.pathStyle) return
        try {
            client.createBucket { it.bucket(props.storage.bucket) }
        } catch (_: BucketAlreadyOwnedByYouException) {
            // fine
        }
    }

    fun put(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(
            PutObjectRequest.builder()
                .bucket(props.storage.bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }

    fun presignGet(key: String, downloadFilename: String, ttl: Duration): String {
        val getRequest = GetObjectRequest.builder()
            .bucket(props.storage.bucket)
            .key(key)
            .responseContentDisposition("attachment; filename=\"$downloadFilename\"")
            .build()
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getRequest)
                .build(),
        )
        return presigned.url().toString()
    }
}
