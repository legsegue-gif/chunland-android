package com.chunland.app.data.api

import com.chunland.app.core.network.ApiEnvelope
import com.chunland.app.data.model.AddRoleRequest
import com.chunland.app.data.model.AuthResult
import com.chunland.app.data.model.BindContactRequest
import com.chunland.app.data.model.OpenStoreRequest
import com.chunland.app.data.model.OtpLoginRequest
import com.chunland.app.data.model.OtpSendResult
import com.chunland.app.data.model.PasswordLoginRequest
import com.chunland.app.data.model.RefreshRequest
import com.chunland.app.data.model.ResetPasswordRequest
import com.chunland.app.data.model.SendOtpRequest
import com.chunland.app.data.model.SetPasswordRequest
import com.chunland.app.data.model.TokenPair
import com.chunland.app.data.model.UserProfile
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

// ⚠️ 路径不带前导斜杠：baseUrl 已含 /api/v1/，写 "/auth/..." 会把前缀整个吃掉。
interface AuthApi {

    @POST("auth/otp/send")
    suspend fun sendOtp(@Body body: SendOtpRequest): ApiEnvelope<OtpSendResult>

    @POST("auth/otp/login")
    suspend fun otpLogin(@Body body: OtpLoginRequest): ApiEnvelope<AuthResult>

    /** 密码登录（OTP 注册未设密码的账号会被 400 拒绝，文案如实透出） */
    @POST("auth/login")
    suspend fun passwordLogin(@Body body: PasswordLoginRequest): ApiEnvelope<AuthResult>

    /** 忘记密码：OTP 验证 + 设新密码，成功即登录 */
    @POST("auth/password/reset")
    suspend fun resetPassword(@Body body: ResetPasswordRequest): ApiEnvelope<AuthResult>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): ApiEnvelope<TokenPair>

    @GET("auth/me")
    suspend fun me(): ApiEnvelope<UserProfile>

    /** 设置/修改密码（已登录，不换 token） */
    @POST("auth/password")
    suspend fun setPassword(@Body body: SetPasswordRequest): ApiEnvelope<JsonElement>

    /** 绑定/换绑手机号或邮箱（OTP purpose=bind） */
    @POST("auth/bind")
    suspend fun bind(@Body body: BindContactRequest): ApiEnvelope<UserProfile>

    /** 注销账号（软删 + 脱敏，不可恢复） */
    @DELETE("auth/me")
    suspend fun deleteAccount(): ApiEnvelope<JsonElement>

    /** 追加身份并重签 token（merchant 不走这里，只随开店授予） */
    @POST("auth/roles")
    suspend fun addRole(@Body body: AddRoleRequest): ApiEnvelope<AuthResult>

    /**
     * 开店：建店 + 授予 merchant 角色 + 重签 token。端点归 merchants 但性质是 auth 流程 ——
     * 返回体与 /auth/roles 同构（多出的 merchant 字段被 ignoreUnknownKeys 吃掉），复用同一持久化路径。
     */
    @POST("merchants/self")
    suspend fun openStore(@Body body: OpenStoreRequest): ApiEnvelope<AuthResult>
}
