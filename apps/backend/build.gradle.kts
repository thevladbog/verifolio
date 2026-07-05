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
        classpath("org.testcontainers:postgresql:1.21.4")
        classpath("org.flywaydb:flyway-database-postgresql:11.14.1")
        classpath("org.postgresql:postgresql:42.7.7")
        classpath("org.jooq:jooq-codegen:3.19.35")
    }
}

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.spring") version "2.4.0"
    id("org.springframework.boot") version "4.0.7"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.verifolio"
version = "0.1.0-SNAPSHOT"
// Keep the runtime jOOQ version explicitly identical to the codegen version on the
// buildscript classpath — do not rely on the Boot BOM staying on the same patch level.
extra["jooq.version"] = "3.19.35"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // spring-boot-starter-flyway includes spring-boot-flyway (autoconfiguration) + flyway-core
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.0.3")
    implementation(platform("software.amazon.awssdk:bom:2.31.78"))
    implementation("software.amazon.awssdk:s3")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")
    // RFC 6238 TOTP for admin MFA (minimal, no transitive bloat). Base32 encoding of the
    // secret uses commons-codec (not on the compile classpath transitively — declared here).
    implementation("com.eatthepath:java-otp:0.4.0")
    implementation("commons-codec:commons-codec:1.18.0")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:minio")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.0.5")
        mavenBom("org.testcontainers:testcontainers-bom:1.21.4")
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
        val migrationDir = layout.projectDirectory.dir("src/main/resources/db/migration").asFile
        val hasMigrations = migrationDir.listFiles()?.any { it.extension == "sql" } ?: false
        if (!hasMigrations) {
            logger.lifecycle("generateJooq: no SQL migrations found — skipping")
            jooqOutput.get().asFile.mkdirs()
            return@doLast
        }
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
