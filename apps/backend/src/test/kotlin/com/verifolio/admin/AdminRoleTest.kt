package com.verifolio.admin

import com.verifolio.admin.domain.AdminPermission
import com.verifolio.admin.domain.AdminRole
import com.verifolio.admin.domain.has
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit test of the fixed role -> permission matrix (spec §RBAC table).
 * L1 = DSR_VIEW only; L2 = DSR + USER_VIEW + AUDIT_VIEW; SUPERADMIN = all.
 */
class AdminRoleTest {
    @Test
    fun `L1 has DSR_VIEW only`() {
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.DSR_VIEW)).isTrue()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.DSR_DECIDE)).isFalse()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.DSR_EXECUTE)).isFalse()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.USER_VIEW)).isFalse()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.AUDIT_VIEW)).isFalse()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.AUDIT_EXPORT)).isFalse()
        assertThat(AdminRole.SUPPORT_L1.has(AdminPermission.ADMIN_MANAGE)).isFalse()
    }

    @Test
    fun `L2 views users and audit and decides but cannot export or manage`() {
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.DSR_VIEW)).isTrue()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.DSR_DECIDE)).isTrue()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.DSR_EXECUTE)).isTrue()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.USER_VIEW)).isTrue()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.AUDIT_VIEW)).isTrue()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.AUDIT_EXPORT)).isFalse()
        assertThat(AdminRole.SUPPORT_L2.has(AdminPermission.ADMIN_MANAGE)).isFalse()
    }

    @Test
    fun `SUPERADMIN has every permission`() {
        AdminPermission.entries.forEach { permission ->
            assertThat(AdminRole.SUPERADMIN.has(permission))
                .describedAs("SUPERADMIN should have %s", permission)
                .isTrue()
        }
    }
}
