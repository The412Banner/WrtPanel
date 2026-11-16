package com.the412banner.wrtpanel.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FsEntry(val name: String, val type: String, val size: Long, val mode: Int, val mtime: Long) {
    val isDir: Boolean get() = type == "directory"
}

data class ExecResult(val code: Int, val stdout: String, val stderr: String)

/** The ubus `file` object and cgi-io helpers, mirroring LuCI's fs.js. */
class Fs(private val client: LuciClient) {
    suspend fun list(path: String): List<FsEntry> =
        client.call("file", "list", jsonOf("path" to path))["entries"].arr?.map {
            FsEntry(it["name"].str ?: "", it["type"].str ?: "file", it["size"].long ?: 0, it["mode"].int ?: 0, it["mtime"].long ?: 0)
        } ?: emptyList()

    suspend fun stat(path: String): FsEntry? = try {
        val r = client.call("file", "stat", jsonOf("path" to path))
        FsEntry(r["path"].str ?: path, r["type"].str ?: "file", r["size"].long ?: 0, r["mode"].int ?: 0, r["mtime"].long ?: 0)
    } catch (e: UbusException) { null }

    suspend fun read(path: String): String = client.call("file", "read", jsonOf("path" to path))["data"].str ?: ""

    suspend fun readOrNull(path: String): String? = try { read(path) } catch (e: UbusException) { null }

    suspend fun write(path: String, data: String, mode: Int? = null) {
        client.call("file", "write", jsonOf("path" to path, "data" to data, "mode" to mode))
    }

    suspend fun remove(path: String) { client.call("file", "remove", jsonOf("path" to path)) }

    suspend fun exec(command: String, params: List<String> = emptyList(), env: Map<String, String>? = null): ExecResult {
        val r = client.call("file", "exec", jsonOf("command" to command, "params" to params.ifEmpty { null }, "env" to env), slow = true)
        return ExecResult(r["code"].int ?: 0, r["stdout"].str ?: "", r["stderr"].str ?: "")
    }

    /** fs.exec_direct: runs through /cgi-bin/cgi-exec and returns the raw output (no size limit). */
    suspend fun execDirect(command: String, params: List<String> = emptyList(), stderr: Boolean = false): String {
        val esc = { s: String -> s.replace("\\", "\\\\").replace(Regex("(\\s)"), "\\\\$1") }
        val cmd = (listOf(esc(command)) + params.map(esc)).joinToString(" ")
        val (resp, stream) = client.cgiStream("cgi-exec", mapOf("command" to cmd, "stderr" to if (stderr) "1" else "0"))
        return withContext(Dispatchers.IO) { stream.use { it.readBytes().decodeToString() }.also { resp.close() } }
    }

    /** fs.read_direct through /cgi-bin/cgi-download. */
    suspend fun readDirect(path: String): ByteArray {
        val (resp, stream) = client.download(path)
        return withContext(Dispatchers.IO) { stream.use { it.readBytes() }.also { resp.close() } }
    }
}
