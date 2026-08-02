package com.chunland.app.core.auth

import com.chunland.app.core.network.apiCall
import com.chunland.app.core.network.apiCallUnit
import com.chunland.app.data.api.AuthApi
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
import com.chunland.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 登录态唯一真相源（对齐 iOS AuthManager）：
 * StateFlow 驱动 UI 切换，token 落 TokenStore，刷新单飞，刷新失败强制登出。
 *
 * api 用 lambda 注入是为了斩断构造环：OkHttp 的 TokenAuthenticator 需要 AuthManager，
 * 而 AuthManager 的请求又要经过那个 OkHttp —— 两边都延迟取用即可安全成环。
 */
class AuthManager(
    private val store: TokenStore,
    private val api: () -> AuthApi,
) {

    data class AuthState(
        val isLoggedIn: Boolean = false,
        val userId: String? = null,
        val roles: List<String> = emptyList(),
        /** 当前活跃身份（consumer / agent / merchant），始终落在 roles 内 */
        val activeIdentity: String = "consumer",
    )

    private val _state = MutableStateFlow(
        AuthState(
            isLoggedIn = store.accessToken != null,
            userId = store.userId,
            roles = store.roles,
            activeIdentity = reconcileIdentity(store.roles, store.activeIdentity),
        )
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    // MARK: - OTP 登录（主路径）

    /** 申请验证码。channel: "sms" | "email"。返回 cooldown/expiresIn 给 UI 做倒计时。 */
    suspend fun sendOtp(channel: String, target: String, purpose: String = "login"): OtpSendResult =
        apiCall { api().sendOtp(SendOtpRequest(channel, target, purpose)) }

    /** 校验验证码 → 服务端 find-or-create → 落 token。无账号即自动注册。 */
    suspend fun loginWithOtp(channel: String, target: String, code: String) {
        val result = apiCall { api().otpLogin(OtpLoginRequest(channel, target, code)) }
        persist(result)
    }

    suspend fun me(): UserProfile {
        val profile = apiCall { api().me() }
        store.roles = profile.roles
        _state.update {
            it.copy(roles = profile.roles, activeIdentity = reconcileIdentity(profile.roles, it.activeIdentity))
        }
        store.activeIdentity = _state.value.activeIdentity
        return profile
    }

    // MARK: - 多身份（对齐 iOS：切换纯本地，开通经 /auth/roles 重签 token）

    /** 切换当前活跃身份（仅当账号已拥有该角色）。纯本地状态，后端零感知。 */
    fun switchIdentity(identity: String) {
        val current = _state.value
        if (!current.roles.contains(identity) || current.activeIdentity == identity) return
        store.activeIdentity = identity
        _state.update { it.copy(activeIdentity = identity) }
    }

    /** 开通新身份（consumer/agent）：后端追加角色并重签 token，成功后自动切到新身份。 */
    suspend fun addRole(role: String) {
        val result = apiCall { api().addRole(AddRoleRequest(role)) }
        persist(result)
        switchIdentity(role)
    }

    /**
     * 开店（对齐 iOS openMerchantStore）：merchant 角色只随开店授予，不走 /auth/roles。
     * 服务端一步完成建店 + 授角色 + 重签 token，成功即切到商家身份。
     */
    suspend fun openMerchantStore(name: String, areaCode: String?) {
        val result = apiCall { api().openStore(OpenStoreRequest(name, areaCode)) }
        persist(result)
        switchIdentity("merchant")
    }

    /** 密码登录（channel: sms=手机号 / email=邮箱）。 */
    suspend fun loginWithPassword(channel: String, target: String, password: String) {
        val result = apiCall {
            api().passwordLogin(
                PasswordLoginRequest(
                    phone = if (channel == "sms") target else null,
                    email = if (channel == "email") target else null,
                    password = password,
                ),
            )
        }
        persist(result)
    }

    /** 忘记密码：OTP（purpose=reset）验证 + 设新密码，成功即登录（对齐 iOS resetPassword）。 */
    suspend fun resetPassword(channel: String, target: String, code: String, newPassword: String) {
        val result = apiCall { api().resetPassword(ResetPasswordRequest(channel, target, code, newPassword)) }
        persist(result)
    }

    // MARK: - 账户管理（对齐 iOS AccountView 链路）

    /** 设置/修改密码（已登录）。OTP 账号首次设密码 oldPassword 传 null。不换 token。 */
    suspend fun setPassword(oldPassword: String?, newPassword: String) {
        apiCall { api().setPassword(SetPasswordRequest(oldPassword, newPassword)) }
    }

    /** 绑定/换绑手机号或邮箱（OTP purpose=bind）。目标被占用时服务端 409 如实透出。 */
    suspend fun bindContact(channel: String, target: String, code: String) {
        apiCall { api().bind(BindContactRequest(channel, target, code)) }
    }

    /** 注销账号（软删 + 脱敏，不可恢复）。成功后本地登出，UI 自动回游客态。 */
    suspend fun deleteAccount() {
        apiCallUnit { api().deleteAccount() }
        logout()
    }

    // MARK: - 刷新与登出

    /**
     * TokenAuthenticator 专用：单飞刷新。
     * previous = 触发 401 的旧 token；若已被并发请求刷过则直接复用新值。
     * 刷新失败（refresh token 也过期）→ 强制登出并返回 null。
     */
    suspend fun refreshedAccessToken(previous: String): String? = refreshMutex.withLock {
        val current = store.accessToken
        if (current != null && current != previous) return current

        val rt = store.refreshToken ?: run { logout(); return null }
        return try {
            val tokens = apiCall { api().refresh(RefreshRequest(rt)) }
            store.accessToken = tokens.accessToken
            store.refreshToken = tokens.refreshToken
            tokens.accessToken
        } catch (_: Exception) {
            logout()
            null
        }
    }

    fun logout() {
        store.clear()
        _state.value = AuthState()
    }

    // MARK: - Private

    private fun persist(result: AuthResult) {
        store.accessToken = result.accessToken
        store.refreshToken = result.refreshToken
        store.userId = result.userId.toString()
        val roles = result.roles ?: emptyList()
        store.roles = roles
        val identity = reconcileIdentity(roles, store.activeIdentity)
        store.activeIdentity = identity
        _state.value = AuthState(
            isLoggedIn = true,
            userId = result.userId.toString(),
            roles = roles,
            activeIdentity = identity,
        )
    }

    /** 活跃身份必须落在 roles 内：优先保留偏好，否则回退 consumer，再否则 roles.first。 */
    private fun reconcileIdentity(roles: List<String>, preferred: String): String = when {
        roles.contains(preferred) -> preferred
        roles.contains("consumer") -> "consumer"
        else -> roles.firstOrNull() ?: "consumer"
    }
}
