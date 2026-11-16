package com.the412banner.wrtpanel.pages.software

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.text.Collator
import java.util.Locale

/** A package record as package-manager.js keeps it (parseList / parseApkQueryJson). */
internal class Pkg(val name: String) {
    var version: String? = null
    var description: String? = null
    var size: Long? = null
    var installsize: Long? = null
    var depends: List<String>? = null
    var provides: List<String>? = null
    var status: List<String>? = null
    var missing = false
}

internal class PkgList {
    val pkgs = LinkedHashMap<String, Pkg>()
    val providers = HashMap<String, MutableList<Pkg>>()
}

internal class PkgDb(val available: PkgList, val installed: PkgList, val languages: List<String>)

internal fun isPkgInstalled(pkg: Pkg?): Boolean = pkg?.status?.contains("installed") == true

/** JS truthiness of a numeric field (0 / NaN / undefined are false). */
internal fun Long?.nz(): Long? = this?.takeIf { it != 0L }

private val contRe = Regex("^\\s(.*)$")
private val keyRe = Regex("^([\\w-]+)\\s*:(.+)$")
private val commaRe = Regex("\\s*,\\s*")
private val wsRe = Regex("\\s+")

/** opkg control-file lists (/usr/lib/opkg/status, Packages lists). */
internal fun parseList(s: String, dest: PkgList) {
    var pkg: Pkg? = null
    var key: String? = null
    var value: String? = null
    var start = 0
    while (true) {
        val nl = s.indexOf('\n', start)
        if (nl < 0) break
        val line = s.substring(start, nl)
        start = nl + 1

        val cont = contRe.matchEntire(line)
        if (cont != null) {
            if (pkg != null && key != null && value != null) value += "\n" + cont.groupValues[1].trim()
            continue
        }

        if (key != null && value != null) {
            val p = pkg
            when (key) {
                "package" -> pkg = Pkg(value)
                "depends", "provides" -> {
                    val list = value.split(commaRe)
                    if (p != null && (list.size != 1 || list[0].isNotEmpty())) {
                        if (key == "depends") p.depends = list else p.provides = list
                    }
                }
                "installed-time" -> {}
                "installed-size" -> p?.installsize = value.toLongOrNull()
                "status" -> {
                    val stat = value.split(wsRe)
                    if (stat.getOrNull(2) == "installed") p?.status = listOf("installed")
                }
                "essential" -> {}
                "size" -> p?.size = value.toLongOrNull()
                "architecture", "auto-installed", "filename", "sha256sum", "section" -> {}
                "version" -> p?.version = value
                "description" -> p?.description = value
                else -> {}
            }
            key = null
            value = null
        }

        val km = keyRe.matchEntire(line.trim())
        if (km != null) {
            key = km.groupValues[1].lowercase()
            value = km.groupValues[2].trim()
        } else if (pkg != null) {
            dest.pkgs[pkg.name] = pkg
            val provides = if (dest.providers[pkg.name] != null) mutableListOf() else mutableListOf(pkg.name)
            pkg.provides?.let { provides.addAll(it) }
            for (pr in provides) dest.providers.getOrPut(pr) { mutableListOf() }.add(pkg)
        }
    }
}

