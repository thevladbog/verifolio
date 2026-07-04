package com.verifolio.platform.web

import org.springframework.http.HttpStatus

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, String?> = emptyMap(),
) : RuntimeException(message)
