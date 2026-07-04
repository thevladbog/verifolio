package com.verifolio.platform.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.JsonNode

@Configuration
class OpenApiConfig {

    init {
        // Jackson 3's JsonNode exposes many boolean getters (isArray, isNull, …) that springdoc 3
        // reflects into schema properties whose HashMap ordering varies across JVM runs, making
        // the OpenAPI snapshot non-deterministic. Replace JsonNode with Object so it renders as
        // {} — an opaque JSON value, which is the correct semantic for clients anyway.
        SpringDocUtils.getConfig().replaceWithClass(JsonNode::class.java, Any::class.java)
    }

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Verifolio API")
            .version("v1")
            .description("Verifolio backend API. Trust model: verification signals, not binary verified flags."),
    )
}
