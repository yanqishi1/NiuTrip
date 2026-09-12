package com.niutrip.app.ui.auth

/** 注册时 identifier（手机号/邮箱）格式校验：含 @ 按邮箱校验，否则按大陆手机号。 */
object AuthValidation {
    private val PHONE = Regex("^1[3-9]\\d{9}$")
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** @return null=格式合法；否则为对应提示文案（提交与输入框实时提示共用）。 */
    fun identifierError(value: String): String? {
        val v = value.trim()
        return if (v.contains('@')) {
            if (EMAIL.matches(v)) null else "请输入正确的邮箱"
        } else {
            if (PHONE.matches(v)) null else "请输入正确的手机号（11 位，1 开头）"
        }
    }
}
