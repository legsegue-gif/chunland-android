# Chunland Android

Chunland 代购平台的 Android 客户端（Kotlin / Jetpack Compose，minSdk 26）。本仓库是自动同步的开源快照。

> **这是一个客户端。** 仓库不含后端 —— 运行需要你自备一个兼容的服务端（参见下文 API 约定）。直接编译可得到完整 UI，但浏览/登录/下单等需连上后端才工作。

## 架构

```
core/    共享基础层：网络栈 / 认证 / 通用 DTO 与 Retrofit 接口 / UI 基件
app/     应用层：各功能页面（发现·店铺·商品·购物车·结算·订单·工作台·商家控制台·AI 助手）
```

依赖方向单向汇聚到 `core`，`app` 依赖 `core`。装配走手工依赖图（规模不到用 DI 框架的程度）：
`CoreGraph`（网络/认证/通用 API，在 `core`）+ `AppGraph`（feature store，在 `app`）。

## 构建

```bash
cp config.properties.example config.properties   # 填入你自己的后端域名等
./gradlew :app:assembleDebug                     # debug APK
./gradlew testDebugUnitTest                      # 单元测试
```

用 Android Studio 直接打开本目录即可。`config.properties` 已 gitignore —— 填你自己的值，请勿提交；各字段说明见 `config.properties.example`。

debug 构建默认连 `http://10.0.2.2:3000/api/v1`（`10.0.2.2` 是模拟器访问宿主机 localhost 的固定地址）；release 域名由 `config.properties` 的 `PROD_API_HOST` 注入 `BuildConfig`，源码不硬编码。

## AI 助手

对接任意 OpenAI 兼容服务 —— 在应用内配置页填入 endpoint 与 key 即可使用。
key 只存应用私有存储，请求直连你配置的服务，不经过其它服务器。

## 未包含的功能

部分功能依赖第三方 SDK 或自建服务，不在本仓库中；相关入口会自动隐藏，不影响其余部分构建与运行。

## API 约定

客户端期望一个 REST 后端，响应信封：

```json
{ "code": 0, "message": "ok", "data": { } }
```

`code === 0` 表示成功。请求体发 camelCase；响应侧的 snake_case 键会在解码前统一归一为 camelCase（`NormalizingConverterFactory`），所以后端两种风格都能接。

自建后端时需要注意几点 —— 客户端把这些判断完全交给了服务端：

- **订单的可执行动作由后端下发**：客户端不内置状态机，操作按钮的可用性完全取自 `order.availableActions`。后端不返回该字段，订单详情页就没有任何可点的操作。
- **金额由后端计算**：客户端不复算费率，下单前调 `POST orders/quote` 取金额分解（含配送/服务费与起送校验）。
- **401 仅用于 token 失效**：客户端收到 401 会自动刷新 token、失败则登出。业务性失败（验证码错误、密码错误等）请用信封里的非零 `code` 返回，否则用户会被无故登出。

## License

MIT，见 [LICENSE](LICENSE)。
