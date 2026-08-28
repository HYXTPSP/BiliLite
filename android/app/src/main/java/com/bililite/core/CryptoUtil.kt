// ============================================================================
// BiliLite — 凭据加密(v0.4.17 安全加固)
// Android Keystore 生成 AES-256-GCM 密钥(硬件安全模块内,不可导出),
// 用于加密 SharedPreferences 中的登录凭证(SESSDATA/bili_jct)。
// 密文格式:Base64(iv[12B] + ciphertext+tag[16B])
// ============================================================================
package com.bililite.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtil {
    private const val KS_ALIAS = "bililite_master_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    /** 懒加载/生成 Keystore 内的 AES 密钥(首次调用生成,之后复用) */
    private fun key(): SecretKey = synchronized(this) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        return gen.generateKey()
    }

    /** 加密明文,返回 Base64(iv+ciphertext)。空串原样返回;失败返回空串(调用方需容错)。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Crypto", "加密失败: ${e.message}")
            ""
        }
    }

    /** 解密 encrypt() 的输出。失败返回空串(密钥更换/数据损坏时视为未登录)。 */
    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            val all = Base64.decode(enc, Base64.NO_WRAP)
            if (all.size <= IV_LEN) return ""
            val iv = all.copyOfRange(0, IV_LEN)
            val ct = all.copyOfRange(IV_LEN, all.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            com.bililite.core.BiliLog.e("Crypto", "解密失败: ${e.message}")
            ""
        }
    }
}
