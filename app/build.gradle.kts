import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// config.properties（gitignored）：生产域名 / 签名 keystore 等真实配置，源码不硬编码。
val config = Properties().apply {
    val f = rootProject.file("config.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun cfg(key: String): String? = config.getProperty(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.chunland.app"
    compileSdk = 36

    defaultConfig {
        // 真实 applicationId 走 config.properties 注入（不入库）；缺省用通用占位 id，
        // clone 下来直接可构建。namespace（源码包名）刻意不随动。
        applicationId = cfg("APPLICATION_ID") ?: "com.chunland.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // release 签名：keystore 与口令走 config.properties。未配置时回退 debug 签名（仅本地验证用）。
        val storePath = cfg("RELEASE_STORE_FILE")
        if (storePath != null && rootProject.file(storePath).exists()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = cfg("RELEASE_STORE_PASSWORD")
                keyAlias = cfg("RELEASE_KEY_ALIAS")
                keyPassword = cfg("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // 模拟器里 10.0.2.2 = 宿主机的 localhost。默认连本地开发服务端（3000）；
            // 需要指向别的地址时在 config.properties 设 DEBUG_API_BASE_URL，无需改代码。
            val debugUrl = cfg("DEBUG_API_BASE_URL") ?: "http://10.0.2.2:3000/api/v1"
            buildConfigField("String", "API_BASE_URL", "\"$debugUrl\"")
        }
        release {
            val host = cfg("PROD_API_HOST") ?: "your-server.example.com"
            buildConfigField("String", "API_BASE_URL", "\"https://$host/api/v1\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // 共享基础层。三方依赖经 :core 的 api(...) 传递。
    implementation(project(":core"))

    // 可选集成 —— 移除某行即下线对应模块，:app 照常编译。
    // 移除后 :app 若仍引用该模块符号 → **编译期报错**（这正是分 module 的意义）。

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // 远程图片统一 Coil（对应 iOS CachedAsyncImage：内存+磁盘缓存+去重），禁止手写加载
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
}
