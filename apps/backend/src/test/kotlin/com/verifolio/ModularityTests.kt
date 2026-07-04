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
