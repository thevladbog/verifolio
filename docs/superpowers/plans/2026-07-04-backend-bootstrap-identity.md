# Backend Bootstrap + Identity Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bootstrap `apps/backend` (Kotlin Spring Boot modular monolith) and ship the first vertical slice: magic-link authentication with sessions, audit events, OpenAPI 3.1 + Scalar, tests, and CI.

**Architecture:** Single Gradle module; domain modules are packages `com.verifolio.<module>` verified by Spring Modulith. PostgreSQL via Flyway migrations + jOOQ (codegen from migrations using Testcontainers during build). Identity slice implements `docs/AUTHENTICATION.md` rules; audit and notifications get minimal cores.

**Tech Stack:** Java 21, Kotlin 2.1.x, Spring Boot 3.5.x, Spring Modulith, Spring Security, Flyway, jOOQ (KotlinGenerator), PostgreSQL 17, Testcontainers, springdoc (OpenAPI 3.1), Scalar, Mailpit (local SMTP), GitHub Actions.

**Version policy:** at the start of Task 1, check the current latest stable versions of every plugin/library below (Maven Central / plugin portal) and use those instead of the pinned examples if newer stable exists. Never use milestone/RC versions.

**Conventions used throughout:**
- Base package `com.verifolio`; jOOQ generated code in `com.verifolio.jooq` (build dir, not committed).
- All commands run from `apps/backend/` unless stated otherwise.
- Docker must be running (compose services + Testcontainers).
- Commit after every green step; prefix messages per `BRANCHING_AND_PR_RULES.md` (`feat:`, `chore:`, `test:`, `docs:`, `ci:`).

---

### Task 1: Gradle scaffold for apps/backend

**Files:**
- Create: `apps/backend/settings.gradle.kts`
- Create: `apps/backend/build.gradle.kts`
- Create: `apps/backend/gradle.properties`
- Create: `apps/backend/.gitignore`
- Create: `apps/backend/src/main/kotlin/com/verifolio/BackendApplication.kt`
- Create: `apps/backend/src/main/resources/application.yaml`
- Create: gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`)

- [ ] **Step 1: Create project files**

`apps/backend/settings.gradle.kts`:
```kotlin
rootProject.name = "verifolio-backend"
```

`apps/backend/gradle.properties`:
```properties
org.gradle.caching=true
org.gradle.parallel=true
kotlin.code.style=official
```

`apps/backend/.gitignore`:
```text
build/
.gradle/
.kotlin/
```

`apps/backend/build.gradle.kts` (versions: verify latest stable first, see Version policy):
```kotlin
import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.containers.PostgreSQLContainer

buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.testcontainers:postgresql:1.21.3")
        classpath("org.flywaydb:flyway-database-postgresql:11.10.0")
        classpath("org.postgresql:postgresql:42.7.7")
        classpath("org.jooq:jooq-codegen:3.20.5")
    }
}

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.verifolio"
version = "0.1.0-SNAPSHOT"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-core")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.9")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:1.4.1")
        mavenBom("org.testcontainers:testcontainers-bom:1.21.3")
    }
}

tasks.withType<Test> { useJUnitPlatform() }

// ---- jOOQ code generation from Flyway migrations (Testcontainers) ----
val jooqOutput = layout.buildDirectory.dir("generated-jooq")

tasks.register("generateJooq") {
    group = "build"
    description = "Runs Flyway migrations in a throwaway Postgres container and generates jOOQ code"
    inputs.dir(layout.projectDirectory.dir("src/main/resources/db/migration"))
    outputs.dir(jooqOutput)
    doLast {
        PostgreSQLContainer<Nothing>("postgres:17-alpine").use { pg ->
            pg.start()
            Flyway.configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .locations("filesystem:${projectDir}/src/main/resources/db/migration")
                .load()
                .migrate()
            GenerationTool.generate(
                Configuration()
                    .withJdbc(
                        Jdbc().withDriver("org.postgresql.Driver")
                            .withUrl(pg.jdbcUrl).withUser(pg.username).withPassword(pg.password)
                    )
                    .withGenerator(
                        Generator()
                            .withName("org.jooq.codegen.KotlinGenerator")
                            .withDatabase(
                                Database()
                                    .withName("org.jooq.meta.postgres.PostgresDatabase")
                                    .withInputSchema("public")
                                    .withExcludes("flyway_schema_history")
                            )
                            .withTarget(
                                Target()
                                    .withPackageName("com.verifolio.jooq")
                                    .withDirectory(jooqOutput.get().asFile.absolutePath)
                            )
                    )
            )
        }
    }
}

sourceSets { named("main") { kotlin.srcDir(jooqOutput) } }
tasks.named("compileKotlin") { dependsOn("generateJooq") }
```

Note: `generateJooq` will fail until Task 3 adds the first migration. That is expected; Steps 3–4 of this task only verify compilation of an empty app, so **temporarily** make `generateJooq` tolerate an absent migrations dir by creating the empty directory now:

```bash
mkdir -p src/main/resources/db/migration
```

`apps/backend/src/main/kotlin/com/verifolio/BackendApplication.kt`:
```kotlin
package com.verifolio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@Modulithic
@SpringBootApplication
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
```

`apps/backend/src/main/resources/application.yaml`:
```yaml
spring:
  application:
    name: verifolio-backend
  datasource:
    url: jdbc:postgresql://localhost:5432/verifolio
    username: verifolio
    password: verifolio
  flyway:
    enabled: true
  mail:
    host: localhost
    port: 1025

springdoc:
  api-docs:
    version: openapi_3_1

verifolio:
  region: local # development-only value, see docs/REGION_POLICIES.md
  auth:
    token-pepper: local-dev-pepper-change-me
    magic-link-ttl: 15m
    session-ttl: 30d
    frontend-base-url: http://localhost:3000
```

- [ ] **Step 2: Generate the Gradle wrapper**

Run (requires a local Gradle; on macOS: `brew install gradle` if missing):
```bash
cd apps/backend && gradle wrapper --gradle-version 8.14
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` created.

- [ ] **Step 3: Verify latest stable versions**

Check each version pinned in `build.gradle.kts` against latest stable (e.g. `https://plugins.gradle.org`, Maven Central search). Update pins where newer stable exists. Never pick milestones/RCs.

- [ ] **Step 4: Verify the build compiles**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (jooq generation produces no sources yet — fine).

- [ ] **Step 5: Commit**

```bash
git add apps/backend && git commit -m "chore(backend): bootstrap Gradle + Spring Boot skeleton"
```

---

### Task 2: Local infrastructure (docker-compose)

**Files:**
- Create: `docker-compose.yml` (repo root, per LOCAL_DEVELOPMENT.md)

- [ ] **Step 1: Create compose file**

