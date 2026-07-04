package com.verifolio.identity.infrastructure

import com.verifolio.identity.domain.TokenHasher
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class IdentityBeans {
    @Bean
    fun tokenHasher(props: VerifolioProperties) = TokenHasher(props.auth.tokenPepper)
}
