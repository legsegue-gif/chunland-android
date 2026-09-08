package com.chunland.app.core

import android.content.Context
import com.chunland.app.core.auth.AuthManager
import com.chunland.app.core.auth.TokenStore
import com.chunland.app.core.network.AuthInterceptor
import com.chunland.app.core.network.BaseUrlInterceptor
import com.chunland.app.core.network.ChunlandJson
import com.chunland.app.core.network.ClientInfoInterceptor
import com.chunland.app.core.network.NormalizingConverterFactory
import com.chunland.app.core.network.ServerConfig
import com.chunland.app.core.network.TokenAuthenticator
import com.chunland.app.data.api.AddressApi
import com.chunland.app.data.api.AgentProfileApi
import com.chunland.app.data.api.AiToolApi
import com.chunland.app.data.api.AuthApi
import com.chunland.app.data.api.BlockApi
import com.chunland.app.data.api.CartApi
import com.chunland.app.data.api.CategoryApi
import com.chunland.app.data.api.ConfigApi
import com.chunland.app.data.api.FeedApi
import com.chunland.app.data.api.MerchantApi
import com.chunland.app.data.api.MerchantConsoleApi
import com.chunland.app.data.api.OrderApi
import com.chunland.app.data.api.PaymentApi
import com.chunland.app.data.api.ProductApi
import com.chunland.app.data.api.RegionApi
import com.chunland.app.data.api.ReportApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * 共享基础装配（网络栈 + 认证 + 通用 API），位于 :core。
 *
 * 为什么独立于 AppGraph：下游模块需要 retrofit 与通用 API，但绝不能反向依赖 :app（会成环）。
 * 依赖方向单向汇聚到这里。
 *
 * 全部 lazy：AuthManager ←→ OkHttp 的循环引用靠双向延迟取用解开。
 *
 * @param serverConfig 基址真相源，由 :app 构造（默认值取自它自己的 BuildConfig）。
 * @param debugLogging 由 :app 从自己的 BuildConfig 传入；true 时挂 OkHttp BASIC 日志。
 */
class CoreGraph(
    context: Context,
    val serverConfig: ServerConfig,
    private val debugLogging: Boolean,
) {
    val appContext: Context = context.applicationContext

    init {
        // 顶层媒体 URL 工具（absoluteMediaUrl / serverOrigin）需要 API 基址；
        // :core 读不到 :app 的 BuildConfig，故在此单向注入。传闭包不传快照 —— 见 MediaConfig 注释。
        com.chunland.app.core.network.MediaConfig.baseUrlProvider = { serverConfig.baseUrl }
    }

    /** app 级长生命周期作用域（登出监听 / 埋点攒批 / 通话状态机共用） */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val tokenStore = TokenStore(appContext)

    val authManager: AuthManager by lazy { AuthManager(tokenStore) { authApi } }

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // 必须第一个：后面的日志拦截器要打真实地址而非哨兵
            .addInterceptor(BaseUrlInterceptor { serverConfig.baseUrl })
            .addInterceptor(ClientInfoInterceptor(appContext))
            .addInterceptor(AuthInterceptor { tokenStore.accessToken })
            .authenticator(TokenAuthenticator { authManager })
            .apply {
                if (debugLogging) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }

    /**
     * 供下游模块创建各自的 API 接口。
     *
     * baseUrl 是**哨兵而非真实地址** —— 真实地址由 [BaseUrlInterceptor] 出站时改写，
     * 这样切服务器无需重建 Retrofit。`.invalid` 是 RFC 2606 保留 TLD，永不解析成真实主机：
     * 万一改写没生效，请求会立刻 UnknownHostException 失败，而不是悄悄打到某个真实域名。
     */
    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(SENTINEL_BASE_URL)
            .client(okHttp)
            .addConverterFactory(NormalizingConverterFactory(ChunlandJson))
            .build()
    }

    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }
    val productApi: ProductApi by lazy { retrofit.create(ProductApi::class.java) }
    val categoryApi: CategoryApi by lazy { retrofit.create(CategoryApi::class.java) }
    val merchantApi: MerchantApi by lazy { retrofit.create(MerchantApi::class.java) }
    val merchantConsoleApi: MerchantConsoleApi by lazy { retrofit.create(MerchantConsoleApi::class.java) }
    val cartApi: CartApi by lazy { retrofit.create(CartApi::class.java) }
    val addressApi: AddressApi by lazy { retrofit.create(AddressApi::class.java) }
    val regionApi: RegionApi by lazy { retrofit.create(RegionApi::class.java) }
    val orderApi: OrderApi by lazy { retrofit.create(OrderApi::class.java) }
    val agentProfileApi: AgentProfileApi by lazy { retrofit.create(AgentProfileApi::class.java) }
    val paymentApi: PaymentApi by lazy { retrofit.create(PaymentApi::class.java) }
    val feedApi: FeedApi by lazy { retrofit.create(FeedApi::class.java) }
    val reportApi: ReportApi by lazy { retrofit.create(ReportApi::class.java) }
    val blockApi: BlockApi by lazy { retrofit.create(BlockApi::class.java) }
    val configApi: ConfigApi by lazy { retrofit.create(ConfigApi::class.java) }
    val aiToolApi: AiToolApi by lazy { retrofit.create(AiToolApi::class.java) }

    private companion object {
        /** 见 [retrofit] 注释。末尾斜杠不能省 —— Retrofit 要求 baseUrl 以 `/` 结尾。 */
        const val SENTINEL_BASE_URL = "http://chunland.invalid/"
    }
}
