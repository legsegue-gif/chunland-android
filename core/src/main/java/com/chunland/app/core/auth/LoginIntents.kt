package com.chunland.app.core.auth

/**
 * 登录 intent retry（对齐 iOS LoginCoordinator 语义）：游客被拦截的动作在
 * 登录成功后自动续做一次。使用方在唤起登录层前 set；AppRoot 在登录态翻转时
 * consume；用户手动关闭登录层则 clear（放弃续做）。
 *
 * ⚠️ pending 动作必须是「不再检查登录态」的直接执行路径（捕获的 UI 状态是
 * 旧快照，若动作内部再查登录态会拿到过期值造成二次拦截）。
 */
object LoginIntents {
    private var pending: (() -> Unit)? = null

    fun set(action: () -> Unit) {
        pending = action
    }

    fun clear() {
        pending = null
    }

    fun consume(): (() -> Unit)? = pending.also { pending = null }
}