`docker-compose.yml`:
```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: verifolio
      POSTGRES_USER: verifolio
      POSTGRES_PASSWORD: verifolio
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: verifolio
      MINIO_ROOT_PASSWORD: verifolio-local
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]

  mailpit:
    image: axllent/mailpit:latest
    ports: ["1025:1025", "8025:8025"]

  temporal:
    image: temporalio/auto-setup:latest
    depends_on: [postgres]
    environment:
      DB: postgres12
      DB_PORT: "5432"
      POSTGRES_USER: verifolio
      POSTGRES_PWD: verifolio
      POSTGRES_SEEDS: postgres
    ports: ["7233:7233"]

  temporal-ui:
    image: temporalio/ui:latest
    depends_on: [temporal]
    environment:
      TEMPORAL_ADDRESS: temporal:7233
    ports: ["8088:8080"]

volumes:
  pgdata:
  miniodata:
```

- [ ] **Step 2: Verify services start**

Run (repo root): `docker compose up -d && docker compose ps`
Expected: postgres, minio, mailpit, temporal, temporal-ui all `running`. Mailpit UI at `http://localhost:8025`, Temporal UI at `http://localhost:8088`.

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml && git commit -m "chore: add local docker-compose infrastructure"
```

---

### Task 3: First migration + jOOQ generation

**Files:**
- Create: `apps/backend/src/main/resources/db/migration/V1__identity_and_audit.sql`

- [ ] **Step 1: Write the migration**

`V1__identity_and_audit.sql`:
```sql
create table user_account (
    id          uuid primary key default gen_random_uuid(),
    email       text not null unique,
    region      text not null,
    status      text not null default 'ACTIVE',
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create table magic_link_token (
    id             uuid primary key default gen_random_uuid(),
    email          text not null,
    token_hash     text not null unique,
    expires_at     timestamptz not null,
    consumed_at    timestamptz,
    invalidated_at timestamptz,
    created_at     timestamptz not null default now()
);
create index idx_magic_link_token_email on magic_link_token (email);

create table user_session (
    id              uuid primary key default gen_random_uuid(),
    user_account_id uuid not null references user_account (id),
    token_hash      text not null unique,
    ip_hash         text,
    user_agent_hash text,
    expires_at      timestamptz not null,
    revoked_at      timestamptz,
    created_at      timestamptz not null default now()
);
create index idx_user_session_account on user_session (user_account_id);

create table audit_event (
    id              uuid primary key default gen_random_uuid(),
    actor_type      text not null,
    actor_id        text,
    action          text not null,
    entity_type     text,
    entity_id       text,
    metadata        jsonb not null default '{}'::jsonb,
    ip_hash         text,
    user_agent_hash text,
    created_at      timestamptz not null default now()
);
create index idx_audit_event_action on audit_event (action);
```

- [ ] **Step 2: Generate jOOQ code and compile**

Run: `./gradlew generateJooq compileKotlin`
Expected: `BUILD SUCCESSFUL`; `build/generated-jooq/com/verifolio/jooq/tables/` contains `UserAccount`, `MagicLinkToken`, `UserSession`, `AuditEvent`.

- [ ] **Step 3: Commit**

```bash
git add apps/backend/src/main/resources/db/migration && git commit -m "feat(backend): V1 schema for identity, sessions, audit"
```

---

### Task 4: Modulith package skeleton + boundary test

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/<module>/package-info.java` for each of: `identity`, `profiles`, `organizations`, `contacts`, `requests`, `templates`, `documents`, `files`, `verification`, `signatures`, `workflows`, `notifications`, `audit`, `admin`, `platform`
- Test: `apps/backend/src/test/kotlin/com/verifolio/ModularityTests.kt`

- [ ] **Step 1: Write the failing boundary test**

`ModularityTests.kt`:
```kotlin
package com.verifolio

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    private val modules = ApplicationModules.of(BackendApplication::class.java)

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `expected modules exist`() {
        val names = modules.map { it.name }.toSet()
        val expected = setOf(
            "identity", "profiles", "organizations", "contacts", "requests",
            "templates", "documents", "files", "verification", "signatures",
            "workflows", "notifications", "audit", "admin", "platform",
        )
        require(names.containsAll(expected)) { "Missing modules: ${expected - names}" }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.verifolio.ModularityTests`
Expected: FAIL — `expected modules exist` fails (no packages yet).

- [ ] **Step 3: Create module packages**

For each module listed above create `package-info.java` (packages need at least one file; example for `identity`, repeat with the matching description from `docs/MODULES.md`):

```java
/**
 * Identity module: user accounts, magic-link authentication, sessions.
 * Boundary rules: see docs/MODULES.md and docs/ARCHITECTURE.md.
 */
package com.verifolio.identity;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.verifolio.ModularityTests`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): modulith module skeleton with boundary tests"
```

---

### Task 5: Platform — config properties and API error handling

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/config/VerifolioProperties.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/web/ApiError.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/web/ApiException.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/web/GlobalExceptionHandler.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/platform/web/GlobalExceptionHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

`GlobalExceptionHandlerTest.kt`:
```kotlin
package com.verifolio.platform.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `maps ApiException to error body with code`() {
        val response = handler.handleApiException(
            ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token")
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!.code).isEqualTo("UNAUTHORIZED")
        assertThat(response.body!!.message).isEqualTo("Invalid or expired token")
    }

    @Test
    fun `maps unexpected exception to INTERNAL_ERROR without leaking details`() {
        val response = handler.handleUnexpected(IllegalStateException("secret internals"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.code).isEqualTo("INTERNAL_ERROR")
        assertThat(response.body!!.message).doesNotContain("secret")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.platform.web.*"`
Expected: FAIL — classes do not exist (compilation error).

- [ ] **Step 3: Implement**

`VerifolioProperties.kt`:
```kotlin
package com.verifolio.platform.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "verifolio")
data class VerifolioProperties(
    val region: String,
    val auth: Auth,
) {
    data class Auth(
        val tokenPepper: String,
        val magicLinkTtl: Duration,
        val sessionTtl: Duration,
        val frontendBaseUrl: String,
    )
}
```

Register in `BackendApplication.kt` — add annotation:
```kotlin
@ConfigurationPropertiesScan
```
(import `org.springframework.boot.context.properties.ConfigurationPropertiesScan`).

`ApiError.kt`:
```kotlin
package com.verifolio.platform.web

data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String?> = emptyMap(),
)
```

`ApiException.kt`:
```kotlin
package com.verifolio.platform.web

import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, String?> = emptyMap(),
) : RuntimeException(message)
```

`GlobalExceptionHandler.kt`:
```kotlin
package com.verifolio.platform.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(ex.status).body(ApiError(ex.code, ex.message, ex.details))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val details = ex.bindingResult.fieldErrors.associate { it.field to it.defaultMessage }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError("VALIDATION_FAILED", "Request validation failed", details))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("INTERNAL_ERROR", "Internal error"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.verifolio.platform.web.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): platform config properties and API error contract"
```

---

### Task 6: Audit module core (TDD, Testcontainers)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/audit/AuditService.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/audit/infrastructure/JooqAuditRepository.kt`
- Create: `apps/backend/src/test/kotlin/com/verifolio/testsupport/IntegrationTest.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/audit/AuditServiceIntegrationTest.kt`

- [ ] **Step 1: Create the shared integration-test base**

`IntegrationTest.kt`:
```kotlin
package com.verifolio.testsupport

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
```

- [ ] **Step 2: Write the failing test**

`AuditServiceIntegrationTest.kt`:
```kotlin
package com.verifolio.audit

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class AuditServiceIntegrationTest : IntegrationTest() {

    @Autowired lateinit var auditService: AuditService
    @Autowired lateinit var dsl: DSLContext

    @Test
    fun `records an append-only audit event`() {
        auditService.record(
            actorType = "USER",
            actorId = "user-1",
            action = "LOGIN_SUCCEEDED",
            entityType = "SESSION",
            entityId = "session-1",
            metadata = mapOf("region" to "local"),
            ipHash = "aa11",
            userAgentHash = "bb22",
        )
        val row = dsl.selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.ACTION.eq("LOGIN_SUCCEEDED"))
            .fetchOne()
        assertThat(row).isNotNull
        assertThat(row!!.actorType).isEqualTo("USER")
        assertThat(row.entityType).isEqualTo("SESSION")
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.audit.*"`
Expected: FAIL (AuditService does not exist).

- [ ] **Step 4: Implement**

`AuditService.kt` (module public API at package root):
```kotlin
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
```

`infrastructure/JooqAuditRepository.kt`:
```kotlin
package com.verifolio.audit.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.verifolio.audit.AuditService
import com.verifolio.jooq.tables.references.AUDIT_EVENT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Service

@Service
internal class JooqAuditRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : AuditService {

    override fun record(
        actorType: String, actorId: String?, action: String,
        entityType: String?, entityId: String?,
        metadata: Map<String, String>, ipHash: String?, userAgentHash: String?,
    ) {
        dsl.insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ACTOR_TYPE, actorType)
            .set(AUDIT_EVENT.ACTOR_ID, actorId)
            .set(AUDIT_EVENT.ACTION, action)
            .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_ID, entityId)
            .set(AUDIT_EVENT.METADATA, JSONB.jsonb(objectMapper.writeValueAsString(metadata)))
            .set(AUDIT_EVENT.IP_HASH, ipHash)
            .set(AUDIT_EVENT.USER_AGENT_HASH, userAgentHash)
            .execute()
    }
}
```

Note: the test will boot the full context; Spring Security default config may block nothing here (no HTTP involved). If context fails on missing mail health, set in `src/test/resources/application-test.yaml` nothing yet — mail sender is lazy.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.verifolio.audit.*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): audit module core with append-only events"
```

---

### Task 7: Notifications module — mail port

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/notifications/MailPort.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/notifications/infrastructure/SmtpMailAdapter.kt`
- Create: `apps/backend/src/test/kotlin/com/verifolio/testsupport/RecordingMailConfig.kt`

- [ ] **Step 1: Implement the port and SMTP adapter**

`MailPort.kt`:
```kotlin
package com.verifolio.notifications

interface MailPort {
    fun send(to: String, subject: String, textBody: String)
}
```

`infrastructure/SmtpMailAdapter.kt`:
```kotlin
package com.verifolio.notifications.infrastructure

import com.verifolio.notifications.MailPort
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

@Service
internal class SmtpMailAdapter(private val mailSender: JavaMailSender) : MailPort {
    override fun send(to: String, subject: String, textBody: String) {
        val message = SimpleMailMessage()
        message.setTo(to)
        message.from = "no-reply@verifolio.local"
        message.subject = subject
        message.text = textBody
        mailSender.send(message)
    }
}
```

`RecordingMailConfig.kt` (test support — captures mail instead of SMTP):
```kotlin
package com.verifolio.testsupport

import com.verifolio.notifications.MailPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

class RecordingMailPort : MailPort {
    data class Sent(val to: String, val subject: String, val textBody: String)
    val sent = CopyOnWriteArrayList<Sent>()
    override fun send(to: String, subject: String, textBody: String) {
        sent += Sent(to, subject, textBody)
    }
}

@TestConfiguration
class RecordingMailConfig {
    @Bean @Primary fun recordingMailPort(): RecordingMailPort = RecordingMailPort()
}
```

- [ ] **Step 2: Verify compilation and modulith boundaries**

Run: `./gradlew test --tests com.verifolio.ModularityTests`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): notifications mail port with SMTP adapter"
```

---

### Task 8: Identity domain — token hashing and generation (unit TDD)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/domain/TokenHasher.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/domain/TokenGenerator.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/domain/TokenHasherTest.kt`

