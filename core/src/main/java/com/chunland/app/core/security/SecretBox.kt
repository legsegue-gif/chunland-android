package com.chunland.app.core.security

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// MARK: - 本地秘密的落盘加固
//
// ⚠️ 红线：秘密（登录 token / 第三方 AI 密钥）落盘必须是密文。
//
// **这买到的是什么、不是什么**：app 私有目录 + allowBackup=false + 系统 FBE，
// 本来就挡住了「别的 app 读」「进系统备份」「adb backup 拿走」。这一层加固
// 抬高的是 **root / 物理取证** 那一档：从「读一个文件」变成「得以 app 身份
// 跑代码或 hook 进程」。**是纵深，不是硬边界** —— iOS Keychain 在越狱机上同理，
// 别拿它当越权防线，真正的边界在服务端。
//
// **为什么不用 EncryptedSharedPreferences**：androidx.security:security-crypto
// 已于 1.1.0-alpha07 废弃。官方现在指向 DataStore + Tink，而 DataStore 是
// 协程/Flow 的异步 API —— 认证拦截器每个请求都要**同步**读 token，换过去要
// 动整个网络层，代价不成比例。Keystore 直接用，零新依赖。

/**
 * 对称加解密的最小接口。
 *
 * 抽成接口只为一件事：`AndroidKeyStore` 是 framework API，JVM 单测跑不了。
 * 有了这个缝，[SealedStore] 的**策略**（解不开怎么办、加不上密怎么办）才测得到；
 * 真正的 Keystore 路径只能在设备上实测。
 *
 * 两个方法都**以返回 null 表示失败，绝不抛** —— 密钥被系统作废（设备恢复、
 * 锁屏凭据变更等）是正常可达状态，不该让 app 崩。
 */
interface SecretBox {
    fun encrypt(plain: String): String?
    fun decrypt(stored: String): String?
}

/** 键值落盘的最小接口 —— 让 [SealedStore] 的策略脱离 Android 可测。 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)

    /** 已落盘的全部键 —— [SealedStore.sweep] 要靠它找出残留 */
    fun keys(): Set<String>
}

/** [KeyValueStore] 在 SharedPreferences 上的实现。 */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}

/**
 * Android Keystore 里的 AES-256-GCM。密钥不可导出，随 app 卸载消失。
 *
 * 落盘格式：`base64(iv ‖ ciphertext)`，IV 每次由 Cipher 随机生成（GCM 下 IV 复用
 * 会直接毁掉保密性，所以绝不自己造 IV、也绝不固定 IV）。
 */
class KeystoreSecretBox(private val alias: String) : SecretBox {

    override fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: return null)
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "GCM IV 长度异常: ${iv.size}" }
        Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        // 短于一个 IV 的东西不可能是我们写的 —— 多半是旧的明文残留
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key() ?: return null,
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES))
    }.getOrNull()

    // 密钥缓存：每次读 token 都去 Keystore 取一次没必要，而拦截器每个请求都读。
    @Volatile
    private var cached: SecretKey? = null

    private fun key(): SecretKey? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching { loadOrCreate() }.getOrNull()?.also { cached = it }
        }
    }

    private fun loadOrCreate(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        // StrongBox（独立安全芯片）能用就用；很多机型没有，抛了就退回 TEE
        return runCatching { generate(strongBox = true) }.getOrElse { generate(strongBox = false) }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // 刻意不要求用户认证：每次调 AI / 每个请求带 token 都弹指纹是不可用的。
            // 这一层防的是「拿到文件」，不是「拿到解锁的手机」。
            .setUserAuthenticationRequired(false)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * 「秘密只以密文落盘」这条策略的唯一实现处。
 *
 * 三条行为，每条都有具体理由：
 *
 * 1. **解不开就删掉那条**。解不开只有两种可能 —— 加固之前留下的明文残留，
 *    或密钥已被系统作废。两种都已不可用，而**把明文秘密继续留在磁盘上，
 *    正是这次要消除的东西**。所以是删，不是留着装看不见。
 * 2. **加不上密就不落盘**。宁可下次冷启动要求重新登录 / 重填密钥，
 *    也绝不退回写明文 —— 那等于这一层白做。当前进程仍能用（值留在内存缓存里）。
 * 3. **读走内存缓存**。认证拦截器每个请求都读 token，每次走一遍 Keystore
 *    解密不划算。
 */
class SealedStore(
    private val box: SecretBox,
    private val backing: KeyValueStore,
) {
    private val cache = HashMap<String, String?>()

    fun get(key: String): String? = synchronized(cache) {
        if (cache.containsKey(key)) return@synchronized cache[key]
        val stored = backing.get(key)
        val value = if (stored == null) {
            null
        } else {
            box.decrypt(stored) ?: run {
                backing.remove(key)   // 见上「解不开就删掉那条」
                null
            }
        }
        cache[key] = value
        value
    }

    fun set(key: String, value: String?) = synchronized(cache) {
        if (value.isNullOrEmpty()) {
            backing.remove(key)
            cache[key] = null
            return@synchronized
        }
        val sealed = box.encrypt(value)
        if (sealed == null) {
            backing.remove(key)       // 见上「加不上密就不落盘」
        } else {
            backing.put(key, sealed)
        }
        // 落盘失败也让当前进程接着用，只是重启后就没了
        cache[key] = value
    }

    /** 登出 / 清空时调用 —— 缓存必须跟着清，否则清完还能读到旧值 */
    fun forgetAll() = synchronized(cache) { cache.clear() }

    /**
     * 主动扫掉解不开的残留。**构造时必须调，不能等到被读。**
     *
     * 曾经只在 [get] 里清，等于**懒清理** —— access_token 一启动就被读到所以清掉了，
     * 而 refresh_token 只有 401 时才读，那条明文就一直躺在磁盘上。「不落明文」这件事
     * 因此形同虚设。
     *
     * 调用方自己决定扫哪些键：TokenStore 那个 prefs 里还混着 userId / roles /
     * activeIdentity 这些**本来就不加密**的字段，无差别扫会把它们一并删掉。
     */
    fun sweep(keys: Collection<String>) {
        keys.forEach { get(it) }   // get 里已有「解不开就删」，这里只是把它提前触发
    }

    /** 整个 backing 都是秘密时用（如 AI 密钥那份 prefs，每条都是 apikey:*）。 */
    fun sweepAll() = sweep(backing.keys())
}
