package com.verifolio.platform.web

data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String?> = emptyMap(),
    val requestId: String? = null,
)
