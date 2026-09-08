package com.chunland.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.chunland.app.core.AppGraph
import com.chunland.app.core.logging.AiDebugFileLog

class ChunlandApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // AI 对话调试日志：debug 源集才有实现，release 是空壳（见 AiDebugFileLog 注释）
        AiDebugFileLog.install(this)

    }

    // 全局 Coil 单例（AsyncImage 默认取这里）：显式注册 OkHttp 网络抓取器，
    // 内存/磁盘缓存与请求去重用 Coil 默认配置（对应 iOS CachedAsyncImage 的角色）
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}

val Context.appGraph: AppGraph
    get() = (applicationContext as ChunlandApp).graph