/** `apk query --format json` output, read as a stream; only the fields package-manager.js uses are kept. */
internal fun parseApkQueryJson(reader: Reader, dest: PkgList) {
    val items = ArrayList<Pkg>()
    try {
        val r = JsonReader(reader)
        r.isLenient = true
        if (r.peek() != JsonToken.BEGIN_ARRAY) return
        r.beginArray()
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            r.beginObject()
            var name: String? = null
            var version: String? = null
            var description: String? = null
            var size: Long? = null
            var depends: List<String>? = null
            var provides: List<String>? = null
            var status: List<String>? = null
            while (r.hasNext()) {
                when (r.nextName()) {
                    "name" -> name = jsonString(r)
                    "version" -> version = jsonString(r)
                    "description" -> description = jsonString(r)
                    "file-size" -> size = jsonString(r)?.toDoubleOrNull()?.toLong()
                    "depends" -> depends = jsonStringList(r)
                    "provides" -> provides = jsonStringList(r)
                    "status" -> status = jsonStringList(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            val pkg = Pkg(name ?: continue)
            pkg.version = version
            pkg.description = description
            pkg.size = size
            pkg.depends = depends
            pkg.provides = provides
            pkg.status = status
            items += pkg
        }
        r.endArray()
    } catch (e: Exception) {
        // JSON.parse() failed: no packages, like the view.
        return
    }
    for (pkg in items) {
        dest.pkgs[pkg.name] = pkg
        val provides = listOf(pkg.name) + (pkg.provides ?: emptyList())
        for (p0 in provides) {
            val p = p0.substringBefore('=')
            val l = dest.providers.getOrPut(p) { mutableListOf() }
            if (!l.contains(pkg)) l.add(pkg)
        }
    }
}

private fun jsonString(r: JsonReader): String? = when (r.peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
    JsonToken.BOOLEAN -> r.nextBoolean().toString()
    JsonToken.NULL -> { r.nextNull(); null }
    else -> { r.skipValue(); null }
}

private fun jsonStringList(r: JsonReader): List<String>? {
    if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return null }
    val out = ArrayList<String>()
    r.beginArray()
    while (r.hasNext()) jsonString(r)?.let { out += it }
    r.endArray()
    return out
}

/** parsePackageData(): JSON (apk) when the output starts with '[', else opkg control lists. */
internal fun parsePackageData(input: InputStream, dest: PkgList) {
    val buf = BufferedInputStream(input, 64 * 1024)
    buf.mark(8192)
    var first = -1
    for (i in 0 until 8192) {
        val b = buf.read()
        if (b < 0) break
        if (!Character.isWhitespace(b)) { first = b; break }
    }
    buf.reset()
    if (first < 0) return
    if (first == '['.code) parseApkQueryJson(InputStreamReader(buf, Charsets.UTF_8), dest)
    else parseList(buf.readBytes().decodeToString(), dest)
}

private val nonDigits = Regex("[^0-9]+")

internal fun compareVersion(v: String?, ref: String?): Int {
    val a = (v ?: "").split(nonDigits).filter { it.isNotEmpty() }
    val b = (ref ?: "").split(nonDigits).filter { it.isNotEmpty() }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrNull(i)?.toDouble() ?: 0.0
        val y = b.getOrNull(i)?.toDouble() ?: 0.0
        if (x > y) return 1
        if (x < y) return -1
    }
    return 0
}

internal fun versionSatisfied(ver: String?, ref: String?, vop: String?): Boolean {
    val r = compareVersion(ver, ref)
    return when (vop) {
        "<", "<=" -> r <= 0
        ">", ">=" -> r >= 0
        "<<" -> r < 0
        ">>" -> r > 0
        "=" -> r == 0
        else -> false
    }
}

private val hashRe = Regex("\\b(([a-f0-9]{8})[a-f0-9]{24,32})\\b")

/** truncateVersion(): long hashes shortened to 8 characters plus an ellipsis. */
internal fun truncateVersion(v: String?, op: String? = null): String {
    val t = hashRe.replaceFirst(v ?: "", "$2…")
    return if (op == null || op == "=") t else "$op $t"
}

internal fun truncateVersionHtml(v: String): String = hashRe.replaceFirst(v, "<span data-tooltip=\"$1\">$2…</span>")

internal fun escHtml(s: String): String = buildString(s.length) {
    for (c in s) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
        '"' -> append("&#34;"); '\'' -> append("&#39;")
        else -> append(c)
    }
}

/** LuCI's '%1024mB' size format. */
internal fun fmtM(value: Double): String {
    val units = arrayOf(" ", " K", " M", " G", " T", " P", " E")
    var v = value
    var i = 0
    while (i < units.size && v > 1024) { v /= 1024; i++ }
    if (i > 0) return String.format(Locale.ROOT, "%.2f", v) + units[i.coerceAtMost(units.size - 1)] + "iB"
    val n = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    return "$n B"
}

internal fun fmtM(value: Long): String = fmtM(value.toDouble())

// ---------------------------------------------------------------- package table rows (display())

