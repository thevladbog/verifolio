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
