package com.chunland.app.data.model

import kotlinx.serialization.Serializable

// DTO 属性一律 camelCase —— ChunlandJson 的 SnakeCase 策略负责与 wire 格式互转。

@Serializable
data class AuthResult(
    val userId: Int,
    val roles: List<String>? = null,
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)

/** cooldown 驱动「X 秒后重发」倒计时；expiresIn 是验证码有效期。 */
@Serializable
data class OtpSendResult(
    val cooldown: Int,
    val expiresIn: Int,
)

@Serializable
data class UserProfile(
    val id: Int,
    val phone: String? = null,
    val email: String? = null,
    val isPhoneVerified: Boolean = false,
    val isEmailVerified: Boolean = false,
    val hasPassword: Boolean = false,
    val roles: List<String> = emptyList(),
    val createdAt: String = "",
)

@Serializable
data class SendOtpRequest(
    val channel: String,
    val target: String,
    val purpose: String = "login",
)

@Serializable
data class OtpLoginRequest(
    val channel: String,
    val target: String,
    val code: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** POST /auth/roles —— 为当前账号追加身份（consumer/agent；merchant 只随开店授予） */
@Serializable
data class AddRoleRequest(
    val role: String,
)

/** POST /auth/password —— 设置/修改密码（已登录）。OTP 账号首设密码 oldPassword 传 null。 */
@Serializable
data class SetPasswordRequest(
    val oldPassword: String? = null,
    val newPassword: String,
)

/** POST /auth/bind —— 绑定/换绑手机号或邮箱（OTP purpose=bind）。目标被占用时服务端 409。 */
@Serializable
data class BindContactRequest(
    val channel: String,
    val target: String,
    val code: String,
)

/** POST /auth/login —— 密码登录（phone / email 二选一） */
@Serializable
data class PasswordLoginRequest(
    val phone: String? = null,
    val email: String? = null,
    val password: String,
)

/** POST /auth/password/reset —— 忘记密码：OTP 验证 + 设新密码，成功即返回登录态 */
@Serializable
data class ResetPasswordRequest(
    val channel: String,
    val target: String,
    val code: String,
    val newPassword: String,
)
