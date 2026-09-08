package com.chunland.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics

/** 验证码位数（服务端下发的一律 6 位）。过滤与提交校验共用同一个常量。 */
const val OTP_LENGTH = 6

/**
 * 把输入框标记成「短信验证码」，让系统自动填充把收到的码递上来
 * —— 对齐 iOS 的 `.textContentType(.oneTimeCode)`。
 *
 * Compose 1.8 起语义化自动填充默认开启（`ComposeUiFlags.isSemanticAutofillEnabled` 恒 true），
 * 所以标了就会发布给框架，无需额外开关。
 *
 * ⚠️ 这是**声明意图**，不是自己去读短信：能不能真的自动填由设备的自动填充服务/输入法决定
 * （Gboard 会从通知里提取验证码并给出候选；国产 ROM 看各家自带输入法）。
 * 本项目刻意不引 SMS Retriever —— 它依赖 Google Play 服务，与「离线推送后置」同一个理由；
 * 且 Retriever 要求短信正文带 app hash，得改 sms-api 的模板，是服务端侧的另一件事。
 *
 * 邮箱渠道也照标：iOS 三处都是无条件 `.oneTimeCode`，且填的都是同一个 6 位码。
 */
fun Modifier.smsOtpAutofill(): Modifier = semantics { contentType = ContentType.SmsOtpCode }

/**
 * 验证码输入的归一：只留数字、最多 [OTP_LENGTH] 位。
 *
 * 对齐 iOS 三处 `.onChange` 里那段 `v.filter(\.isNumber).prefix(6)`。
 * 抽出来是因为三处原本各写各的 —— 登录页当时压根没做过滤，绑定页没设数字键盘。
 */
fun sanitizeOtp(input: String): String = input.filter { it.isDigit() }.take(OTP_LENGTH)
