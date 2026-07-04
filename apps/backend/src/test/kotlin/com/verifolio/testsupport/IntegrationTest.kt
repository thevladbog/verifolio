package com.verifolio.testsupport

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

// In Spring Boot 4, TestRestTemplate is no longer auto-registered for RANDOM_PORT tests;
// @AutoConfigureTestRestTemplate is required to activate TestRestTemplateTestAutoConfiguration.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
abstract class IntegrationTest {
    companion object {
        // Single shared container for the whole test JVM.
        private val postgres = PostgreSQLContainer("postgres:17-alpine").also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
