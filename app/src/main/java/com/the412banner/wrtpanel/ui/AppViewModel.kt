package com.the412banner.wrtpanel.ui

import android.app.Application
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.the412banner.wrtpanel.core.AppSettings
import com.the412banner.wrtpanel.core.AuthException
import com.the412banner.wrtpanel.core.LuciClient
import com.the412banner.wrtpanel.core.Router
import com.the412banner.wrtpanel.core.RouterStore
import com.the412banner.wrtpanel.core.UntrustedCertificateException
import com.the412banner.wrtpanel.update.AppRelease
import com.the412banner.wrtpanel.update.Updater
import kotlinx.coroutines.launch
import java.net.Inet4Address

sealed interface ConnectResult {
    data object Ok : ConnectResult
    data class Failed(val message: String) : ConnectResult
    data class Untrusted(val sha256: String, val subject: String) : ConnectResult
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    private val store = RouterStore(app)
    val updater = Updater(app)

    var routers by mutableStateOf(store.all())
        private set
    var session by mutableStateOf<RouterSession?>(null)
        private set
    var connectingId by mutableStateOf<String?>(null)
        private set
    var showSettings by mutableStateOf(false)
    var showUpdateRequested by mutableStateOf(false)
    var availableUpdate by mutableStateOf<AppRelease?>(null)
        private set
    var updateCheckError by mutableStateOf<String?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set

    init {
        if (settings.autoUpdateCheck && System.currentTimeMillis() - settings.lastUpdateCheck > 6 * 3600_000L) checkForUpdate(false)
    }

    fun saveRouter(r: Router) { store.save(r); routers = store.all() }
    fun deleteRouter(id: String) { store.delete(id); routers = store.all() }

    /** The phone's current default gateway (IPv4), used to pre-fill the router address. */
    fun detectGateway(): String? = runCatching {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }.getOrNull()

    suspend fun connect(r: Router): ConnectResult {
        connectingId = r.id
        try {
            val client = LuciClient(r)
            client.login()
            val s = RouterSession(r, client)
            s.loadBasics()
            val updated = r.copy(lastUsed = System.currentTimeMillis(), password = if (r.remember) r.password else "")
            store.save(updated)
            routers = store.all()
            session = s
            return ConnectResult.Ok
        } catch (e: UntrustedCertificateException) {
            return ConnectResult.Untrusted(e.sha256, e.subject)
        } catch (e: AuthException) {
            return ConnectResult.Failed(e.message ?: "Invalid username and/or password")
        } catch (e: Exception) {
            return ConnectResult.Failed(e.message ?: e.toString())
        } finally {
            connectingId = null
        }
    }

    fun disconnect(logout: Boolean = true) {
        val s = session ?: return
        session = null
        if (logout) viewModelScope.launch { runCatching { s.client.logout() } }
    }

    fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        updateCheckError = null
        viewModelScope.launch {
            try {
                val rel = updater.latest(settings.includePrereleases)
                settings.lastUpdateCheck = System.currentTimeMillis()
                availableUpdate = rel?.takeIf { updater.isNewer(it) }
                if (manual && availableUpdate != null) showUpdateRequested = true
                if (manual && availableUpdate == null) updateCheckError = if (rel == null) "No releases published yet" else "WrtPanel is up to date"
            } catch (e: Exception) {
                if (manual) updateCheckError = e.message ?: "Update check failed"
            } finally {
                checkingUpdate = false
            }
        }
    }
}
