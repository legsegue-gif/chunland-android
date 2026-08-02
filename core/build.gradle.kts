import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core —— 共享基础层（网络 / 认证 / 通用 DTO 与 API / UI 基件）。
// **绝不反向依赖 :app** —— 依赖只能单向朝这里汇聚。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chunland.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// api（非 implementation）：下游模块直接消费这些类型（Retrofit 接口、
// Compose 组件、DTO），传递暴露是有意为之。
dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.viewmodel.compose)

    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    api(libs.compose.icons.extended)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    api(libs.navigation.compose)

    api(libs.retrofit)
    api(libs.okhttp)
    api(libs.okhttp.logging)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
