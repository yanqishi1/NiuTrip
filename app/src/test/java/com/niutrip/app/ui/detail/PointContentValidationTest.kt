package com.niutrip.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointContentValidationTest {
    @Test fun `manual checkin with all content cleared may become auto point`() {
        assertNull(pointContentValidation(true, "", "", 0))
    }

    @Test fun `description and photos require a title`() {
        assertEquals("填写描述或添加图片前，请先填写标题",
            pointContentValidation(true, "", "描述", 0))
        assertEquals("填写描述或添加图片前，请先填写标题",
            pointContentValidation(true, "", "", 1))
    }

    @Test fun `editing auto point requires a title`() {
        assertEquals("编辑自动轨迹点时必须填写标题",
            pointContentValidation(false, "", "", 0))
        assertNull(pointContentValidation(false, "修正点", "", 0))
    }
}
