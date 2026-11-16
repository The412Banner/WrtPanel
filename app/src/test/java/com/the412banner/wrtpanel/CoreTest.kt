package com.the412banner.wrtpanel

import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.LuciClient
import com.the412banner.wrtpanel.core.Router
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.normalizeRouterUrl
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.ui.MenuNode
import com.the412banner.wrtpanel.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun versions() {
        assertTrue(Updater.compareVersions("v1.0.0", "0.1.0") > 0)
        assertTrue(Updater.compareVersions("1.0.0-pre2", "1.0.0-pre1") > 0)
        assertTrue(Updater.compareVersions("1.0.0", "1.0.0-pre9") > 0)
        assertEquals(0, Updater.compareVersions("v0.1.0", "0.1.0"))
        assertTrue(Updater.compareVersions("0.9.9", "0.10.0") < 0)
    }

    @Test fun urls() {
        assertEquals("http://192.168.1.1", normalizeRouterUrl("192.168.1.1"))
        assertEquals("https://router.lan:8443", normalizeRouterUrl("https://router.lan:8443/cgi-bin/luci/"))
    }

    @Test fun parseEnv() {
        val html = """<script>L = new LuCI({"media":"/luci-static/bootstrap","token":"abc123","sessionid":"ff00","apply_rollback":90,"apply_holdoff":4,"apply_timeout":5,"apply_display":1.5,"nodespec":{"a":{"b":"}"}},"pollinterval":5});</script>"""
        val env = LuciClient.parseEnv(html, "x")
        assertEquals("abc123", env.token)
        assertEquals("ff00", env.sessionId)
        assertEquals(90, env.applyRollback)
        assertEquals(1.5, env.applyDisplay, 0.0)
    }

    @Test fun masks() {
        assertEquals(24, NetTools.maskToPrefix("255.255.255.0", false))
        assertNull(NetTools.maskToPrefix("255.0.255.0", false))
        assertEquals("255.255.255.128", NetTools.prefixToMask(25, false))
        assertEquals(64, NetTools.maskToPrefix("ffff:ffff:ffff:ffff::", true))
    }

    @Test fun uciEdits() {
        val uci = UciStore(LuciClient(Router(name = "t", url = "http://127.0.0.1")))
        val values = JsonCodec.parseToJsonElement(
            """{"lan":{".anonymous":false,".type":"interface",".name":"lan",".index":1,"proto":"static","ipaddr":"192.168.1.1","dns":["1.1.1.1","8.8.8.8"]},
                "cfg01":{".anonymous":true,".type":"route",".name":"cfg01",".index":2,"target":"10.0.0.0/8"}}""",
        ).obj!!
        uci.seed("network", values)
        assertEquals("static", uci.get("network", "lan", "proto"))
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), uci.getList("network", "lan", "dns"))
        assertEquals("cfg01", uci.resolveSid("network", "@route[0]"))
        assertEquals("cfg01", uci.resolveSid("network", "@route[-1]"))

        // Setting the loaded value again is not a change.
        uci.set("network", "lan", "proto", "static")
        assertFalse(uci.hasLocalChanges())
        uci.set("network", "lan", "ipaddr", "10.1.1.1")
        assertEquals("10.1.1.1", uci.get("network", "lan", "ipaddr"))
        assertTrue(uci.hasLocalChanges())
        uci.set("network", "lan", "ipaddr", "192.168.1.1")
        assertFalse(uci.hasLocalChanges())

        uci.unset("network", "lan", "dns")
        assertNull(uci.get("network", "lan", "dns"))
        assertEquals(setOf("dns"), uci.pending().third["network"]?.get("lan"))

        val sid = uci.add("network", "route")
        uci.set("network", sid, "target", "172.16.0.0/12")
        assertEquals(2, uci.sections("network", "route").size)
        uci.remove("network", sid)
        assertEquals(1, uci.sections("network", "route").size)

        uci.remove("network", "cfg01")
        assertEquals(0, uci.sections("network", "route").size)
    }

    @Test fun menuScrub() {
        val menu = JsonCodec.parseToJsonElement(
            """{"children":{"admin":{"title":"Administration","action":{"type":"firstchild"},"satisfied":true,"children":{
                "status":{"title":"Status","order":10,"action":{"type":"firstchild"},"satisfied":true,"children":{
                   "overview":{"title":"Overview","order":1,"action":{"type":"template","path":"admin_status/index"},"satisfied":true}}},
                "vpn":{"title":"VPN","order":70,"action":{"type":"firstchild"},"satisfied":true}}}}}""",
        ).obj!!
        val root = MenuNode.parse(menu)
        val admin = root.find("admin")!!
        assertEquals(listOf("status"), admin.visibleChildren.map { it.name })
        assertEquals("admin/status/overview", root.find("admin/status")!!.resolveTarget(root).path)
    }
}
