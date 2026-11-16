package com.the412banner.wrtpanel.pages.system

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.form.deviceSelect
import com.the412banner.wrtpanel.ui.PageContext

/** A view/system/led-trigger/<name>.js plugin. */
private class LedTrigger(val name: String, val trigger: String, val description: String, val kernel: Boolean, val addFormOptions: FormSection.() -> Unit)

/** The trigger plugins shipped with luci-mod-system (view/system/led-trigger). */
private val ledTriggers = listOf(
    LedTrigger("default-on", "Always on (kernel: default-on)", "The LED is always in default state on.", true) {},
    LedTrigger(
        "heartbeat", "Heartbeat interval (kernel: heartbeat)",
        "The LED flashes to simulate actual heart beat." + "The frequency is in direct proportion to 1-minute average CPU load.", true,
    ) {
        flag("inverted", "Invert blinking", "When inverted, the LED is continuously lit and flickers instead of it being off by default and blinking on system activity.") {
            rmempty = true; modalonly = true; depends("trigger", "heartbeat")
        }
    },
    LedTrigger("netdev", "Network device activity (kernel: netdev)", "The LED flashes with link status and activity on the configured interface.", true) {
        deviceSelect("_net_dev", "Device", noaliases = true) {
            rmempty = true; ucioption = "dev"; modalonly = true; depends("trigger", "netdev")
        }
        multi("mode", "Trigger Mode") {
            rmempty = true; modalonly = true; depends("trigger", "netdev")
            placeholder = "-- Please choose --"
            value("link", "Link On")
            value("link_10", "Link 10M On")
            value("link_100", "Link 100M On")
            value("link_1000", "Link 1G On")
            value("link_2500", "Link 2.5G On")
            value("link_5000", "Link 5G On")
            value("link_10000", "Link 10G On")
            value("half_duplex", "Half Duplex")
            value("full_duplex", "Full Duplex")
            value("tx", "Transmit")
            value("rx", "Receive")
        }
    },
    LedTrigger("none", "Always off (kernel: none)", "The LED is always in default state off.", true) {
        flag("default", "Default state") {
            rmempty = false; modalonly = true; depends("trigger", "none")
            textvalueFn = { rt, sid ->
                val cval = rt.cfgvalue(this, sid)?.toString() ?: default?.toString()
                if (cval == enabled) "On" else "Off"
            }
        }
    },
    LedTrigger("timer", "Custom flash interval (kernel: timer)", "The LED blinks with the configured on/off frequency", true) {
        value("delayon", "On-State Delay", "How long (in milliseconds) the LED should be on") { modalonly = true; depends("trigger", "timer") }
        value("delayoff", "Off-State Delay", "How long (in milliseconds) the LED should be off") { modalonly = true; depends("trigger", "timer") }
    },
)

/** System › LED Configuration — port of luci-mod-system view/system/leds.js (+ led-trigger plugins). */
@Composable
fun LedsPage(ctx: PageContext) = FormPage(ctx) {
    val leds = call("luci", "getLEDs").obj ?: emptyMap()
    val present = try {
        fs.list("/www" + (client.env?.resource ?: "/luci-static/resources") + "/view/system/led-trigger")
            .filter { it.type == "file" }.mapNotNull { Regex("^(.+)\\.js$").find(it.name)?.groupValues?.get(1) }
    } catch (e: com.the412banner.wrtpanel.core.UbusException) { emptyList() }
    val plugins = present.mapNotNull { n -> ledTriggers.firstOrNull { it.name == n } }
    val triggers = leds.values.flatMap { l -> l["triggers"].arr?.mapNotNull { it.str } ?: emptyList() }

    val m = FormMap(
        "system",
        "<abbr title=\"Light Emitting Diode\">LED</abbr> Configuration",
        "Customizes the behaviour of the device <abbr title=\"Light Emitting Diode\">LED</abbr>s if possible.",
    )
    m.grid("led", null) {
        anonymous = true; addremove = true; sortable = true; nodescriptions = true
        addbtntitle = "Add LED action"

        value("name", "Name")
        list("sysfs", "<abbr title=\"Light Emitting Diode\">LED</abbr> Name") {
            leds.keys.sorted().forEach { value(it) }
        }
        list("trigger", "Trigger") {
            for (p in plugins) {
                if (!p.kernel) value(p.name, p.trigger)
                else if (p.name in triggers) value(p.name, p.trigger)
            }
            val self = this
            render = { rt, sid ->
                val v = rt.formString(self, sid)
                SelectField(
                    value = v, choices = self.choicesFor(rt, sid), label = "Trigger",
                    onChange = { rt.setValue(self, sid, it) },
                    enabled = !rt.isDisabled(self, sid),
                    supporting = plugins.firstOrNull { it.name == v }?.description,
                    error = rt.error(self, sid),
                    optional = self.rmempty || self.optional,
                )
            }
        }
        value("interval", "Interval", "milliseconds") { placeholder = "50" }

        for (p in plugins) p.addFormOptions(this)
    }
    m
}
