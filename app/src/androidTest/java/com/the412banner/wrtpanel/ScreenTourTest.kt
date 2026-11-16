package com.the412banner.wrtpanel

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.the412banner.wrtpanel.ui.AppViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logs in to the CI test router and opens every page of its menu, saving a screenshot of
 * each to /data/local/tmp/wrtpanel-screens (pulled by the ui-tour workflow).
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val outDir = "/data/local/tmp/wrtpanel-screens"
    private var shot = 0

    private fun sh(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        Thread.sleep(500)
        sh("screencap -p %s/%03d-%s.png".format(outDir, shot++, name.replace(Regex("[^A-Za-z0-9._-]"), "_")))
        Thread.sleep(700)
    }

    private fun vm(): AppViewModel = ViewModelProvider(rule.activity)[AppViewModel::class.java]

    private var lastError: String? = null

    private fun waitText(text: String, ms: Long): Boolean = try {
        rule.waitUntil(ms) { rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        true
    } catch (e: Throwable) {
        lastError = e.toString()
        android.util.Log.e("WRTPANEL-TOUR", "waitText($text) failed", e)
        runCatching { rule.onRoot(useUnmergedTree = true).printToLog("WRTPANEL-TREE") }
        false
    }

    @Test
    fun tour() {
        val url = InstrumentationRegistry.getArguments().getString("routerUrl") ?: "http://10.0.2.2:8088"
        val password = InstrumentationRegistry.getArguments().getString("routerPassword") ?: "wrtpanel"
        sh("mkdir -p $outDir")
        check(waitText("Add router", 30_000)) { screenshot("no-add-router"); "Add router not shown: $lastError" }
        screenshot("routers-empty")
        rule.onNodeWithText("Add router").performClick()
        check(waitText("Save & connect", 10_000)) { "router dialog not shown" }
        rule.onNode(hasSetTextAction() and hasText("Address")).performTextReplacement(url)
        rule.onNode(hasSetTextAction() and hasText("Name (optional)")).performTextReplacement("CI router")
        rule.onNode(hasSetTextAction() and hasText("Password")).performTextReplacement(password)
        screenshot("router-add")
        rule.onNodeWithText("Save & connect").performClick()
        if (!waitText("Unsaved Changes", 1) && !waitText("Overview", 90_000)) {
            screenshot("login-failed"); error("login failed")
        }
        Thread.sleep(4000)
        screenshot("overview")

        val root = vm().session?.menu ?: error("no menu")
        val pages = ArrayList<String>()
        root.find("admin")?.visibleChildren?.forEach { group ->
            group.visibleChildren.forEach { page ->
                val tabs = page.visibleChildren
                if (tabs.size > 1) tabs.forEach { pages += it.path } else pages += page.path
            }
        }
        for (p in pages) {
            rule.runOnUiThread { vm().openRequest = p }
            Thread.sleep(5000)
            screenshot(p.removePrefix("admin/"))
        }
        rule.runOnUiThread { vm().showSettings = true }
        Thread.sleep(1500)
        screenshot("app-settings")
    }
}
