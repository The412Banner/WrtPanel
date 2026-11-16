package com.the412banner.wrtpanel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.the412banner.wrtpanel.ui.AppRoot
import com.the412banner.wrtpanel.ui.AppViewModel
import com.the412banner.wrtpanel.ui.theme.WrtPanelTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            WrtPanelTheme(mode = vm.settings.themeMode, dynamic = vm.settings.dynamicColor) {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true) vm.showUpdateRequested = true
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}
