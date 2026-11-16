package com.the412banner.wrtpanel.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Values LuCI puts in `L.env` on every page (token, apply timings, paths). */
data class LuciEnv(
    val token: String,
    val sessionId: String,
    val scriptName: String = "/cgi-bin/luci",
    val resource: String = "/luci-static/resources",
    val ubusPath: String = "/ubus/",
    val applyRollback: Int = 90,
    val applyHoldoff: Int = 4,
    val applyTimeout: Int = 5,
    val applyDisplay: Double = 1.5,
    val pollInterval: Int = 5,
)

open class LuciException(message: String, cause: Throwable? = null) : Exception(message, cause)
class AuthException(message: String) : LuciException(message)
class SessionExpiredException : LuciException("Session expired")
class UntrustedCertificateException(val sha256: String, val subject: String) :
    LuciException("The router's certificate is not trusted")

/** A ubus status code other than 0, as LuCI's rpc.js reports it. */
class UbusException(val code: Int, val target: String) : LuciException("${statusText(code)} ($target)") {
    companion object {
        fun statusText(code: Int): String = when (code) {
            0 -> "Command OK"
            1 -> "Invalid command"
            2 -> "Invalid argument"
            3 -> "Method not found"
            4 -> "Resource not found"
            5 -> "No data received"
            6 -> "Permission denied"
            7 -> "Request timeout"
            8 -> "Not supported"
            9 -> "Unspecified error"
            10 -> "Connection lost"
            else -> "Unknown error code $code"
        }
    }
}

data class UbusCall(val obj: String, val method: String, val args: JsonObject = EmptyJson)

/**
 * Talks to a router the way the LuCI web interface does: the LuCI login form, the
 * sysauth cookie, ubus JSON-RPC on /ubus, the dispatcher's uci apply endpoints and the
 * cgi-io upload/download helpers.
 */
class LuciClient(val router: Router) {
    @Volatile var env: LuciEnv? = null
        private set
    val sid: String? get() = env?.sessionId
    private val loginMutex = Mutex()
    private val rpcId = AtomicInteger(1)

    /** Fingerprint of the certificate the server presented on the last TLS handshake. */
    @Volatile var lastPeerSha256: String? = null
        private set