internal enum class PmMode { Available, Installed, Updates }
internal enum class I18nFilter { Lang, All, None }
internal enum class RowBtn { Install, Upgrade, Remove, Installed }

internal class PkgRow(
    val name: String,
    val version: String,
    val sizeText: String,
    val desc: String,
    val btn: RowBtn,
    /** Cell values as LuCI hands them to ui.Table (used for column sorting). */
    val keys: Array<String>,
)

internal fun filterPattern(filter: String): Regex? =
    if (filter.isNotEmpty()) Regex(Regex.escape(filter), RegexOption.IGNORE_CASE) else null

private fun markup(s: String, pattern: Regex?): String =
    if (pattern == null) s else pattern.replace(s) { "<ins>${it.value}</ins>" }

internal fun buildRows(db: PkgDb, mode: PmMode, filter: String, i18n: I18nFilter): List<PkgRow> {
    val src = if (mode == PmMode.Available) db.available else db.installed
    val pattern = filterPattern(filter)
    val i18nFilter: Regex? = when (i18n) {
        I18nFilter.All -> Regex("^luci-i18n-")
        I18nFilter.Lang -> runCatching { Regex("^luci-i18n-(base-.+|.+-(" + db.languages.joinToString("|") + "))$") }.getOrNull()
        I18nFilter.None -> null
    }
    val rows = ArrayList<PkgRow>()

    for ((name, pkg) in src.pkgs) {
        var desc = pkg.description ?: ""
        var altsize: Long? = null
        val avail = db.available.pkgs[name]
        if (pkg.size.nz() == null && avail != null) altsize = avail.size
        if (desc.isEmpty() && avail != null) desc = avail.description ?: ""
        val lines = desc.split("\n")
        desc = lines[0].trim() + if (lines.size > 1) "…" else ""

        if (pattern != null && !pattern.containsMatchIn(name) && !pattern.containsMatchIn(desc)) continue
        if (name.startsWith("luci-i18n-") && (i18nFilter == null || !i18nFilter.containsMatchIn(name))) continue

        val btn: RowBtn
        val ver: String
        val verHtml: String
        when (mode) {
            PmMode.Updates -> {
                val inst = db.installed.pkgs[name]
                if (!isPkgInstalled(inst)) continue
                if (avail == null || compareVersion(avail.version, pkg.version) <= 0) continue
                ver = "${truncateVersion(pkg.version ?: "-")} » ${truncateVersion(avail.version ?: "-")}"
                verHtml = "${truncateVersionHtml(pkg.version ?: "-")} » ${truncateVersionHtml(avail.version ?: "-")}"
                btn = RowBtn.Upgrade
            }
            PmMode.Installed -> {
                if (!isPkgInstalled(pkg)) continue
                ver = truncateVersion(pkg.version ?: "-")
                verHtml = truncateVersionHtml(pkg.version ?: "-")
                btn = RowBtn.Remove
            }
            PmMode.Available -> {
                val inst = db.installed.pkgs[name]
                ver = truncateVersion(pkg.version ?: "-")
                verHtml = truncateVersionHtml(pkg.version ?: "-")
                btn = when {
                    !isPkgInstalled(inst) -> RowBtn.Install
                    inst?.version != pkg.version -> RowBtn.Upgrade
                    else -> RowBtn.Installed
                }
            }
        }

        val sz = pkg.size.nz()
        val alt = altsize.nz()
        val sizeText = if (sz != null) fmtM(sz) else if (alt != null) "~" + fmtM(alt) else "-"
        val shownDesc = desc.ifEmpty { "-" }
        rows += PkgRow(
            name = name, version = ver, sizeText = sizeText, desc = shownDesc, btn = btn,
            keys = arrayOf(
                markup(escHtml(name), pattern),
                verHtml,
                "${sz ?: alt ?: 0},$sizeText",
                markup(escHtml(shownDesc), pattern),
            ),
        )
    }
    rows.sortWith { a, b -> a.keys[0].compareTo(b.keys[0]) }
    return rows
}

// ---------------------------------------------------------------- column sorting (ui.Table)

private val numKeyRe = Regex("^(\\d+)\\b(\\D*)$")

