package com.verifolio.testsupport

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer

// In Spring Boot 4, TestRestTemplate is no longer auto-registered for RANDOM_PORT tests;
// @AutoConfigureTestRestTemplate is required to activate TestRestTemplateTestAutoConfiguration.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {
    companion object {
        // Single shared containers for the whole test JVM.
        private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }
        private val minio = MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z").also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("verifolio.storage.endpoint", minio::getS3URL)
            registry.add("verifolio.storage.access-key", minio::getUserName)
            registry.add("verifolio.storage.secret-key", minio::getPassword)
            // The whole suite shares one context and one client IP; production default is 100.
            registry.add("verifolio.auth.magic-link-ip-limit") { "100000" }
            registry.add("verifolio.privacy.recommender-ip-limit") { "100000" }
            // Tests invoke RecurringTask.run() directly — the background scheduler stays off.
            registry.add("verifolio.workflows.enabled") { "false" }
        }
    }
}
