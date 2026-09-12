package com.niutrip.app.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthValidationTest {
    @Test fun `valid mainland phone passes`() {
        assertNull(AuthValidation.identifierError("13800138000"))
        assertNull(AuthValidation.identifierError("19912345678"))
    }

    @Test fun `invalid phone gets phone hint`() {
        val hint = "请输入正确的手机号（11 位，1 开头）"
        assertEquals(hint, AuthValidation.identifierError("1380013800"))     // 10 位
        assertEquals(hint, AuthValidation.identifierError("138001380001"))   // 12 位
        assertEquals(hint, AuthValidation.identifierError("23800138000"))    // 非 1 开头
        assertEquals(hint, AuthValidation.identifierError("12800138000"))    // 第二位非 3-9
        assertEquals(hint, AuthValidation.identifierError("138abc"))
    }

    @Test fun `valid email passes`() {
        assertNull(AuthValidation.identifierError("niu@example.com"))
        assertNull(AuthValidation.identifierError("travel.cow@sub.example.cn"))
    }

    @Test fun `invalid email gets email hint`() {
        assertEquals("请输入正确的邮箱", AuthValidation.identifierError("niu@example"))
        assertEquals("请输入正确的邮箱", AuthValidation.identifierError("niu @example.com"))
        assertEquals("请输入正确的邮箱", AuthValidation.identifierError("@example.com"))
    }
}
