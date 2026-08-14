package com.chunland.app.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * 拍照前的权限适配 —— IM 与 AI 共用。
 *
 * ## 为什么需要这个判断
 *
 * `ACTION_IMAGE_CAPTURE`（走系统相机 app）本身**不需要** CAMERA 权限，但 Android 有一条
 * 反直觉的规则：**只要 manifest 声明了 CAMERA，就必须先拿到运行时授权**，否则拍照直接失败。
 *
 * 而 CAMERA 声明是**构建可变的** —— 它随音视频通话能力一并进入 manifest，
 * 未集成通话的构建里根本没有这条声明。于是同一份代码在两种构建下的正确行为相反：
 * - 有声明 → 必须先请求
 * - 无声明 → 不能请求（请求一个未声明的权限会被系统直接拒）
 *
 * 所以只能运行时读 manifest 来决定，不能写死。
 *
 * ⚠️ 放在 `core/` 而不是某个 feature 里：它是平台适配、不是任何一个功能的私有知识，
 * 而且 AI 与会话分处不同 module —— 逻辑若留在会话侧，AI 根本调不到，
 * 只能复制一份，那就成了新的漂移源。
 */
object CameraPermission {

    /** app 是否在 manifest 声明了 CAMERA */
    fun isDeclared(context: Context): Boolean = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.CAMERA) == true
    }.getOrDefault(false)

    /** 已获授权 */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 拍照前是否需要先请求权限。
     * `false` = 直接拍（未声明，或已授权）。
     */
    fun needsRequest(context: Context): Boolean = isDeclared(context) && !isGranted(context)
}
