package com.verifolio.platform.web

import com.verifolio.platform.ApiException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `maps ApiException to error body with code`() {
        val response = handler.handleApiException(
            ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired token")
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!.code).isEqualTo("UNAUTHORIZED")
        assertThat(response.body!!.message).isEqualTo("Invalid or expired token")
    }

    @Test
    fun `maps unexpected exception to INTERNAL_ERROR without leaking details`() {
        val response = handler.handleUnexpected(IllegalStateException("secret internals"))
        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(response.body!!.code).isEqualTo("INTERNAL_ERROR")
        assertThat(response.body!!.message).doesNotContain("secret")
    }
}
