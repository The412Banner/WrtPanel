package com.the412banner.wrtpanel.pages.firewall

import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.ui.PageContext

/** The parts of luci-base firewall.js (Defaults, Zone, Forwarding) the firewall views use, on [UciStore]. */
object FwModel {
    private const val C = "firewall"

    private fun parseEnum(s: String?, values: List<String>): String? {
        val u = s?.uppercase() ?: return null
        if (u.isEmpty()) return null
        return values.firstOrNull { it.uppercase().startsWith(u) }
    }

    fun parsePolicy(s: String?, default: String?): String? = parseEnum(s, listOf("DROP", "REJECT", "ACCEPT")) ?: default

    /** new Defaults(): the first defaults section, added when there is none. */
    fun defaultsSid(uci: UciStore): String = uci.firstSid(C, "defaults") ?: uci.add(C, "defaults")

    fun defaultInput(uci: UciStore) = parsePolicy(uci.getString(C, defaultsSid(uci), "input"), "DROP")
    fun defaultOutput(uci: UciStore) = parsePolicy(uci.getString(C, defaultsSid(uci), "output"), "DROP")
    fun defaultForward(uci: UciStore) = parsePolicy(uci.getString(C, defaultsSid(uci), "forward"), "DROP")

    fun zoneName(uci: UciStore, sid: String): String? = uci.getString(C, sid, "name")

    /** lookupZone(name): section id of the zone with this section name or zone name. */
    fun lookupZone(uci: UciStore, name: String?): String? {
        if (name != null) {
            val z = uci.section(C, name)
            if (z != null && z[".type"] == "zone") return z[".name"] as? String
        }
        return uci.sections(C, "zone").firstOrNull { it["name"] as? String == name }?.get(".name") as? String
    }

    /** getZones(): zone section ids sorted by name. */
    fun zones(uci: UciStore): List<String> = uci.sections(C, "zone").mapNotNull { it[".name"] as? String }
        .sortedWith { a, b -> FwTool.naturalCompare(zoneName(uci, a) ?: "", zoneName(uci, b) ?: "") }

    fun zoneNetworks(uci: UciStore, sid: String): List<String> = UciStore.toArray(uci.get(C, sid, "network"))
    fun zoneDevices(uci: UciStore, sid: String): List<String> = UciStore.toArray(uci.get(C, sid, "device"))
    fun zoneSubnets(uci: UciStore, sid: String): List<String> = UciStore.toArray(uci.get(C, sid, "subnet"))

    private fun refersTo(s: Map<String, Any>, name: String): Boolean = s["src"] == name || s["dest"] == name

    /** firewall.deleteZone(name): removes the zone and the rules, redirects and forwardings using it. */
    fun deleteZone(uci: UciStore, nameOrSid: String): Boolean {
        val section = uci.section(C, nameOrSid)
        val isZone = section != null && section[".type"] == "zone"
        val name: String? = if (isZone) section?.get("name") as? String else nameOrSid
        var found = false
        if (isZone) {
            found = true
            uci.remove(C, section?.get(".name") as String)
        } else {
            uci.sections(C, "zone").filter { it["name"] == nameOrSid }.forEach { found = true; uci.remove(C, it[".name"] as String) }
        }
        // An unnamed zone has no references; LuCI would match every section without src or dest here.
        if (found && name != null) {
            uci.sections(C).filter { it[".type"] in setOf("rule", "redirect", "forwarding") && refersTo(it, name) }
                .forEach { uci.remove(C, it[".name"] as String) }
        }
        return found
    }

    /** firewall.renameZone(oldName, newName) */
    fun renameZone(uci: UciStore, oldName: String?, newName: String?): Boolean {
        if (oldName == null || newName == null || !Regex("^[a-zA-Z0-9_]+$").matches(newName)) return false
        if (lookupZone(uci, newName) != null) return false
        var found = false
        uci.sections(C, "zone").filter { it["name"] == oldName }.forEach { uci.set(C, it[".name"] as String, "name", newName); found = true }
        if (found) {
            uci.sections(C).filter { it[".type"] in setOf("rule", "redirect", "forwarding") }.forEach { s ->
                val sid = s[".name"] as String
                if (s["src"] == oldName) uci.set(C, sid, "src", newName)
                if (s["dest"] == oldName) uci.set(C, sid, "dest", newName)
            }
        }
        return found
    }