- [ ] **Step 1: Write the failing test**

`TokenHasherTest.kt`:
```kotlin
package com.verifolio.identity.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenHasherTest {

    @Test
    fun `same input and pepper produce the same hash`() {
        val hasher = TokenHasher("pepper-1")
        assertThat(hasher.hash("token")).isEqualTo(hasher.hash("token"))
    }

    @Test
    fun `different pepper produces a different hash (keyed HMAC, not plain digest)`() {
        assertThat(TokenHasher("pepper-1").hash("token"))
            .isNotEqualTo(TokenHasher("pepper-2").hash("token"))
    }

    @Test
    fun `hash is lowercase hex and does not contain the input`() {
        val hash = TokenHasher("pepper").hash("secret-token")
        assertThat(hash).matches("[0-9a-f]{64}")
        assertThat(hash).doesNotContain("secret-token")
    }

    @Test
    fun `generated tokens are url-safe and unique`() {
        val tokens = (1..100).map { TokenGenerator.generate() }.toSet()
        assertThat(tokens).hasSize(100)
        tokens.forEach { assertThat(it).matches("[A-Za-z0-9_-]{43}") }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.identity.domain.*"`
Expected: FAIL (classes missing).

- [ ] **Step 3: Implement**

`TokenHasher.kt`:
```kotlin
package com.verifolio.identity.domain

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
```

`TokenGenerator.kt`:
```kotlin
package com.verifolio.identity.domain

import java.security.SecureRandom
import java.util.Base64

object TokenGenerator {
    private val random = SecureRandom()
    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.verifolio.identity.domain.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): identity token hashing and generation"
```

