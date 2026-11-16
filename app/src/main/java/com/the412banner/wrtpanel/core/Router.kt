package com.the412banner.wrtpanel.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.JsonArray
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A saved router. [url] is scheme://host[:port] without a path. */
data class Router(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val username: String = "root",
    val password: String = "",
    val remember: Boolean = true,
    val pinnedCertSha256: String? = null,
    val lastUsed: Long = 0L,
) {
    val isHttps: Boolean get() = url.startsWith("https://", ignoreCase = true)
    val hostLabel: String get() = url.substringAfter("://")
}

/** Normalises user input like "192.168.1.1", "router.lan:8080/" or "https://10.0.0.1" to scheme://host[:port]. */
fun normalizeRouterUrl(input: String): String {
    var s = input.trim().trimEnd('/')
    if (!s.contains("://")) s = "http://$s"
    val scheme = s.substringBefore("://").lowercase()
    var rest = s.substringAfter("://")
    rest = rest.substringBefore('/')
    return "$scheme://$rest"
}

/** Saved routers; passwords are encrypted with an Android Keystore AES-GCM key. */
class RouterStore(context: Context) {
    private val prefs = context.getSharedPreferences("routers", Context.MODE_PRIVATE)

    fun all(): List<Router> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        val arr = runCatching { JsonCodec.parseToJsonElement(raw) }.getOrNull().arr ?: return emptyList()
        return arr.mapNotNull { e ->
            val id = e["id"].str ?: return@mapNotNull null
            Router(
                id = id,
                name = e["name"].str ?: "",
                url = e["url"].str ?: return@mapNotNull null,
                username = e["username"].str ?: "root",
                password = e["password"].str?.let { decrypt(it) } ?: "",
                remember = e["remember"].bool ?: true,
                pinnedCertSha256 = e["pin"].str,
                lastUsed = e["lastUsed"].long ?: 0L,
            )
        }.sortedByDescending { it.lastUsed }
    }

    fun save(router: Router) {
        val list = all().filter { it.id != router.id } + router
        write(list)
    }

    fun delete(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Router>) {
        val arr = JsonArray(list.map {
            jsonOf(
                "id" to it.id, "name" to it.name, "url" to it.url, "username" to it.username,
                "password" to if (it.remember && it.password.isNotEmpty()) encrypt(it.password) else null,
                "remember" to it.remember, "pin" to it.pinnedCertSha256, "lastUsed" to it.lastUsed,
            )
        })
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.doFinal(plain.toByteArray())
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(enc: String): String? = runCatching {
        val (iv, data) = enc.split(':', limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }.getOrNull()

    private companion object {
        const val KEY_ALIAS = "wrtpanel_router_pw"
    }
}
