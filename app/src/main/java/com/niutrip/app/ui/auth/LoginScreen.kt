package com.niutrip.app.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niutrip.app.R
import com.niutrip.app.ui.theme.*

@Composable fun LoginScreen(viewModel: LoginViewModel, onSuccess: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val busy = state.submit is AuthSubmitState.Loading || state.sendingCode
    LaunchedEffect(state.submit) { if (state.submit == AuthSubmitState.Success) onSuccess() }
    Column(
        Modifier.fillMaxSize().background(Background).imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(Modifier.widthIn(max = 496.dp).fillMaxWidth().padding(horizontal = 28.dp).padding(top = 36.dp, bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.mipmap.ic_launcher_square), null,
                    Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("旅行牛牛", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("记录每一段旅程，分享每一处风景", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
            Box {
                HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = Line)
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    AuthTab("登录", state.mode == AuthMode.LOGIN, !busy) { viewModel.setMode(AuthMode.LOGIN) }
                    AuthTab("注册", state.mode == AuthMode.REGISTER, !busy) { viewModel.setMode(AuthMode.REGISTER) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (state.mode == AuthMode.LOGIN) {
                    Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        LoginMethodChoice("密码登录", state.loginMethod == LoginMethod.PASSWORD, !busy) {
                            viewModel.setLoginMethod(LoginMethod.PASSWORD)
                        }
                        VerticalDivider(Modifier.height(14.dp), color = Line)
                        LoginMethodChoice("短信验证码登录", state.loginMethod == LoginMethod.SMS, !busy) {
                            viewModel.setLoginMethod(LoginMethod.SMS)
                        }
                    }
                }
                if (state.mode == AuthMode.REGISTER) {
                    AuthField(state.username, viewModel::setUsername, "用户名", Icons.Outlined.Person)
                }
                val identifierHint = if (state.needsCode && state.identifier.isNotBlank()) {
                    if (state.smsLogin && '@' in state.identifier) "请输入正确的手机号（11 位，1 开头）"
                    else AuthValidation.identifierError(state.identifier)
                } else null
                AuthField(state.identifier, viewModel::setIdentifier,
                    if (state.smsLogin) "手机号" else "手机号 / 邮箱",
                    if (state.emailRegistration) Icons.Outlined.Email else Icons.Outlined.PhoneAndroid,
                    keyboardType = if (state.smsLogin) KeyboardType.Phone else KeyboardType.Email,
                    error = identifierHint)
                if (state.needsCode) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuthField(if (state.emailRegistration) state.emailCode else state.smsCode,
                            { if (state.emailRegistration) viewModel.setEmailCode(it) else viewModel.setSmsCode(it) },
                            if (state.emailRegistration) "邮箱验证码" else "短信验证码",
                            if (state.emailRegistration) Icons.Outlined.Email else Icons.Outlined.Sms,
                            keyboardType = KeyboardType.Number,
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VerticalDivider(Modifier.height(20.dp), color = Line)
                                    TextButton(viewModel::sendCode,
                                        modifier = Modifier.width(106.dp).height(48.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                        enabled = state.identifier.isNotBlank() && !busy && state.resendSeconds == 0 && identifierHint == null,
                                        colors = ButtonDefaults.textButtonColors(contentColor = Green700, disabledContentColor = Muted)) {
                                        Text(if (state.sendingCode) "发送中" else if (state.resendSeconds > 0) "${state.resendSeconds} 秒后重发" else "获取验证码",
                                            fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            })
                        state.codeMessage?.let { Text(it, color = Green700, fontSize = 12.sp) }
                        state.codeError?.let { Text(it, color = Danger, fontSize = 12.sp) }
                    }
                }
                val visibilityToggle: @Composable () -> Unit = {
                    IconButton({ passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (passwordVisible) "隐藏密码" else "显示密码", Modifier.size(20.dp), tint = Muted)
                    }
                }
                if (!state.smsLogin) {
                    AuthField(state.password, viewModel::setPassword, "密码", Icons.Outlined.Lock,
                        secure = true, visible = passwordVisible, trailing = visibilityToggle)
                }
                if (state.mode == AuthMode.REGISTER) {
                    val mismatch = state.confirmPassword.isNotEmpty() && state.confirmPassword != state.password
                    AuthField(state.confirmPassword, viewModel::setConfirmPassword, "确认密码", Icons.Outlined.Lock,
                        secure = true, visible = passwordVisible, trailing = visibilityToggle,
                        error = if (mismatch) "两次输入的密码不一致" else null)
                }
                (state.submit as? AuthSubmitState.Error)?.message?.let { Text(it, color = Danger, fontSize = 12.sp) }
                Button(viewModel::submit, Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp), enabled = !busy,
                    shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Green500)) {
                    if (state.submit is AuthSubmitState.Loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(if (state.mode == AuthMode.LOGIN) "登录" else "创建账号", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable private fun AuthTab(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.widthIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = if (selected) Ink else Muted, fontSize = 22.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        Spacer(Modifier.height(13.dp))
        Box(Modifier.size(24.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) Green500 else Color.Transparent))
    }
}

@Composable private fun LoginMethodChoice(title: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(title, color = if (selected) Green700 else Muted, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable private fun AuthField(
    value: String, onValueChange: (String) -> Unit, label: String, icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text, secure: Boolean = false, visible: Boolean = false,
    error: String? = null, trailing: (@Composable () -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth().height(54.dp),
            placeholder = { Text("请输入$label", fontSize = 15.sp) },
            textStyle = TextStyle(fontSize = 15.sp, color = Ink),
            leadingIcon = { Icon(icon, null, Modifier.size(20.dp), tint = Muted) },
            trailingIcon = trailing,
            visualTransformation = if (secure && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secure) KeyboardType.Password else keyboardType),
            singleLine = true, shape = RoundedCornerShape(12.dp), isError = error != null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White, errorContainerColor = Color.White,
                focusedBorderColor = Green500, unfocusedBorderColor = Line, errorBorderColor = Danger,
                focusedPlaceholderColor = Muted, unfocusedPlaceholderColor = Muted))
        error?.let { Text(it, color = Danger, fontSize = 12.sp) }
    }
}