---

### Task 9: Magic link request flow (integration TDD)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/application/MagicLinkService.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/infrastructure/IdentityBeans.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/AuthController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/AuthDtos.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/SecurityConfig.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/MagicLinkRequestIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

`MagicLinkRequestIntegrationTest.kt`:
```kotlin
package com.verifolio.identity

import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class MagicLinkRequestIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    @Test
    fun `requesting a magic link stores only a hash and mails the raw token`() {
        val response = rest.postForEntity(
            "/api/v1/auth/magic-links",
            mapOf("email" to "User@Example.com "),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)

        val sent = mail.sent.single { it.to == "user@example.com" }
        val rawToken = Regex("token=([A-Za-z0-9_-]+)").find(sent.textBody)!!.groupValues[1]

        val stored = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("user@example.com"))
            .fetch()
        assertThat(stored).hasSize(1)
        assertThat(stored.first().tokenHash).isNotEqualTo(rawToken)
        assertThat(stored.first().tokenHash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `response is identical for unknown emails (anti-enumeration)`() {
        val a = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "nobody@example.com"), String::class.java)
        val b = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "nobody2@example.com"), String::class.java)
        assertThat(a.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(a.body).isEqualTo(b.body!!.replace("nobody2", "nobody"))
    }

    @Test
    fun `re-requesting invalidates previous tokens`() {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "twice@example.com"), Map::class.java)
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "twice@example.com"), Map::class.java)
        val active = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("twice@example.com"))
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .fetch()
        assertThat(active).hasSize(1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.identity.MagicLinkRequestIntegrationTest"`
Expected: FAIL (endpoint missing → 401/404 or compile error).

- [ ] **Step 3: Implement**

`identity/infrastructure/IdentityBeans.kt`:
```kotlin
package com.verifolio.identity.infrastructure

import com.verifolio.identity.domain.TokenHasher
import com.verifolio.platform.config.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class IdentityBeans {
    @Bean
    fun tokenHasher(props: VerifolioProperties) = TokenHasher(props.auth.tokenPepper)
}
```

`identity/application/MagicLinkService.kt`:
```kotlin
package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.notifications.MailPort
import com.verifolio.platform.config.VerifolioProperties
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class MagicLinkService(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val mail: MailPort,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    @Transactional
    fun requestMagicLink(rawEmail: String, ipHash: String?, userAgentHash: String?) {
        val email = rawEmail.trim().lowercase()
        val now = OffsetDateTime.now()

        // Reissue invalidates all previous unconsumed tokens (docs/AUTHENTICATION.md).
        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.INVALIDATED_AT, now)
            .where(MAGIC_LINK_TOKEN.EMAIL.eq(email))
            .and(MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .execute()

        val rawToken = TokenGenerator.generate()
        dsl.insertInto(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.EMAIL, email)
            .set(MAGIC_LINK_TOKEN.TOKEN_HASH, hasher.hash(rawToken))
            .set(MAGIC_LINK_TOKEN.EXPIRES_AT, now.plus(props.auth.magicLinkTtl))
            .execute()

        mail.send(
            to = email,
            subject = "Your Verifolio sign-in link",
            textBody = "Sign in to Verifolio: ${props.auth.frontendBaseUrl}/auth/callback?token=$rawToken\n" +
                "The link is valid for ${props.auth.magicLinkTtl.toMinutes()} minutes and can be used once.",
        )
        audit.record(
            actorType = "USER", actorId = null, action = "MAGIC_LINK_REQUESTED",
            entityType = "MAGIC_LINK_TOKEN", metadata = mapOf("region" to props.region),
            ipHash = ipHash, userAgentHash = userAgentHash,
        )
    }
}
```

`identity/api/AuthDtos.kt`:
```kotlin
package com.verifolio.identity.api

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class MagicLinkRequest(@field:NotBlank @field:Email val email: String)
data class SessionRequest(@field:NotBlank val token: String)
data class MessageResponse(val message: String)
data class CurrentUserResponse(val userId: String, val email: String, val region: String)
```

`identity/api/AuthController.kt` (only the magic-link endpoint for now; session endpoints come in Task 10):
```kotlin
package com.verifolio.identity.api

import com.verifolio.identity.application.MagicLinkService
import com.verifolio.identity.domain.TokenHasher
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val magicLinkService: MagicLinkService,
    private val hasher: TokenHasher,
) {

    @PostMapping("/magic-links")
    fun requestMagicLink(
        @Valid @RequestBody body: MagicLinkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<MessageResponse> {
        magicLinkService.requestMagicLink(body.email, ipHash(request), userAgentHash(request))
        // Identical response whether or not an account exists (anti-enumeration).
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(MessageResponse("If the address is valid, a sign-in link has been sent."))
    }

    internal fun ipHash(request: HttpServletRequest): String? =
        request.remoteAddr?.let { hasher.hash(it) }

    internal fun userAgentHash(request: HttpServletRequest): String? =
        request.getHeader("User-Agent")?.let { hasher.hash(it) }
}
```

`identity/api/SecurityConfig.kt`:
```kotlin
package com.verifolio.identity.api

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository

@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                // Unauthenticated entry points; session-protected endpoints keep CSRF.
                it.ignoringRequestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions")
            }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/magic-links", "/api/v1/auth/sessions").permitAll()
                it.requestMatchers("/v3/api-docs/**", "/docs").permitAll()
                it.anyRequest().authenticated()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.verifolio.identity.MagicLinkRequestIntegrationTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run modularity test (identity now depends on audit/notifications/platform)**

Run: `./gradlew test --tests com.verifolio.ModularityTests`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): magic link request endpoint with audit and anti-enumeration"
```

---

### Task 10: Consume token → cookie session (integration TDD)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/application/SessionService.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/identity/api/AuthController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/SessionCookie.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/api/SessionAuthFilter.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/identity/api/SecurityConfig.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/SessionIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

