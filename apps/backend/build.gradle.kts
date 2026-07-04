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
// Keep the runtime jOOQ version identical to the codegen version on the buildscript classpath.
extra["jooq.version"] = "3.20.5"

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