internal fun sortKey(value: String): String {
    val m = numKeyRe.find(value) ?: return value
    return "%010d%s".format(Locale.ROOT, m.groupValues[1].toBigInteger(), m.groupValues[2])
}

/** Intl.Collator(undefined, { numeric: true }).compare */
internal object NaturalCompare : Comparator<String> {
    private val collator: Collator = Collator.getInstance()
    private val token = Regex("\\d+|\\D+")

    override fun compare(a: String, b: String): Int {
        val ta = token.findAll(a).map { it.value }.toList()
        val tb = token.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ta.size, tb.size)) {
            val x = ta[i]; val y = tb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0'); val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            } else collator.compare(x, y)
            if (r != 0) return r
        }
        return ta.size.compareTo(tb.size)
    }
}

// ---------------------------------------------------------------- dependencies (renderDependencies)

internal enum class LabelKind { Plain, Notice, Warning }

internal class DepLabel(val text: String, val kind: LabelKind, val tooltip: String? = null)

internal class DepAlt(val text: String, val tooltip: String?, val label: DepLabel)

internal class DepNode(val alts: List<DepAlt>, val children: List<DepNode>)

internal class DepInfo {
    val errors = mutableListOf<String>()
    val install = mutableListOf<Pkg>()
    val seen = HashMap<String, Boolean>()
}

private val depRe = Regex("^([^><=~\\s]+)\\s*\\(?([><=~]+)?\\s*([^)]+)?\\)?$")

internal class DepResolver(private val db: PkgDb) {
    private fun pkgStatus(pkg: Pkg, vop: String?, ver: String?, info: DepInfo): DepLabel {
        if (isPkgInstalled(pkg)) {
            if (vop != null && !versionSatisfied(pkg.version, ver, vop)) {
                val repl = (db.available.providers[pkg.name] ?: emptyList()).firstOrNull { versionSatisfied(it.version, ver, vop) }
                if (repl != null) {
                    info.install += repl
                    return DepLabel("Needs upgrade", LabelKind.Plain, "Requires update to ${repl.name} ${repl.version ?: ""}")
                }
                info.errors += "The installed version of package <em>${escHtml(pkg.name)}</em> is not compatible, require ${escHtml(truncateVersion(ver, vop))} while ${escHtml(truncateVersion(pkg.version))} is installed."
                return DepLabel("Version incompatible", LabelKind.Warning, "Require version $vop ${ver ?: ""},\ninstalled ${pkg.version ?: ""}")
            }
            return DepLabel("Installed", LabelKind.Notice)
        } else if (!pkg.missing) {
            if (vop == null || versionSatisfied(pkg.version, ver, vop)) {
                info.install += pkg
                return DepLabel("Not installed", LabelKind.Plain)
            }
            info.errors += "The repository version of package <em>${escHtml(pkg.name)}</em> is not compatible, require ${escHtml(truncateVersion(ver, vop))} but only ${escHtml(truncateVersion(pkg.version))} is available."
            return DepLabel("Version incompatible", LabelKind.Warning, "Require version $vop ${ver ?: ""},\ninstalled ${pkg.version ?: ""}")
        } else {
            info.errors += "Required dependency package <em>${escHtml(pkg.name)}</em> is not available in any repository."
            return DepLabel("Not available", LabelKind.Warning)
        }
    }

    private fun item(name: String, pkgNames: List<String>, vop: String?, ver: String?, info: DepInfo, flat: Boolean): DepNode {
        val alts = mutableListOf<DepAlt>()
        val depends = mutableListOf<String>()
        for (n in pkgNames) {
            val pkg = db.installed.pkgs[n] ?: db.available.pkgs[n] ?: Pkg(name)
            var text = pkg.name
            val isz = pkg.installsize.nz()
            val sz = pkg.size.nz()
            if (isz != null) text += " (${fmtM(isz)})" else if (sz != null) text += " (~${fmtM(sz)})"
            alts += DepAlt(text, pkg.description, pkgStatus(pkg, vop, ver, info))
            pkg.depends?.forEach { d -> if (!depends.contains(d)) depends += d }
        }
        if (alts.isEmpty()) alts += DepAlt(name, null, pkgStatus(Pkg(name).also { it.missing = true }, vop, ver, info))
        val children = if (!flat) render(depends, info, false) ?: emptyList() else emptyList()
        return DepNode(alts, children)
    }

