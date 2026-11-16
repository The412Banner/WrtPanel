package com.the412banner.wrtpanel

import android.app.Application
import com.the412banner.wrtpanel.core.AppSettings
import com.the412banner.wrtpanel.update.Updater

class WrtPanelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.ensureChannel(this)
        Updater.schedule(this, AppSettings(this).autoUpdateCheck)
    }
}
