package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.BadgeKind
import com.the412banner.wrtpanel.ui.components.StatusBadge
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.components.stripHtml
import kotlinx.serialization.json.JsonElement

/** One entry of the channel / mode / band / width lists ([value, label, { available, no_outdoor }]). */
internal class FreqChoice(val value: String, val label: String, val available: Boolean, val noOutdoor: Boolean = false)

/** The lists CBIWifiFrequencyValue.load() builds from iwinfo freqlist and the radio capabilities. */
internal class FreqModel(
    val channels: Map<String, List<FreqChoice>>,
    val modes: List<FreqChoice>,
    val htmodes: Map<String, List<FreqChoice>>,
    val bands: Map<String, List<FreqChoice>>,
)

internal fun PageContext.buildFreqModel(radio: WifiRadio?, freqlist: List<JsonElement>): FreqModel {
    val acs = feature("hostapd", "acs")
    val auto = { if (acs) mutableListOf(FreqChoice("auto", "auto", true)) else mutableListOf<FreqChoice>() }
    val channels = linkedMapOf("2g" to auto(), "5g" to auto(), "6g" to auto(), "60g" to mutableListOf<FreqChoice>())
    for (freq in freqlist) {
        val b = freq["band"].int ?: continue
        if (b == 0) continue
        val band = "${b}g"
        val available = !(freq["restricted"].bool == true && freq["no_ir"].bool == true)
        val ch = freq["channel"].str ?: continue
        channels.getOrPut(band) { mutableListOf() }.add(FreqChoice(ch, "%d (%d Mhz)".format(freq["channel"].int ?: 0, freq["mhz"].int ?: 0), available, freq["no_outdoor"].bool == true))
    }
    val hw = (radio?.hwModes ?: listOf("b", "g")).toSet()
    val modes = listOf(
        FreqChoice("", "Legacy", "a" in hw || "b" in hw || "g" in hw),
        FreqChoice("n", "N", "n" in hw),
        FreqChoice("ac", "AC", feature("hostapd", "11ac") && "ac" in hw),
        FreqChoice("ax", "AX", feature("hostapd", "11ax") && "ax" in hw),
        FreqChoice("be", "BE", feature("hostapd", "11be") && "be" in hw),
    )
    val ht = (radio?.htModes ?: emptyList()).toSet()
    fun w(vararg m: Pair<String, String>) = m.map { (k, l) -> FreqChoice(k, l, k in ht) }
    val htmodes = mapOf(
        "" to listOf(FreqChoice("", "-", true)),
        "n" to w("HT20" to "20 MHz", "HT40" to "40 MHz"),
        "ac" to w("VHT20" to "20 MHz", "VHT40" to "40 MHz", "VHT80" to "80 MHz", "VHT160" to "160 MHz"),
        "ax" to w("HE20" to "20 MHz", "HE40" to "40 MHz", "HE80" to "80 MHz", "HE160" to "160 MHz"),
        "be" to w("EHT20" to "20 MHz", "EHT40" to "40 MHz", "EHT80" to "80 MHz", "EHT160" to "160 MHz", "EHT320" to "320 MHz"),
    )
    val n2 = channels["2g"]!!.size > 1
    val n5 = channels["5g"]!!.size > 1
    val n6 = channels["6g"]!!.size > 1
    val n60 = channels["60g"]!!.isNotEmpty()
    val bands = mapOf(
        "" to listOf(FreqChoice("2g", "2.4 GHz", n2), FreqChoice("5g", "5 GHz", n5), FreqChoice("60g", "60 GHz", n60)),
        "n" to listOf(FreqChoice("2g", "2.4 GHz", n2), FreqChoice("5g", "5 GHz", n5)),
        "ac" to listOf(FreqChoice("5g", "5 GHz", true)),
        "ax" to listOf(FreqChoice("2g", "2.4 GHz", n2), FreqChoice("5g", "5 GHz", n5), FreqChoice("6g", "6 GHz", n6)),
        "be" to listOf(FreqChoice("2g", "2.4 GHz", n2), FreqChoice("5g", "5 GHz", n5), FreqChoice("6g", "6 GHz", n6)),
    )
    return FreqModel(channels, modes, htmodes, bands)
}

/** The four selects of the widget, with the browser select semantics the JS relies on. */
internal class FreqWidgetState(private val model: FreqModel) {
    class Sel {
        var key: String? = null
        var opts: List<FreqChoice> = emptyList()
        var idx = -1
        val value: String get() = opts.getOrNull(idx)?.value ?: ""
        fun set(v: String?) { idx = opts.indexOfFirst { it.value == v } }
    }

    val mode = Sel()
    val band = Sel()
    val chan = Sel()
    val ht = Sel()
    private val memo = HashMap<String, Int>()
    var useBandOption = true

