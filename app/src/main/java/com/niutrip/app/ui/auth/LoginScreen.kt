package com.niutrip.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niutrip.app.ui.common.SegmentedControl
import com.niutrip.app.ui.theme.*

@Composable fun LoginScreen(viewModel: LoginViewModel, onSuccess: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.submit) { if (state.submit == AuthSubmitState.Success) onSuccess() }
    Column(Modifier.fillMaxSize().background(Background).imePadding().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Green50, Background))).padding(start = 28.dp, top = 50.dp, end = 28.dp, bottom = 26.dp)) {
            Box(Modifier.size(76.dp).background(Green500, RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                Text("N", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp)); Text("旅行牛牛", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("记录每一段旅程，分享每一处风景", color = Muted, fontSize = 13.sp)
        }
        SegmentedControl(listOf("登录", "注册"), if (state.mode == AuthMode.LOGIN) 0 else 1,
            { viewModel.setMode(if (it == 0) AuthMode.LOGIN else AuthMode.REGISTER) }, Modifier.padding(horizontal = 28.dp))
        Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.mode == AuthMode.REGISTER) OutlinedTextField(state.username, viewModel::setUsername, Modifier.fillMaxWidth(), label = { Text("用户名") }, leadingIcon = { Icon(Icons.Outlined.Person, null) }, singleLine = true, shape = RoundedCornerShape(12.dp))
            // 注册时实时校验手机号/邮箱格式（登录不拦，交由服务端判定）
            val identifierHint = if (state.mode == AuthMode.REGISTER && state.identifier.isNotBlank()) AuthValidation.identifierError(state.identifier) else null
            OutlinedTextField(state.identifier, viewModel::setIdentifier, Modifier.fillMaxWidth(), label = { Text("手机号 / 邮箱") }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                isError = identifierHint != null, supportingText = identifierHint?.let { hint -> { Text(hint, color = MaterialTheme.colorScheme.error) } })
            if (state.emailRegistration) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.emailCode, viewModel::setEmailCode, Modifier.weight(1f),
                        label = { Text("邮箱验证码") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(12.dp))
                    TextButton(viewModel::sendEmailCode,
                        enabled = !state.sendingCode && state.resendSeconds == 0 && identifierHint == null && state.submit !is AuthSubmitState.Loading) {
                        Text(if (state.sendingCode) "发送中" else if (state.resendSeconds > 0) "${state.resendSeconds} 秒后重发" else "获取验证码")
                    }
                }
                state.codeMessage?.let { Text(it, color = Green500, fontSize = 12.sp) }
                state.codeError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
            var passwordVisible by remember { mutableStateOf(false) }
            OutlinedTextField(state.password, viewModel::setPassword, Modifier.fillMaxWidth(), label = { Text("密码") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (passwordVisible) "隐藏密码" else "显示密码") } },
                singleLine = true, shape = RoundedCornerShape(12.dp))
            if (state.mode == AuthMode.REGISTER) {
                // 二次输入密码防手误；小眼睛与主密码框联动
                val confirmMismatch = state.confirmPassword.isNotEmpty() && state.confirmPassword != state.password
                OutlinedTextField(state.confirmPassword, viewModel::setConfirmPassword, Modifier.fillMaxWidth(), label = { Text("确认密码") }, leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (passwordVisible) "隐藏密码" else "显示密码") } },
                    isError = confirmMismatch,
                    supportingText = if (confirmMismatch) { { Text("两次输入的密码不一致", color = MaterialTheme.colorScheme.error) } } else null,
                    singleLine = true, shape = RoundedCornerShape(12.dp))
            }
            val error = (state.submit as? AuthSubmitState.Error)?.message
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            Button(viewModel::submit, Modifier.fillMaxWidth().height(48.dp), enabled = state.submit !is AuthSubmitState.Loading && !state.sendingCode, shape = RoundedCornerShape(24.dp)) {
                if (state.submit is AuthSubmitState.Loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (state.mode == AuthMode.LOGIN) "登 录" else "注 册")
            }
        }
    }
}
