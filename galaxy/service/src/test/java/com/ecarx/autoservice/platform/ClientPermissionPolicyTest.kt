package com.ecarx.autoservice.platform

import org.junit.Assert.*
import org.junit.Test

class ClientPermissionPolicyTest {
    @Test fun `只能给自有记录仪申请相机和恢复权限不能授权其他权限或共享UID包`() {
        val p=ClientPermissionPolicy
        assertTrue(p.allowed(listOf(p.RECORD_PACKAGE),listOf(p.CAMERA)))
        assertTrue(p.allowed(listOf(p.RECORD_PACKAGE),listOf(p.CAMERA,p.OVERLAY)))
        assertFalse(p.allowed(listOf("other.app"),listOf(p.OVERLAY)))
        assertFalse(p.allowed(listOf("other.app"),listOf(p.CAMERA)))
        assertFalse(p.allowed(listOf(p.RECORD_PACKAGE),listOf("android.permission.RECORD_AUDIO")))
        assertFalse(p.allowed(listOf(p.RECORD_PACKAGE,"other.app"),listOf(p.CAMERA)))
        assertFalse(p.allowed(listOf(p.RECORD_PACKAGE),emptyList()))
    }
}
