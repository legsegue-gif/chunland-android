package com.chunland.app.core

import android.content.Context
import com.chunland.app.BuildConfig
import com.chunland.app.core.ai.AiRuntime
import com.chunland.app.core.ai.tools.AgentToolRegistry
import com.chunland.app.core.network.apiCall
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * app 层装配：持有 [CoreGraph]（:core 的网络/认证/通用 API）+ 各 feature store + 可选集成。
 *
 * 通用 API 一律委托给 core，保留统一的访问路径（`graph.productApi`）。
 *
 * 全部 lazy：AuthManager ←→ OkHttp 的循环引用靠双向延迟取用解开。
 */
class AppGraph(context: Context) {

    /** 共享基础层（:core）。 */
    val core = CoreGraph(
        context = context,
        baseUrl = BuildConfig.API_BASE_URL,
        debugLogging = BuildConfig.DEBUG,
    )

    // ── 委托：保留既有调用路径，避免全 app 改写 ──
    val appContext get() = core.appContext
    private val appScope get() = core.appScope
    val tokenStore get() = core.tokenStore
    val authManager get() = core.authManager
    val authApi get() = core.authApi
    val productApi get() = core.productApi
    val categoryApi get() = core.categoryApi
    val merchantApi get() = core.merchantApi
    val merchantConsoleApi get() = core.merchantConsoleApi
    val cartApi get() = core.cartApi
    val addressApi get() = core.addressApi
    val regionApi get() = core.regionApi
    val orderApi get() = core.orderApi
    val agentProfileApi get() = core.agentProfileApi
    val paymentApi get() = core.paymentApi
    val feedApi get() = core.feedApi
    val reportApi get() = core.reportApi
    val blockApi get() = core.blockApi
    val configApi get() = core.configApi

    // ── app 专属 ──
    val followStore: FollowStore by lazy { FollowStore(feedApi) }

    val feedEventTracker: FeedEventTracker by lazy { FeedEventTracker(feedApi, appScope) }

    val storeAnchor: StoreAnchorStore by lazy { StoreAnchorStore(appContext) }

    /** 会话属主：登录用 userId，游客独立 "guest" 桶（读路径按属主过滤，绝不外泄他人历史） */
    val aiOwner: () -> String = { authManager.state.value.userId ?: "guest" }

    /**
     * AI 子系统装配点：库 / 凭证 / 来源配置 / 会话注册表全在里面。
     *
     * 工具 handler 走本 graph 的 Retrofit（带本项目 token），与 AI endpoint 的裸 client 永不交叉。
     */
    val aiRuntime: AiRuntime by lazy {
        AiRuntime(
            context = appContext,
            scope = appScope,
            executorFactory = { context ->
                AgentToolRegistry(
                    graph = this,
                    scope = context.scope,
                    suggested = context.tools,
                    // 用闭包而不是快照：身份可能在会话存续期间被切换，
                    // 工具可用集必须跟着变
                    activeIdentity = { authManager.state.value.activeIdentity },
                )
            },
            ownerUserId = aiOwner,
            // 画像片段：只聚合用户自己已有的数据（默认地址 + 常买品类）。
            // 失败静默 —— 拿不到画像不该让整轮对话失败。
            profileFragment = {
                runCatching { apiCall { productApi.profileFragment() }.fragment }.getOrNull()
            },
        )
    }

    // ── 可选集成装配（各自随模块存废）──


    init {
        // 登出（含 401 强制登出）→ 清关注集与 AI 会话（含 scoped），防止跨账号残留。
        // ⚠️ drop(1) 必须有：appScope 是 Main.immediate，StateFlow 的当前值会在本构造函数内
        // 同步发射 —— 全新安装（未登录）时立即走 reset 分支，触发 followStore 等 lazy 初始化，
        // 而它们依赖的 retrofit/api 此刻可能尚未就绪 → 首启即崩
        // 语义上也更对：清理只响应「登出事件」，启动时的初始状态本来就无可清理。
        appScope.launch {
            authManager.state.drop(1).collect {
                if (!it.isLoggedIn) {
                    followStore.reset()
                    aiRuntime.resetForAccountChange()
                }
            }
        }
    }
}
