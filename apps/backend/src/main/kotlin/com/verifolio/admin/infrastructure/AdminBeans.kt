package com.verifolio.admin.infrastructure

import com.verifolio.admin.application.AdminTotpCipher
import com.verifolio.platform.VerifolioProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
internal class AdminBeans {

    /** AES-256-GCM cipher for TOTP secrets at rest, keyed by the per-cell config (spec §TOTP). */
    @Bean
    fun adminTotpCipher(props: VerifolioProperties) = AdminTotpCipher(props.admin.totpSecretKey)
}
