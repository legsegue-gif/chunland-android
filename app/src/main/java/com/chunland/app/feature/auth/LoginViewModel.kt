package com.chunland.app.feature.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chunland.app.core.auth.AuthManager
import com.chunland.app.core.network.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginViewModel(private val auth: AuthManager) : ViewModel() {

    /** "sms" | "email" */
    var channel by mutableStateOf("sms")
    /** "otp" 验证码（主路径）| "password" 密码登录 */
    var mode by mutableStateOf("otp")
    var target by mutableStateOf("")
    var code by mutableStateOf("")
    var password by mutableStateOf("")
    var cooldown by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    private var tickerJob: Job? = null

    fun sendCode() {
        if (busy || cooldown > 0) return
        viewModelScope.launch {
            busy = true
            try {
                val result = auth.sendOtp(channel, target.trim())
                startCooldown(result.cooldown)
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                busy = false
            }
        }
    }

    fun login() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                // 成功后 AuthManager.state 翻转，AppRoot 自动切到主页，这里无需回调
                if (mode == "password") {
                    auth.loginWithPassword(channel, target.trim(), password)
                } else {
                    auth.loginWithOtp(channel, target.trim(), code.trim())
                }
            } catch (e: Exception) {
                toast = e.userMessage
            } finally {
                busy = false
            }
        }
    }

    private fun startCooldown(seconds: Int) {
        cooldown = seconds
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (cooldown > 0) {
                delay(1_000)
                cooldown--
            }
        }
    }
}
