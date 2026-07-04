package com.verifolio.identity

import java.util.UUID

data class UserAccountCreated(val userAccountId: UUID, val email: String, val region: String)