    private val http: OkHttpClient = buildHttp(30)
    private val slowHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(600, TimeUnit.SECONDS).writeTimeout(600, TimeUnit.SECONDS).build() }

    private fun buildHttp(readTimeout: Long): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
        if (router.isHttps) {
            val tm = PinningTrustManager(router.pinnedCertSha256) { lastPeerSha256 = it }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm)
            // Router certificates are usually issued to "OpenWrt", not the address used to reach it;
            // a pinned certificate is trusted for this router regardless of the name.
            b.hostnameVerifier { host, session ->
                val pinned = router.pinnedCertSha256
                if (pinned != null) true
                else javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)
            }
        }
        return b.build()
    }

    val baseUrl: String get() = router.url.trimEnd('/')
    fun url(path: String): String = baseUrl + (if (path.startsWith("/")) path else "/$path")
    fun luciUrl(vararg parts: String): String =
        url((env?.scriptName ?: "/cgi-bin/luci") + "/" + parts.joinToString("/") { it.trim('/') })

    private val cookieName get() = if (router.isHttps) "sysauth_https" else "sysauth_http"
    fun cookieHeader(): String = sid?.let { "$cookieName=$it" } ?: ""

    private suspend fun execute(req: Request, slow: Boolean = false): Response = withContext(Dispatchers.IO) {
        try {
            (if (slow) slowHttp else http).newCall(req).execute()
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            val c = e.cause
            if (c is UntrustedCertificateException) throw c
            var t: Throwable? = e
            while (t != null) { if (t is UntrustedCertificateException) throw t; t = t.cause }
            throw LuciException("TLS handshake failed: ${e.message}", e)
        } catch (e: UntrustedCertificateException) {
            throw e
        } catch (e: IOException) {
            var t: Throwable? = e
            while (t != null) { if (t is UntrustedCertificateException) throw t; t = t.cause }
            throw LuciException(e.message ?: "Network error", e)
        }
    }

    /** Logs in through the LuCI login form and reads L.env (token, apply timings) from the first page. */
    suspend fun login(): LuciEnv = loginMutex.withLock { doLogin() }

    private suspend fun doLogin(): LuciEnv {
        val form = FormBody.Builder()
            .add("luci_username", router.username)
            .add("luci_password", router.password)
            .build()
        val resp = execute(Request.Builder().url(url("/cgi-bin/luci/")).post(form).build())
        val code = resp.code
        val setCookie = resp.headers("Set-Cookie")
        val body = resp.body?.string().orEmpty()
        resp.close()
        val sid = setCookie.firstNotNullOfOrNull { Regex("sysauth(?:_https?)?=([0-9a-fA-F]{16,})").find(it)?.groupValues?.get(1) }
        if (sid == null) {
            if (code == 403 || body.contains("luci_password")) throw AuthException("Invalid username and/or password")
            if (code == 404) throw LuciException("LuCI was not found at ${router.url}")
            throw LuciException("Login failed (HTTP $code)")
        }
        // Fetch a page to read L.env (token etc.). Follow redirects manually so the cookie is kept.
        var location = resp.header("Location") ?: "/cgi-bin/luci/"
        var html = ""
        for (hop in 0 until 6) {
            val target = if (location.startsWith("http")) location else url(location)
            val r = execute(Request.Builder().url(target).header("Cookie", "$cookieName=$sid").get().build())
            val loc = r.header("Location")
            html = r.body?.string().orEmpty()
            r.close()
            if (r.code in 300..399 && loc != null) location = loc else break
        }
        val parsed = parseEnv(html, sid)
        env = parsed
        return parsed
    }

    fun logoutLocal() { env = null }

    suspend fun logout() {
        val e = env ?: return
        runCatching {
            execute(Request.Builder().url(luciUrl("admin", "logout")).header("Cookie", cookieHeader()).get().build()).close()
        }
        if (env === e) env = null
    }

    private suspend fun ensureLogin(): LuciEnv = env ?: login()

    /** One ubus call. Returns `result[1]` (null when the call returned no data). */
    suspend fun call(obj: String, method: String, args: JsonObject = EmptyJson, slow: Boolean = false): JsonElement? {
        return callWithRetry(slow) { e -> rpcOne(e, obj, method, args, slow) }
    }

    /** Like [call] but returns null instead of throwing for ubus status errors (LuCI's L.resolveDefault). */
    suspend fun callOrNull(obj: String, method: String, args: JsonObject = EmptyJson): JsonElement? =
        try { call(obj, method, args) } catch (e: UbusException) { null }

    /** Several ubus calls in one JSON-RPC batch request, like rpc.js does. */
    suspend fun batch(calls: List<UbusCall>): List<Result<JsonElement?>> {
        if (calls.isEmpty()) return emptyList()
        return callWithRetry(false) { e -> rpcBatch(e, calls) }
    }

    /** JSON-RPC "list" (rpc.js rpc.list): all object names, or the method signatures of [objects]. */
    suspend fun list(vararg objects: String): JsonElement? = callWithRetry(false) { e ->
        val body = jsonOf(
            "jsonrpc" to "2.0", "id" to rpcId.getAndIncrement(), "method" to "list",
            "params" to if (objects.isEmpty()) null else objects.toList(),
        )
        val reply = postRpc(e, body, false)
        val err = reply["error"]
        if (err != null) {
            if (err["code"].int == -32002) throw SessionExpiredException()
            throw LuciException(err["message"].str ?: "RPC error")
        }
        reply["result"]
    }

    private suspend fun <T> callWithRetry(slow: Boolean, block: suspend (LuciEnv) -> T): T {
        val e = ensureLogin()
        return try {
            block(e)
        } catch (x: SessionExpiredException) {
            val relog = loginMutex.withLock { if (env === e || env == null) doLogin() else env!! }
            block(relog)
        }
    }

    private fun rpcBody(sid: String, c: UbusCall): JsonObject = jsonOf(
        "jsonrpc" to "2.0", "id" to rpcId.getAndIncrement(), "method" to "call",
        "params" to listOf(sid, c.obj, c.method, c.args),
    )

    private suspend fun postRpc(e: LuciEnv, payload: JsonElement, slow: Boolean): JsonElement {
        val req = Request.Builder()
            .url(url(e.ubusPath))
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        val resp = execute(req, slow)
        val text = resp.body?.string().orEmpty()
        val code = resp.code
        resp.close()
        if (code == 403 || code == 401) throw SessionExpiredException()
        if (code !in 200..299) throw LuciException("ubus HTTP $code")
        return runCatching { JsonCodec.parseToJsonElement(text) }.getOrElse { throw LuciException("Invalid ubus reply") }
    }

    private fun decodeReply(reply: JsonElement, target: String): JsonElement? {
        val err = reply["error"]
        if (err != null) {
            val c = err["code"].int
            if (c == -32002) throw SessionExpiredException()
            throw LuciException(err["message"].str ?: "RPC error $c")
        }
        val result = reply["result"].arr ?: throw LuciException("Invalid ubus reply")
        val status = result[0].int ?: 0
        if (status != 0) throw UbusException(status, target)
        return result.getOrNull(1)
    }

    private suspend fun rpcOne(e: LuciEnv, obj: String, method: String, args: JsonObject, slow: Boolean): JsonElement? {
        val reply = postRpc(e, rpcBody(e.sessionId, UbusCall(obj, method, args)), slow)
        return decodeReply(reply, "$obj.$method")
    }

    private suspend fun rpcBatch(e: LuciEnv, calls: List<UbusCall>): List<Result<JsonElement?>> {
        val bodies = calls.map { rpcBody(e.sessionId, it) }
        val reply = postRpc(e, JsonArray(bodies), false)
        val replies = reply.arr ?: listOf(reply)
        val byId = replies.associateBy { it["id"].int }
        return bodies.mapIndexed { i, b ->
            val r = byId[b["id"].int] ?: return@mapIndexed Result.failure(LuciException("Missing reply"))
            try {
                Result.success(decodeReply(r, "${calls[i].obj}.${calls[i].method}"))
            } catch (x: SessionExpiredException) {
                throw x
            } catch (x: Exception) {
                Result.failure(x)
            }
        }
    }

    /** GET a LuCI dispatcher path (e.g. admin/menu) with the session cookie. */
    suspend fun dispatcherGet(vararg parts: String): Pair<Int, String> {
        val e = ensureLogin()
        val r = execute(Request.Builder().url(luciUrl(*parts)).header("Cookie", "$cookieName=${e.sessionId}").get().build())
        val text = r.body?.string().orEmpty()
        r.close()
        return r.code to text
    }

    /** POST to a LuCI dispatcher path with sid + token form fields, as ui.js does for admin/uci/... */
    suspend fun dispatcherPost(path: String, fields: Map<String, String> = emptyMap(), withSession: Boolean = true, timeoutMs: Long? = null): Pair<Int, String> {
        val e = env
        val fb = FormBody.Builder()
        if (withSession && e != null) { fb.add("sid", e.sessionId); fb.add("token", e.token) }
        fields.forEach { (k, v) -> fb.add(k, v) }
        val b = Request.Builder().url(luciUrl(path)).post(fb.build())
        if (e != null) b.header("Cookie", "$cookieName=${e.sessionId}")
        val client = if (timeoutMs != null) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
        val r = withContext(Dispatchers.IO) {
            try { client.newCall(b.build()).execute() } catch (x: IOException) { throw LuciException(x.message ?: "Network error", x) }
        }
        val text = r.body?.string().orEmpty()
        r.close()
        return r.code to text
    }

    suspend fun menu(): JsonObject {
        val (code, text) = dispatcherGet("admin", "menu")
        if (code != 200) throw LuciException("Menu request failed (HTTP $code)")
        return JsonCodec.parseToJsonElement(text).obj ?: throw LuciException("Invalid menu")
    }

    /** cgi-io upload, as LuCI's file upload widgets use it. Returns the parsed JSON reply. */
    suspend fun upload(remotePath: String, fileName: String, data: RequestBody, mode: String? = null): JsonElement? {
        val e = ensureLogin()
        val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("sessionid", e.sessionId)
            .addFormDataPart("filename", remotePath)
        if (mode != null) mb.addFormDataPart("filemode", mode)
        mb.addFormDataPart("filedata", fileName, data)
        val r = execute(Request.Builder().url(url("/cgi-bin/cgi-upload")).post(mb.build()).build(), slow = true)
        val text = r.body?.string().orEmpty()
        r.close()
        if (r.code != 200) throw LuciException("Upload failed (HTTP ${r.code}): ${text.take(200)}")
        return runCatching { JsonCodec.parseToJsonElement(text) }.getOrNull()
    }

    /** cgi-io download of a router file; the stream must be closed by the caller. */
    suspend fun download(remotePath: String, fileName: String = remotePath.substringAfterLast('/')): Pair<Response, InputStream> {
        val e = ensureLogin()
        val form = FormBody.Builder().add("sessionid", e.sessionId).add("path", remotePath).add("filename", fileName).build()
        val r = execute(Request.Builder().url(url("/cgi-bin/cgi-download")).post(form).build(), slow = true)
        if (r.code != 200) { val t = r.body?.string(); r.close(); throw LuciException("Download failed (HTTP ${r.code}) ${t?.take(120) ?: ""}") }
        return r to (r.body?.byteStream() ?: throw LuciException("Empty download"))
    }

    /** POST to a cgi-io helper (cgi-backup, cgi-exec) with the session id plus extra fields. */
    suspend fun cgiStream(helper: String, fields: Map<String, String>): Pair<Response, InputStream> {
        val e = ensureLogin()
        val fb = FormBody.Builder().add("sessionid", e.sessionId)
        fields.forEach { (k, v) -> fb.add(k, v) }
        val r = execute(Request.Builder().url(url("/cgi-bin/$helper")).post(fb.build()).build(), slow = true)
        if (r.code != 200) { val t = r.body?.string(); r.close(); throw LuciException("$helper failed (HTTP ${r.code}) ${t?.take(120) ?: ""}") }
        return r to (r.body?.byteStream() ?: throw LuciException("Empty reply"))
    }

    /** True when the router answers HTTP at all (used while waiting for a reboot). */
    suspend fun ping(): Boolean = try {
        val r = withContext(Dispatchers.IO) {
            http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url("/")).head().build()).execute()
        }
        r.close(); true
    } catch (e: Exception) { false }

    companion object {
        val JSON_TYPE = "application/json".toMediaType()

        fun parseEnv(html: String, sid: String): LuciEnv {
            val start = html.indexOf("new LuCI(")
            val obj = if (start >= 0) extractJsonObject(html, start + "new LuCI(".length) else null
            val j = obj?.let { runCatching { JsonCodec.parseToJsonElement(it) }.getOrNull() }
            val token = j["token"].str
                ?: Regex("""name="token" value="([0-9a-f]+)"""").find(html)?.groupValues?.get(1)
                ?: ""
            return LuciEnv(
                token = token,
                sessionId = j["sessionid"].str ?: sid,
                scriptName = j["scriptname"].str ?: "/cgi-bin/luci",
                resource = j["resource"].str ?: "/luci-static/resources",
                ubusPath = j["ubuspath"].str ?: "/ubus/",
                applyRollback = j["apply_rollback"].int ?: 90,
                applyHoldoff = j["apply_holdoff"].int ?: 4,
                applyTimeout = j["apply_timeout"].int ?: 5,
                applyDisplay = j["apply_display"].double ?: 1.5,
                pollInterval = j["pollinterval"].int ?: 5,
            )
        }

        /** Returns the balanced {...} starting at or after [from], honouring JSON strings. */
        fun extractJsonObject(s: String, from: Int): String? {
            val begin = s.indexOf('{', from)
            if (begin < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in begin until s.length) {
                val ch = s[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                    continue
                }
                when (ch) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return s.substring(begin, i + 1) }
                }
            }
            return null
        }
    }
}

/** Trusts system CAs, or exactly the pinned certificate; reports the presented certificate's SHA-256. */
class PinningTrustManager(
    private val pinnedSha256: String?,
    private val onPeer: (String) -> Unit,
) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as java.security.KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.first()
        val fp = sha256(leaf.encoded)
        onPeer(fp)
        if (pinnedSha256 != null) {
            if (fp.equals(pinnedSha256, ignoreCase = true)) return
            throw CertificateException("Pinned certificate changed", UntrustedCertificateException(fp, leaf.subjectX500Principal.name))
        }
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw CertificateException("Untrusted", UntrustedCertificateException(fp, leaf.subjectX500Principal.name))
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        fun sha256(b: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString(":") { "%02X".format(it) }
    }
}
