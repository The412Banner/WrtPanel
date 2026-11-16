package com.the412banner.wrtpanel

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.the412banner.wrtpanel.ui.AppViewModel
import com.the412banner.wrtpanel.ui.MenuNode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logs in to the CI test router and opens every page of its menu, saving a screenshot of
 * each to /data/local/tmp/wrtpanel-screens (pulled by the ui-tour workflow).
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(inst)
    private val outDir = "/data/local/tmp/wrtpanel-screens"
    private var shot = 0

    private fun sh(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).close()
    }

    /** Writes the UI hierarchy to the shell-owned output dir (the app process cannot write there). */
    private fun dump(name: String) {
        runCatching {
            val buf = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(buf)
            val fds = inst.uiAutomation.executeShellCommandRw("sh -c 'cat > $outDir/$name.xml'")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(buf.toByteArray()) }
            fds[0].close()
            fds.getOrNull(2)?.close()
        }
    }

    /** Dismisses system "isn't responding" dialogs that slow CI emulators show. */
    private fun dismissSystemDialogs() {
        for (label in listOf("Wait", "Close app", "OK")) {
            device.findObject(By.text(label).pkg("android"))?.click()
        }
    }

    private fun waitText(text: String, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            dismissSystemDialogs()
            if (device.hasObject(By.text(text)) || device.hasObject(By.desc(text))) return true
            Thread.sleep(500)
        }
        return false
    }

    private fun screenshot(name: String) {
        Thread.sleep(400)
        val file = "%s/%03d-%s.png".format(outDir, shot++, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        sh("screencap -p $file")
        Thread.sleep(600)
    }

    @Test
    fun tour() {
        val url = InstrumentationRegistry.getArguments().getString("routerUrl") ?: "http://10.0.2.2:8088"
        val password = InstrumentationRegistry.getArguments().getString("routerPassword") ?: "wrtpanel"
        sh("mkdir -p $outDir")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        device.waitForIdle(5000)
        Thread.sleep(3000)
        screenshot("start")
        dump("start")
        val found = waitText("Add router", 40_000)
        if (!found) { screenshot("no-add-router"); dump("no-add-router") }
        assertTrue("Add router not shown", found)
        screenshot("routers-empty")
        (device.findObject(By.text("Add router")) ?: device.findObject(By.desc("Add router"))).click()
        device.wait(Until.hasObject(By.text("Save & connect")), 10_000)
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        fields[0].text = url
        fields[1].text = "CI router"
        fields[3].text = password
        screenshot("router-add")
        device.findObject(By.text("Save & connect")).click()
        val loggedIn = waitText("Overview", 90_000)
        if (!loggedIn) { screenshot("login-failed"); dump("login-failed") }
        assertTrue("login failed", loggedIn)
        Thread.sleep(4000)
        screenshot("overview")

        var menu: MenuNode? = null
        scenario.onActivity { a -> menu = ViewModelProvider(a)[AppViewModel::class.java].session?.menu }
        val root = menu ?: error("no menu")
        val pages = ArrayList<String>()
        root.find("admin")?.visibleChildren?.forEach { group ->
            group.visibleChildren.forEach { page ->
                val tabs = page.visibleChildren
                if (tabs.size > 1) tabs.forEach { pages += it.path } else pages += page.path
            }
        }
        for (p in pages) {
            scenario.onActivity { a -> ViewModelProvider(a)[AppViewModel::class.java].openRequest = p }
            Thread.sleep(5000)
            screenshot(p.removePrefix("admin/"))
        }

        // Navigation drawer and the app settings
        scenario.onActivity { a -> ViewModelProvider(a)[AppViewModel::class.java].openRequest = pages.first() }
        Thread.sleep(2000)
        device.findObject(By.desc("Menu"))?.click()
        Thread.sleep(1500)
        screenshot("drawer")
        device.pressBack()
        scenario.onActivity { a -> ViewModelProvider(a)[AppViewModel::class.java].showSettings = true }
        Thread.sleep(1500)
        screenshot("app-settings")
    }
}