    /** Returns null when there is nothing to list (LuCI renders no <ul>). */
    fun render(depends: List<String>?, info: DepInfo, flat: Boolean): List<DepNode>? {
        val items = mutableListOf<DepNode>()
        for (d in depends ?: emptyList()) {
            if (d == "libc") continue
            val m = depRe.find(d)
            val dep: String
            val vop: String?
            val ver: String?
            if (m != null) {
                dep = m.groupValues[1].trim()
                vop = m.groupValues[2].trim().ifEmpty { null }
                ver = m.groupValues[3].trim().ifEmpty { null }
            } else {
                dep = d.trim(); vop = null; ver = null
            }
            if (info.seen[dep] == true) continue
            val pkgs = mutableListOf<String>()
            db.installed.providers[dep]?.forEach { if (!pkgs.contains(it.name)) pkgs += it.name }
            db.available.providers[dep]?.forEach { if (!pkgs.contains(it.name)) pkgs += it.name }
            info.seen[dep] = true
            items += item(dep, pkgs, vop, ver, info, flat)
        }
        return items.ifEmpty { null }
    }
}

/** Everything handleInstall() computes for the "Details for package" dialog. */
internal class InstallInfo(
    val pkg: Pkg,
    val sizeText: String,
    val deps: List<DepNode>?,
    val errors: List<String>,
    val totalsize: Long,
    val totalpkgs: Int,
    val i18nPackages: List<String>,
    val i18nTree: List<DepNode>?,
    val suggestsize: Long,
)

private val luciNameRe = Regex("^luci-([^-]+)-(.+)$")

internal fun installInfo(db: PkgDb, pkg: Pkg): InstallInfo {
    val resolver = DepResolver(db)
    val sizeText = pkg.installsize.nz()?.let { "~${fmtM(it)} installed" }
        ?: pkg.size.nz()?.let { "~${fmtM(it)} compressed" }
        ?: "unknown"
    val depcache = DepInfo()
    val deps = resolver.render(pkg.depends, depcache, false)

    var totalsize = pkg.installsize.nz() ?: pkg.size.nz() ?: 0L
    var totalpkgs = 1
    for (ip in depcache.install) { totalsize += ip.installsize.nz() ?: ip.size.nz() ?: 0L; totalpkgs++ }

    val luci = luciNameRe.find(pkg.name)
    val i18nPackages = mutableListOf<String>()
    var i18nTree: List<DepNode>? = null
    var suggestsize = 0L
    if (luci != null && (luci.groupValues[1] != "i18n" || luci.groupValues[2].startsWith("base-"))) {
        var filter: Regex? = null
        if (luci.groupValues[1] == "i18n") {
            val basenames = db.installed.pkgs.keys.mapNotNull { n -> luciNameRe.find(n)?.takeIf { it.groupValues[1] != "i18n" }?.groupValues?.get(2) }
            if (basenames.isNotEmpty()) {
                filter = runCatching { Regex("^luci-i18n-(" + basenames.joinToString("|") + ")-" + pkg.name.removePrefix("luci-i18n-base-") + "$") }.getOrNull()
            }
        } else {
            filter = runCatching { Regex("^luci-i18n-" + luci.groupValues[2] + "-(" + db.languages.joinToString("|") + ")$") }.getOrNull()
        }
        if (filter != null) {
            for (n in db.available.pkgs.keys) if (n != pkg.name && filter.containsMatchIn(n)) i18nPackages += n
            val i18ncache = DepInfo()
            i18nTree = resolver.render(i18nPackages, i18ncache, true)
            for (ip in i18ncache.install) suggestsize += ip.installsize.nz() ?: ip.size.nz() ?: 0L
        }
    }
    return InstallInfo(pkg, sizeText, deps, depcache.errors, totalsize, totalpkgs, i18nPackages, i18nTree, suggestsize)
}
