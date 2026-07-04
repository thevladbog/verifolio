package com.verifolio.identity

import java.util.UUID

data class AuthenticatedUser(val userId: UUID, val email: String, val region: String)