`SessionIntegrationTest.kt`:
```kotlin
package com.verifolio.identity

import com.verifolio.jooq.tables.references.AUDIT_EVENT
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class SessionIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    private fun obtainRawToken(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val sent = mail.sent.last { it.to == email }
        return Regex("token=([A-Za-z0-9_-]+)").find(sent.textBody)!!.groupValues[1]
    }

    @Test
    fun `consuming a valid token creates account, session cookie and audit trail`() {
        val token = obtainRawToken("alice@example.com")
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val cookie = response.headers[HttpHeaders.SET_COOKIE]!!.first { it.startsWith("verifolio_session=") }
        assertThat(cookie).contains("HttpOnly")
        assertThat(cookie).contains("SameSite=Strict")

        val me = rest.exchange(
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie.substringBefore(";")) }),
            Map::class.java,
        )
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(me.body!!["email"]).isEqualTo("alice@example.com")

        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("MAGIC_LINK_CONSUMED", "LOGIN_SUCCEEDED", "SESSION_CREATED")
    }

    @Test
    fun `a token cannot be consumed twice`() {
        val token = obtainRawToken("bob@example.com")
        rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        val second = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        assertThat(second.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        val actions = dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).fetch(AUDIT_EVENT.ACTION)
        assertThat(actions).contains("LOGIN_FAILED")
    }

    @Test
    fun `an invalidated token is rejected`() {
        val first = obtainRawToken("carol@example.com")
        obtainRawToken("carol@example.com") // reissue invalidates the first
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to first), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `unauthenticated current-session request is rejected`() {
        val me = rest.getForEntity("/api/v1/auth/sessions/current", Map::class.java)
        assertThat(me.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.identity.SessionIntegrationTest"`
Expected: FAIL (no /auth/sessions endpoint).

- [ ] **Step 3: Implement**

`identity/application/SessionService.kt`:
```kotlin
package com.verifolio.identity.application

import com.verifolio.audit.AuditService
import com.verifolio.identity.domain.TokenGenerator
import com.verifolio.identity.domain.TokenHasher
import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.jooq.tables.references.USER_ACCOUNT
import com.verifolio.jooq.tables.references.USER_SESSION
import com.verifolio.platform.config.VerifolioProperties
import com.verifolio.platform.web.ApiException
import org.jooq.DSLContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

data class AuthenticatedUser(val userId: UUID, val email: String, val region: String)
data class CreatedSession(val rawToken: String, val user: AuthenticatedUser, val ttlSeconds: Long)

@Service
class SessionService(
    private val dsl: DSLContext,
    private val hasher: TokenHasher,
    private val audit: AuditService,
    private val props: VerifolioProperties,
) {

    @Transactional
    fun consumeMagicLink(rawToken: String, ipHash: String?, userAgentHash: String?): CreatedSession {
        val now = OffsetDateTime.now()
        val tokenRow = dsl.selectFrom(MAGIC_LINK_TOKEN)
            .where(MAGIC_LINK_TOKEN.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(MAGIC_LINK_TOKEN.CONSUMED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.INVALIDATED_AT.isNull)
            .and(MAGIC_LINK_TOKEN.EXPIRES_AT.gt(now))
            .forUpdate()
            .fetchOne()
            ?: run {
                audit.record("USER", null, "LOGIN_FAILED", "MAGIC_LINK_TOKEN",
                    metadata = mapOf("reason" to "invalid_or_expired_token"),
                    ipHash = ipHash, userAgentHash = userAgentHash)
                throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token")
            }

        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.CONSUMED_AT, now)
            .where(MAGIC_LINK_TOKEN.ID.eq(tokenRow.id))
            .execute()

        val account = dsl.selectFrom(USER_ACCOUNT)
            .where(USER_ACCOUNT.EMAIL.eq(tokenRow.email))
            .fetchOne()
            ?: dsl.insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.EMAIL, tokenRow.email)
                .set(USER_ACCOUNT.REGION, props.region)
                .returning()
                .fetchOne()!!

        val sessionToken = TokenGenerator.generate()
        dsl.insertInto(USER_SESSION)
            .set(USER_SESSION.USER_ACCOUNT_ID, account.id)
            .set(USER_SESSION.TOKEN_HASH, hasher.hash(sessionToken))
            .set(USER_SESSION.IP_HASH, ipHash)
            .set(USER_SESSION.USER_AGENT_HASH, userAgentHash)
            .set(USER_SESSION.EXPIRES_AT, now.plus(props.auth.sessionTtl))
            .execute()

        val user = AuthenticatedUser(account.id!!, account.email!!, account.region!!)
        audit.record("USER", user.userId.toString(), "MAGIC_LINK_CONSUMED", "MAGIC_LINK_TOKEN",
            tokenRow.id.toString(), ipHash = ipHash, userAgentHash = userAgentHash)
        audit.record("USER", user.userId.toString(), "LOGIN_SUCCEEDED", "USER_ACCOUNT",
            user.userId.toString(), ipHash = ipHash, userAgentHash = userAgentHash)
        audit.record("USER", user.userId.toString(), "SESSION_CREATED", "SESSION",
            ipHash = ipHash, userAgentHash = userAgentHash)
        return CreatedSession(sessionToken, user, props.auth.sessionTtl.seconds)
    }

    fun resolve(rawToken: String): AuthenticatedUser? {
        val now = OffsetDateTime.now()
        return dsl.select(USER_ACCOUNT.ID, USER_ACCOUNT.EMAIL, USER_ACCOUNT.REGION)
            .from(USER_SESSION)
            .join(USER_ACCOUNT).on(USER_ACCOUNT.ID.eq(USER_SESSION.USER_ACCOUNT_ID))
            .where(USER_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .and(USER_SESSION.EXPIRES_AT.gt(now))
            .fetchOne()
            ?.let { AuthenticatedUser(it.value1()!!, it.value2()!!, it.value3()!!) }
    }

    @Transactional
    fun revoke(rawToken: String, ipHash: String?, userAgentHash: String?) {
        val updated = dsl.update(USER_SESSION)
            .set(USER_SESSION.REVOKED_AT, OffsetDateTime.now())
            .where(USER_SESSION.TOKEN_HASH.eq(hasher.hash(rawToken)))
            .and(USER_SESSION.REVOKED_AT.isNull)
            .execute()
        if (updated > 0) {
            audit.record("USER", null, "SESSION_REVOKED", "SESSION", ipHash = ipHash, userAgentHash = userAgentHash)
            audit.record("USER", null, "LOGOUT", "SESSION", ipHash = ipHash, userAgentHash = userAgentHash)
        }
    }
}
```

`identity/api/SessionCookie.kt`:
```kotlin
package com.verifolio.identity.api

import org.springframework.http.ResponseCookie
import java.time.Duration

object SessionCookie {
    const val NAME = "verifolio_session"

    fun create(rawToken: String, ttlSeconds: Long): ResponseCookie =
        ResponseCookie.from(NAME, rawToken)
            .httpOnly(true).secure(true).sameSite("Strict").path("/")
            .maxAge(Duration.ofSeconds(ttlSeconds))
            .build()

    fun expire(): ResponseCookie =
        ResponseCookie.from(NAME, "")
            .httpOnly(true).secure(true).sameSite("Strict").path("/")
            .maxAge(Duration.ZERO)
            .build()
}
```