    /** Zone.getForwardingsBy(what): forwarding section ids whose [what] ("src" / "dest") is [zoneName]. */
    fun forwardingsBy(uci: UciStore, zoneName: String?, what: String): List<String> =
        uci.sections(C, "forwarding").filter { it["src"] != null && it["dest"] != null && it[what] == zoneName }
            .mapNotNull { it[".name"] as? String }

    /** Zone.addForwardingTo(dest) */
    fun addForwardingTo(uci: UciStore, zoneName: String?, dest: String): String? {
        val fwds = forwardingsBy(uci, zoneName, "src")
        val zsid = lookupZone(uci, dest) ?: return null
        val zname = zoneName(uci, zsid)
        if (zname == zoneName) return null
        if (fwds.any { uci.getString(C, it, "dest") == zname }) return null
        val sid = uci.add(C, "forwarding")
        uci.set(C, sid, "src", zoneName)
        uci.set(C, sid, "dest", zname)
        return sid
    }

    /** Zone.addForwardingFrom(src) */
    fun addForwardingFrom(uci: UciStore, zoneName: String?, src: String): String? {
        val fwds = forwardingsBy(uci, zoneName, "dest")
        val zsid = lookupZone(uci, src) ?: return null
        val zname = zoneName(uci, zsid)
        if (zname == zoneName) return null
        if (fwds.any { uci.getString(C, it, "src") == zname }) return null
        val sid = uci.add(C, "forwarding")
        uci.set(C, sid, "src", zname)
        uci.set(C, sid, "dest", zoneName)
        return sid
    }

    /** Zone.addNetwork(network) */
    fun zoneAddNetwork(uci: UciStore, zoneSid: String, network: String): Boolean {
        val s = uci.section("network", network)
        if (s == null || s[".type"] != "interface") return false
        val nets = zoneNetworks(uci, zoneSid)
        if (network in nets) return false
        uci.set(C, zoneSid, "network", nets + network)
        return true
    }

    /** fwtool.checkLegacySNAT() */
    fun checkLegacySNAT(uci: UciStore): Boolean =
        uci.sections(C, "redirect").any { (it["target"] as? String ?: "").lowercase() == "snat" }

    /** fwtool.handleMigration(): "redirect" sections with target SNAT become "nat" sections, then save and apply. */
    suspend fun handleMigration(ctx: PageContext) {
        val uci = ctx.uci
        val mapping = mapOf("dest" to "src", "reflection" to null, "reflection_src" to null, "src_dip" to "snat_ip", "src_dport" to "snat_port", "src" to null)
        for (red in uci.sections(C, "redirect")) {
            if ((red["target"] as? String ?: "").lowercase() != "snat") continue
            val sid = uci.add(C, "nat")
            for ((opt, v) in red) {
                if (opt.startsWith(".")) continue
                if (mapping.containsKey(opt) && mapping[opt] == null) continue
                uci.set(C, sid, mapping[opt] ?: opt, v)
            }
            uci.remove(C, red[".name"] as String)
        }
        uci.save()
        ctx.session.changes.afterSave()
        ctx.session.changes.apply(checked = false)
    }

    /** handleClone(section_id, put_next = true): copies the section right after itself and stages it. */
    suspend fun cloneSection(ctx: PageContext, type: String, sid: String) {
        val uci = ctx.uci
        val src = uci.section(C, sid) ?: return
        val nsid = uci.add(C, type)
        src.forEach { (k, v) -> if (!k.startsWith(".")) uci.set(C, nsid, k, v) }
        uci.move(C, nsid, sid, after = true)
        uci.save()
        ctx.session.changes.afterSave()
    }
}
