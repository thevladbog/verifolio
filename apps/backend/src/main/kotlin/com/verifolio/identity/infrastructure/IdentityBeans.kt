package com.verifolio.identity.infrastructure

import com.verifolio.identity.domain.TokenHasher
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(IdentityProperties::class)
internal class IdentityBeans {
    @Bean
    fun tokenHasher(props: IdentityProperties) = TokenHasher(props.auth.tokenPepper)
}