`identity/api/SessionAuthFilter.kt`:
```kotlin
package com.verifolio.identity.api

import com.verifolio.identity.application.SessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class SessionAuthFilter(private val sessionService: SessionService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val rawToken = request.cookies?.firstOrNull { it.name == SessionCookie.NAME }?.value
        if (rawToken != null && SecurityContextHolder.getContext().authentication == null) {
            sessionService.resolve(rawToken)?.let { user ->
                val auth = UsernamePasswordAuthenticationToken(user, rawToken, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        filterChain.doFilter(request, response)
    }
}
```

Modify `SecurityConfig.kt` — inject and register the filter:
```kotlin
// constructor:
class SecurityConfig(private val sessionAuthFilter: SessionAuthFilter)
// in filterChain(), before build():
http.addFilterBefore(sessionAuthFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter::class.java)
// and add exception handling so unauthenticated requests get 401 JSON instead of redirect:
http.exceptionHandling {
    it.authenticationEntryPoint { _, response, _ ->
        response.status = 401
        response.contentType = "application/json"
        response.writer.write("""{"code":"UNAUTHORIZED","message":"Authentication required","details":{}}""")
    }
}
```

Extend `AuthController.kt` — add endpoints:
```kotlin
// additional imports:
import com.verifolio.identity.application.AuthenticatedUser
import com.verifolio.identity.application.SessionService
import org.springframework.http.HttpHeaders
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping

// constructor gains: private val sessionService: SessionService

@PostMapping("/sessions")
fun createSession(
    @Valid @RequestBody body: SessionRequest,
    request: HttpServletRequest,
): ResponseEntity<CurrentUserResponse> {
    val created = sessionService.consumeMagicLink(body.token, ipHash(request), userAgentHash(request))
    val cookie = SessionCookie.create(created.rawToken, created.ttlSeconds)
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, cookie.toString())
        .body(CurrentUserResponse(created.user.userId.toString(), created.user.email, created.user.region))
}

@GetMapping("/sessions/current")
fun currentSession(
    @AuthenticationPrincipal user: AuthenticatedUser,
): CurrentUserResponse = CurrentUserResponse(user.userId.toString(), user.email, user.region)

@DeleteMapping("/sessions/current")
fun logout(
    @CookieValue(SessionCookie.NAME) rawToken: String,
    request: HttpServletRequest,
): ResponseEntity<Void> {
    sessionService.revoke(rawToken, ipHash(request), userAgentHash(request))
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, SessionCookie.expire().toString())
        .build()
}
```

Note: `TestRestTemplate` does not send `Secure` cookies over http by default only in browsers — it sends whatever `Cookie` header the test sets manually, so `secure(true)` is fine in tests.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.verifolio.identity.SessionIntegrationTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): session creation, resolution, logout with cookie auth"
```

---

### Task 11: Logout CSRF + expired-token test

**Files:**
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/LogoutAndExpiryIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

`LogoutAndExpiryIntegrationTest.kt`:
```kotlin
package com.verifolio.identity

import com.verifolio.jooq.tables.references.MAGIC_LINK_TOKEN
import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import com.verifolio.testsupport.RecordingMailPort
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime

