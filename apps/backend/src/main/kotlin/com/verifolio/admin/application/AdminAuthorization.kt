package com.verifolio.admin.application

import com.verifolio.admin.AdminActor
import com.verifolio.admin.domain.AdminPermission
import com.verifolio.admin.domain.has
import com.verifolio.platform.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * Service-level RBAC gate (spec §RBAC). Endpoints declare a required [AdminPermission]; this checks
 * it against the acting admin's role via the fixed code-defined matrix and throws 403 FORBIDDEN
 * otherwise. Authorization is by permission at the service layer, not URL-role in the filter chain,
 * so all admins reach the chain and the controller decides.
 */
@Component
internal class AdminAuthorization {

    /** Throws 403 FORBIDDEN if [actor]'s role is not granted [permission]. */
    fun require(actor: AdminActor, permission: AdminPermission) {
        if (!actor.role.has(permission)) {
            throw ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions")
        }
    }
}
