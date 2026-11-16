package com.the412banner.wrtpanel.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.the412banner.wrtpanel.ui.theme.ThemeMode

/** App preferences (not router settings). Compose-observable. */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(runCatching { ThemeMode.valueOf(p.getString("theme", "System")!!) }.getOrDefault(ThemeMode.System))
        private set
    var dynamicColor by mutableStateOf(p.getBoolean("dynamic", false))
        private set
    var autoUpdateCheck by mutableStateOf(p.getBoolean("auto_update", true))
        private set
    var includePrereleases by mutableStateOf(p.getBoolean("prerelease", false))
        private set
    /** Refresh interval for status pages in seconds; 0 = LuCI's pollinterval. */
    var pollSeconds by mutableStateOf(p.getInt("poll", 0))
        private set

    var lastUpdateCheck: Long
        get() = p.getLong("last_check", 0)
        set(v) { p.edit().putLong("last_check", v).apply() }

    var notifiedTag: String?
        get() = p.getString("notified_tag", null)
        set(v) { p.edit().putString("notified_tag", v).apply() }

    fun updateTheme(m: ThemeMode) { themeMode = m; p.edit().putString("theme", m.name).apply() }
    fun updateDynamic(b: Boolean) { dynamicColor = b; p.edit().putBoolean("dynamic", b).apply() }
    fun updateAutoCheck(b: Boolean) { autoUpdateCheck = b; p.edit().putBoolean("auto_update", b).apply() }
    fun updatePrereleases(b: Boolean) { includePrereleases = b; p.edit().putBoolean("prerelease", b).apply() }
    fun updatePoll(s: Int) { pollSeconds = s; p.edit().putInt("poll", s).apply() }
}
