package com.niutrip.app.ui.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.niutrip.app.data.TokenStore
import com.niutrip.app.data.remote.*
import com.niutrip.app.data.repo.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LoginViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class AuthApi : StubApi() {
        var registered: RegisterIn? = null
        val sentEmails = mutableListOf<String>()
        val sentSms = mutableListOf<SmsCodeIn>()
        var smsLoginInput: SmsLoginIn? = null
        var sendError: Exception? = null
        override suspend fun register(body: RegisterIn): LoginOut {
            registered = body
            return LoginOut("token", UserDto("user1", "牛牛"))
        }
        override suspend fun sendEmailCode(body: EmailCodeIn): VerificationCodeOut {
            sentEmails += body.email
            sendError?.let { throw it }
            return VerificationCodeOut("验证码已发送", 300, 60)
        }
        override suspend fun sendSmsCode(body: SmsCodeIn): VerificationCodeOut {
            sentSms += body
            sendError?.let { throw it }
            return VerificationCodeOut("短信验证码已发送", 300, 30)
        }
        override suspend fun smsLogin(body: SmsLoginIn): LoginOut {
            smsLoginInput = body
            return LoginOut("sms-token", UserDto("sms-user", "牛牛"))
        }
    }

    private fun makeVm(api: AuthApi): LoginViewModel {
        val tokens = TokenStore(ApplicationProvider.getApplicationContext<Application>())
        return LoginViewModel(AuthRepository(api, tokens)).apply {
            setMode(AuthMode.REGISTER)
            setUsername("牛牛")
            setIdentifier("new@example.com")
            setPassword("12345678")
            setConfirmPassword("12345678")
        }
    }

    @Test fun `email registration requires code and sends password confirmation`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.submit()
        assertNull(api.registered)
        assertEquals("请输入 6 位邮箱验证码", (vm.state.value.submit as AuthSubmitState.Error).message)
        vm.setEmailCode("012345")
        vm.submit()
        assertEquals("012345", api.registered?.email_code)
        assertEquals("12345678", api.registered?.password_confirm)
        assertEquals(AuthSubmitState.Success, vm.state.value.submit)
    }

    @Test fun `empty and mismatched confirmation cannot register`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setEmailCode("123456")
        for (confirm in listOf("", "123456789")) {
            vm.setConfirmPassword(confirm)
            vm.submit()
            assertEquals("两次输入的密码不一致", (vm.state.value.submit as AuthSubmitState.Error).message)
        }
        assertNull(api.registered)
    }

    @Test fun `cooldown stays with normalized email and edit clears code`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setIdentifier(" New@Example.com ")
        vm.sendCode()
        assertEquals(listOf("new@example.com"), api.sentEmails)
        assertEquals(60, vm.state.value.resendSeconds)
        vm.sendCode()
        assertEquals(1, api.sentEmails.size)
        vm.setEmailCode("123456")
        vm.setIdentifier("other@example.com")
        assertEquals("", vm.state.value.emailCode)
        assertEquals(0, vm.state.value.resendSeconds)
        vm.setIdentifier("new@example.com")
        assertEquals(60, vm.state.value.resendSeconds)
    }

    @Test fun `server retry interval and SMTP failure are shown`() {
        val api = AuthApi()
        val vm = makeVm(api)
        api.sendError = ApiException(429, "发送过于频繁", 35)
        vm.sendCode()
        assertEquals("发送过于频繁", vm.state.value.codeError)
        assertEquals(35, vm.state.value.resendSeconds)
        vm.setIdentifier("other@example.com")
        api.sendError = ApiException(503, "验证码发送失败", 60)
        vm.sendCode()
        assertEquals("验证码发送失败", vm.state.value.codeError)
        assertEquals(60, vm.state.value.resendSeconds)
    }

    @Test fun `phone registration requires SMS code and password confirmation`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setIdentifier("13800138000")
        vm.submit()
        assertNull(api.registered)
        assertEquals("请输入 6 位短信验证码", (vm.state.value.submit as AuthSubmitState.Error).message)
        vm.setSmsCode("012345")
        vm.submit()
        assertEquals("13800138000", api.registered?.phone)
        assertEquals("012345", api.registered?.sms_code)
        assertNull(api.registered?.email_code)
        assertEquals(AuthSubmitState.Success, vm.state.value.submit)
    }

    @Test fun `SMS login needs no password and persists login token`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setMode(AuthMode.LOGIN)
        vm.setLoginMethod(LoginMethod.SMS)
        vm.setIdentifier(" 13800138000 ")
        vm.setPassword("")
        vm.submit()
        assertNull(api.smsLoginInput)
        vm.setSmsCode("012345")
        vm.submit()
        assertEquals(SmsLoginIn("13800138000", "012345"), api.smsLoginInput)
        assertEquals(AuthSubmitState.Success, vm.state.value.submit)
        val tokens = TokenStore(ApplicationProvider.getApplicationContext<Application>())
        assertEquals("sms-token", tokens.token)
        assertEquals("sms-user", tokens.userId)
    }

    @Test fun `SMS purpose changes but cooldown remains for phone`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setIdentifier("13800138000")
        vm.sendCode()
        assertEquals(listOf(SmsCodeIn("13800138000", "register")), api.sentSms)
        assertEquals(30, vm.state.value.resendSeconds)
        vm.setSmsCode("123456")
        vm.setMode(AuthMode.LOGIN)
        vm.setLoginMethod(LoginMethod.SMS)
        assertEquals("", vm.state.value.smsCode)
        assertNull(vm.state.value.codeMessage)
        vm.sendCode()
        assertEquals(1, api.sentSms.size)
        vm.setIdentifier("13900139000")
        vm.sendCode()
        assertEquals(SmsCodeIn("13900139000", "login"), api.sentSms.last())
    }

    @Test fun `SMS login rejects email and switching method clears code`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setMode(AuthMode.LOGIN)
        vm.setLoginMethod(LoginMethod.SMS)
        vm.setSmsCode("123456")
        vm.sendCode()
        vm.submit()
        assertTrue(api.sentSms.isEmpty())
        assertNull(api.smsLoginInput)
        assertEquals("请输入正确的手机号（11 位，1 开头）", (vm.state.value.submit as AuthSubmitState.Error).message)
        vm.setLoginMethod(LoginMethod.PASSWORD)
        assertEquals("", vm.state.value.smsCode)
        assertEquals(AuthSubmitState.Idle, vm.state.value.submit)
    }

    @Test fun `SMS send errors preserve server cooldown`() {
        val api = AuthApi()
        val vm = makeVm(api)
        vm.setIdentifier("13800138000")
        api.sendError = ApiException(429, "发送过于频繁", 12)
        vm.sendCode()
        assertEquals(12, vm.state.value.resendSeconds)
        assertEquals("发送过于频繁", vm.state.value.codeError)
    }
}