    /** setValues(): remembers the index per list, shows only available entries. */
    private fun setValues(sel: Sel, key: String, vals: List<FreqChoice>?) {
        sel.key?.let { memo[it] = sel.idx }
        sel.opts = vals?.filter { it.available } ?: emptyList()
        sel.idx = if (sel.opts.isEmpty()) -1 else 0
        if (vals != null) memo[key]?.let { sel.idx = it }
        sel.key = if (vals != null) key else null
    }

    fun toggleMode() { toggleHT(); toggleBand() }
    fun toggleHT() = setValues(ht, "ht:${mode.value}", model.htmodes[mode.value])
    fun toggleBand() { setValues(band, "band:${mode.value}", model.bands[mode.value]); toggleChannel() }
    fun toggleChannel() = setValues(chan, "chan:${band.value}", model.channels[band.value])

    /** setInitialValues() */
    fun init(uci: UciStore, sid: String): FreqWidgetState {
        val htval = uci.getString("wireless", sid, "htmode")
        val hwval = uci.getString("wireless", sid, "hwmode")
        val chval = uci.getString("wireless", sid, "channel")
        val bandval = uci.getString("wireless", sid, "band")
        setValues(mode, "modes", model.modes)
        val h = htval ?: "undefined"
        mode.set(
            when {
                Regex("EHT20|EHT40|EHT80|EHT160|EHT320").containsMatchIn(h) -> "be"
                Regex("HE20|HE40|HE80|HE160").containsMatchIn(h) -> "ax"
                Regex("VHT20|VHT40|VHT80|VHT160").containsMatchIn(h) -> "ac"
                Regex("HT20|HT40").containsMatchIn(h) -> "n"
                else -> ""
            },
        )
        toggleMode()
        if (hwval != null) {
            useBandOption = false
            band.set(if (hwval.contains('a')) "5g" else "2g")
        } else {
            useBandOption = true
            band.set(bandval)
        }
        toggleBand()
        ht.set(htval)
        chan.set(chval ?: chan.opts.firstOrNull()?.value ?: "auto")
        return this
    }

    val formvalue: String get() = "${ht.value}|${band.value}|${chan.value}"
    val restricted: Boolean get() = chan.opts.getOrNull(chan.idx)?.noOutdoor == true
}

internal fun splitFreq(v: String): List<String> = (v.split('|') + listOf("", "", "")).take(3)

/** The `_freq` option (CBIWifiFrequencyValue) of the device section. */
internal fun ValueOption.wifiFrequency(model: FreqModel) {
    fun state(rt: FormRuntime, sid: String): FreqWidgetState =
        rt.data.getOrPut("freq:$sid") { FreqWidgetState(model).init(rt.uci, sid) } as FreqWidgetState

    cfgvalueFn = { rt, sid -> FreqWidgetState(model).init(rt.uci, sid).formvalue }
    writeFn = { rt, sid, v ->
        val (htmode, band, channel) = splitFreq(v.toString())
        rt.uci.set("wireless", sid, "htmode", htmode.ifEmpty { null })
        if (state(rt, sid).useBandOption) rt.uci.set("wireless", sid, "band", band)
        else rt.uci.set("wireless", sid, "hwmode", if (band == "2g") "11g" else "11a")
        rt.uci.set("wireless", sid, "channel", channel)
    }
    render = { rt, sid -> FrequencyWidget(rt, this, sid, state(rt, sid)) }
}

@Composable
private fun FrequencyWidget(rt: FormRuntime, o: ValueOption, sid: String, st: FreqWidgetState) {
    rt.revision.intValue
    val disabled = rt.isDisabled(o, sid)
    val update = { rt.setValue(o, sid, st.formvalue) }
    Column(Modifier.fillMaxWidth()) {
        o.title?.let { Text(stripHtml(it), style = MaterialTheme.typography.bodyLarge) }
        if (st.restricted) {
            Spacer(Modifier.padding(top = 4.dp))
            StatusBadge("Indoor Only Channel Selected", BadgeKind.Warn)
        }
        val fields = listOf(
            Triple("Mode", st.mode) { v: String -> st.mode.set(v); st.toggleMode(); update() },
            Triple("Band", st.band) { v: String -> st.band.set(v); st.toggleBand(); update() },
            Triple("Channel", st.chan) { v: String -> st.chan.set(v); st.toggleChannel(); update() },
            Triple("Width", st.ht) { v: String -> st.ht.set(v); update() },
        ).filter { it.second.opts.size > 1 }
        fields.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, sel, onChange) ->
                    SelectField(
                        value = sel.value, choices = sel.opts.map { it.value to it.label }, label = label,
                        onChange = onChange, modifier = Modifier.weight(1f), enabled = !disabled,
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        o.description?.let {
            Text(htmlToAnnotated(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