@Import(RecordingMailConfig::class)
class LogoutAndExpiryIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate
    @Autowired lateinit var mail: RecordingMailPort
    @Autowired lateinit var dsl: DSLContext

    private fun login(email: String): String {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to email), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == email }.textBody)!!.groupValues[1]
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        return response.headers[HttpHeaders.SET_COOKIE]!!
            .first { it.startsWith("verifolio_session=") }.substringBefore(";")
    }

    @Test
    fun `logout revokes the session`() {
        val cookie = login("dave@example.com")
        val csrf = rest.exchange( // obtain XSRF cookie via a GET
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java,
        ).headers[HttpHeaders.SET_COOKIE]?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
        val xsrf = csrf?.substringAfter("XSRF-TOKEN=")?.substringBefore(";")

        val headers = HttpHeaders().apply {
            add(HttpHeaders.COOKIE, cookie)
            if (xsrf != null) {
                add(HttpHeaders.COOKIE, "XSRF-TOKEN=$xsrf")
                add("X-XSRF-TOKEN", xsrf)
            }
        }
        val logout = rest.exchange("/api/v1/auth/sessions/current", HttpMethod.DELETE, HttpEntity<Void>(headers), Void::class.java)
        assertThat(logout.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val after = rest.exchange(
            "/api/v1/auth/sessions/current", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add(HttpHeaders.COOKIE, cookie) }), Map::class.java,
        )
        assertThat(after.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `expired magic link is rejected`() {
        rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "erin@example.com"), Map::class.java)
        val token = Regex("token=([A-Za-z0-9_-]+)")
            .find(mail.sent.last { it.to == "erin@example.com" }.textBody)!!.groupValues[1]
        dsl.update(MAGIC_LINK_TOKEN)
            .set(MAGIC_LINK_TOKEN.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
            .where(MAGIC_LINK_TOKEN.EMAIL.eq("erin@example.com"))
            .execute()
        val response = rest.postForEntity("/api/v1/auth/sessions", mapOf("token" to token), Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
```

- [ ] **Step 2: Run test — fix until green**

Run: `./gradlew test --tests "com.verifolio.identity.LogoutAndExpiryIntegrationTest"`
Expected: likely PASS with Task 10 implementation. If the CSRF token dance fails with 403, adjust `SecurityConfig` so the XSRF cookie is issued on authenticated GETs (add `it.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())` inside the `csrf {}` block and ensure the token is rendered by adding a request attribute read — see Spring Security 6 docs on SPA CSRF). Iterate until both tests pass.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew test`
Expected: all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add apps/backend/src && git commit -m "test(backend): logout CSRF flow and magic-link expiry coverage"
```

---

### Task 12: Rate limiting (TDD)

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/identity/infrastructure/SlidingWindowRateLimiter.kt`
- Modify: `apps/backend/src/main/kotlin/com/verifolio/identity/application/MagicLinkService.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/infrastructure/SlidingWindowRateLimiterTest.kt`
- Test: `apps/backend/src/test/kotlin/com/verifolio/identity/RateLimitIntegrationTest.kt`

- [ ] **Step 1: Write the failing unit test**

`SlidingWindowRateLimiterTest.kt`:
```kotlin
package com.verifolio.identity.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class SlidingWindowRateLimiterTest {

    @Test
    fun `allows up to the limit within the window`() {
        val limiter = SlidingWindowRateLimiter(limit = 3, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-07-04T10:00:00Z")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0)).isFalse()
    }

    @Test
    fun `window slides - old entries expire`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        val t0 = Instant.parse("2026-07-04T10:00:00Z")
        assertThat(limiter.tryAcquire("k", t0)).isTrue()
        assertThat(limiter.tryAcquire("k", t0.plusSeconds(901))).isTrue()
    }

    @Test
    fun `keys are independent`() {
        val limiter = SlidingWindowRateLimiter(limit = 1, window = Duration.ofMinutes(15))
        val t0 = Instant.now()
        assertThat(limiter.tryAcquire("a", t0)).isTrue()
        assertThat(limiter.tryAcquire("b", t0)).isTrue()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.identity.infrastructure.SlidingWindowRateLimiterTest"`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement the limiter**

`SlidingWindowRateLimiter.kt`:
```kotlin
package com.verifolio.identity.infrastructure

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-process sliding window; per-instance only (single cell, single instance for MVP). */
class SlidingWindowRateLimiter(private val limit: Int, private val window: Duration) {
    private val hits = ConcurrentHashMap<String, MutableList<Instant>>()

    fun tryAcquire(key: String, now: Instant = Instant.now()): Boolean {
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeIf { it.isBefore(now.minus(window)) }
            if (list.size >= limit) return false
            list.add(now)
            return true
        }
    }
}
```

- [ ] **Step 4: Write the failing integration test**

`RateLimitIntegrationTest.kt`:
```kotlin
package com.verifolio.identity

import com.verifolio.testsupport.IntegrationTest
import com.verifolio.testsupport.RecordingMailConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus

@Import(RecordingMailConfig::class)
class RateLimitIntegrationTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate

    @Test
    fun `magic link requests per email are rate limited with RATE_LIMITED code`() {
        repeat(5) {
            val ok = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "spam@example.com"), Map::class.java)
            assertThat(ok.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        }
        val sixth = rest.postForEntity("/api/v1/auth/magic-links", mapOf("email" to "spam@example.com"), Map::class.java)
        assertThat(sixth.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(sixth.body!!["code"]).isEqualTo("RATE_LIMITED")
    }
}
```

- [ ] **Step 5: Wire the limiter into MagicLinkService**

In `IdentityBeans.kt` add:
```kotlin
@Bean("magicLinkEmailLimiter")
fun magicLinkEmailLimiter() = SlidingWindowRateLimiter(limit = 5, window = Duration.ofMinutes(15))

@Bean("magicLinkIpLimiter")
fun magicLinkIpLimiter() = SlidingWindowRateLimiter(limit = 20, window = Duration.ofMinutes(15))
```
(imports: `com.verifolio.identity.infrastructure.SlidingWindowRateLimiter`, `java.time.Duration`.)

In `MagicLinkService` — constructor gains:
```kotlin
@Qualifier("magicLinkEmailLimiter") private val emailLimiter: SlidingWindowRateLimiter,
@Qualifier("magicLinkIpLimiter") private val ipLimiter: SlidingWindowRateLimiter,
```
and at the top of `requestMagicLink`, right after normalizing the email:
```kotlin
val emailAllowed = emailLimiter.tryAcquire(email)
val ipAllowed = ipHash == null || ipLimiter.tryAcquire(ipHash)
if (!emailAllowed || !ipAllowed) {
    throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests, try again later")
}
```
(imports: `org.springframework.beans.factory.annotation.Qualifier`, `com.verifolio.platform.web.ApiException`, `org.springframework.http.HttpStatus`.)

- [ ] **Step 6: Run all tests**

Run: `./gradlew test`
Expected: all PASS (unit + integration; earlier tests use distinct emails so limits are not hit).

- [ ] **Step 7: Commit**

```bash
git add apps/backend/src && git commit -m "feat(backend): rate limiting for magic link requests"
```

---

### Task 13: OpenAPI 3.1 + Scalar + contract snapshot

**Files:**
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/web/ApiDocsUiController.kt`
- Create: `apps/backend/src/main/kotlin/com/verifolio/platform/web/OpenApiConfig.kt`
- Create: `apps/backend/api/openapi.yaml` (exported snapshot)
- Test: `apps/backend/src/test/kotlin/com/verifolio/platform/web/OpenApiContractTest.kt`

- [ ] **Step 1: Write the failing test**

`OpenApiContractTest.kt`:
```kotlin
package com.verifolio.platform.web

import com.verifolio.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import java.nio.file.Files
import java.nio.file.Path

class OpenApiContractTest : IntegrationTest() {

    @Autowired lateinit var rest: TestRestTemplate

    @Test
    fun `spec is OpenAPI 3_1 and documents auth endpoints`() {
        val spec = rest.getForObject("/v3/api-docs.yaml", String::class.java)!!
        assertThat(spec).contains("openapi: 3.1")
        assertThat(spec).contains("/api/v1/auth/magic-links")
        assertThat(spec).contains("/api/v1/auth/sessions")
    }

    @Test
    fun `scalar UI is served at docs`() {
        val html = rest.getForObject("/docs", String::class.java)!!
        assertThat(html).contains("api-reference")
    }

    @Test
    fun `committed openapi yaml matches the served spec`() {
        val served = rest.getForObject("/v3/api-docs.yaml", String::class.java)!!
        val snapshotPath = Path.of("api/openapi.yaml")
        if (System.getenv("UPDATE_OPENAPI") == "true") {
            Files.createDirectories(snapshotPath.parent)
            Files.writeString(snapshotPath, served)
        }
        assertThat(snapshotPath).exists()
        assertThat(Files.readString(snapshotPath))
            .withFailMessage("openapi.yaml is stale. Re-run with UPDATE_OPENAPI=true and commit the diff.")
            .isEqualTo(served)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.verifolio.platform.web.OpenApiContractTest"`
Expected: FAIL — `/docs` missing (404) and no snapshot file.

- [ ] **Step 3: Implement**

`OpenApiConfig.kt`:
```kotlin
package com.verifolio.platform.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Verifolio API")
            .version("v1")
            .description("Verifolio backend API. Trust model: verification signals, not binary verified flags."),
    )
}
```

`ApiDocsUiController.kt` (Scalar):
```kotlin
package com.verifolio.platform.web

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
class ApiDocsUiController {

    @GetMapping("/docs", produces = [MediaType.TEXT_HTML_VALUE])
    @ResponseBody
    fun scalarUi(): String = """
        <!doctype html>
        <html>
          <head>
            <title>Verifolio API Reference</title>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1" />
          </head>
          <body>
            <script id="api-reference" data-url="/v3/api-docs"></script>
            <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
          </body>
        </html>
    """.trimIndent()
}
```

- [ ] **Step 4: Generate the snapshot, then verify it passes**

Run:
```bash
UPDATE_OPENAPI=true ./gradlew test --tests "com.verifolio.platform.web.OpenApiContractTest"
./gradlew test --tests "com.verifolio.platform.web.OpenApiContractTest"
```
Expected: first run writes `apps/backend/api/openapi.yaml`; second run PASSES with no changes.

- [ ] **Step 5: Commit**

```bash
git add apps/backend/src apps/backend/api && git commit -m "feat(backend): OpenAPI 3.1 spec, Scalar UI, contract snapshot test"
```

---

### Task 14: CI workflow + Dependabot

**Files:**
- Create: `.github/workflows/backend.yml`
- Create: `.github/dependabot.yml`

- [ ] **Step 1: Create the workflow**

`.github/workflows/backend.yml`:
```yaml
name: backend

on:
  push:
    branches: [main]
    paths: ["apps/backend/**", ".github/workflows/backend.yml"]
  pull_request:
    paths: ["apps/backend/**", ".github/workflows/backend.yml"]

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: apps/backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@v4
      - name: Build and test
        run: ./gradlew build
```

`.github/dependabot.yml`:
```yaml
version: 2
updates:
  - package-ecosystem: gradle
    directory: /apps/backend
    schedule:
      interval: weekly
  - package-ecosystem: github-actions
    directory: /
    schedule:
      interval: weekly
```

- [ ] **Step 2: Verify workflow syntax locally**

Run (repo root): `docker --version && ./apps/backend/gradlew -p apps/backend build`
Expected: `BUILD SUCCESSFUL` — the same command CI runs (Testcontainers works on ubuntu-latest runners).

- [ ] **Step 3: Commit**

```bash
git add .github && git commit -m "ci: backend build workflow and dependabot"
```

---

### Task 15: Documentation + agent data (post-development deliverables)

**Files:**
- Create: `apps/backend/README.md`
- Modify: `LOCAL_DEVELOPMENT.md` (remove/update the "aspirational" banner — backend now exists)
- Create: `docs/agent/IMPLEMENTATION_HISTORY.md`
- Modify: `docs/ROADMAP.md` (mark identity slice delivered)

- [ ] **Step 1: Write developer docs**

`apps/backend/README.md`:
```markdown
# Verifolio Backend

Kotlin + Spring Boot modular monolith (Spring Modulith). See `docs/ARCHITECTURE.md`.

## Prerequisites

- JDK 21, Docker (required for tests and jOOQ codegen)
- `docker compose up -d` from the repo root (Postgres, MinIO, Mailpit, Temporal)

## Commands

| Command | Purpose |
|---|---|
| `./gradlew bootRun` | run the app on :8080 |
| `./gradlew test` | all tests (Testcontainers) |
| `./gradlew generateJooq` | regenerate jOOQ code from Flyway migrations |
| `UPDATE_OPENAPI=true ./gradlew test --tests "*OpenApiContractTest"` | refresh `api/openapi.yaml` |

## API docs

- OpenAPI 3.1: `http://localhost:8080/v3/api-docs` (`.yaml` for YAML)
- Scalar UI: `http://localhost:8080/docs`
- Committed contract: `api/openapi.yaml` (input for frontend Orval codegen); a test fails if it drifts.

## Adding a migration

1. Add `src/main/resources/db/migration/V<next>__<name>.sql` (never edit applied migrations).
2. `./gradlew generateJooq` — jOOQ classes are generated into `build/generated-jooq` (not committed, never hand-edited).
3. Update `docs/DATA_MODEL.md` if the domain model changed.

## Module layout

`com.verifolio.<module>` packages, boundaries enforced by `ModularityTests`.
Implemented so far: `identity` (magic-link auth, sessions), `audit` (append-only events), `notifications` (mail port), `platform` (config, error contract).

## Local mail

Magic-link mail goes to Mailpit: `http://localhost:8025`.
```

- [ ] **Step 2: Update LOCAL_DEVELOPMENT.md**

Replace the status banner with: backend commands are now real for `apps/backend`; frontend commands remain target-state. Verify every command listed matches the README table above; fix drift.

- [ ] **Step 3: Write agent history**

`docs/agent/IMPLEMENTATION_HISTORY.md`:
```markdown
# Implementation History

Chronological record of delivered iterations. Agents: read this before starting work to
inherit context; append an entry when an iteration ships.

## 2026-07 — Backend bootstrap + identity slice

- `apps/backend` created: single Gradle module, Spring Modulith package-per-module,
  Flyway + jOOQ (codegen via Testcontainers at build; generated code not committed).
- Delivered: magic-link auth (15-min single-use tokens, reissue invalidates, keyed-HMAC
  hashes only), cookie sessions (HttpOnly/Secure/SameSite=Strict, CSRF on mutating
  authenticated endpoints), rate limiting (in-process sliding window), audit events for
  every auth action, OpenAPI 3.1 + Scalar at `/docs`, contract snapshot `api/openapi.yaml`.
- Conventions established:
  - module public API = classes at `com.verifolio.<module>` package root; internals in
    subpackages marked `internal`;
  - all secrets/tokens hashed with `TokenHasher` (HMAC + pepper from config);
  - every sensitive action calls `AuditService.record` in the same transaction;
  - API errors use `ApiError{code,message,details}` via `ApiException`;
  - integration tests extend `testsupport.IntegrationTest` (shared Postgres container)
    and import `RecordingMailConfig` instead of SMTP.
- Deferred: Temporal integration (compose service exists), AuthIdentity/OAuth,
  recommender invitation tokens, admin auth, step-up re-confirmation, distributed
  rate limiting.
```

- [ ] **Step 4: Update ROADMAP.md**

In the MVP section mark the identity item: `identity (magic link) — delivered (2026-07, apps/backend)`.

- [ ] **Step 5: Run everything one final time**

Run: `./gradlew build` (from `apps/backend`)
Expected: `BUILD SUCCESSFUL`, all tests green.

- [ ] **Step 6: Commit**

```bash
git add apps/backend/README.md LOCAL_DEVELOPMENT.md docs/agent/IMPLEMENTATION_HISTORY.md docs/ROADMAP.md
git commit -m "docs: backend developer docs and agent implementation history"
```

---

## Self-Review Notes

- Spec coverage: layout (T1–T2), version policy (T1 step 3 + T14 Dependabot), skeleton + Modulith (T4), identity slice incl. all security rules (T8–T12), audit (T6), notifications/Mailpit (T7), OpenAPI 3.1 + Scalar + snapshot for Orval (T13), CI (T14), developer/user/agent docs (T15; user docs skipped per spec — no user-facing surface beyond login yet).
- User documentation: spec says "skip unless warranted" — nothing user-facing to document for an API-only slice; noted in T15.
- Known risk: Spring Security CSRF token issuance for SPA-style clients may need the `CsrfTokenRequestAttributeHandler` adjustment (called out in T11 step 2).
- jOOQ generated property names (`tokenHash`, `actorType`, …) follow KotlinGenerator camelCase mapping of snake_case columns; if generation output differs, adjust accessors accordingly — do not edit generated code.
```
