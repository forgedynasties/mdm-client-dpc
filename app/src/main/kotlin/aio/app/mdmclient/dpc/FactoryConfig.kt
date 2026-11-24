package aio.app.mdmclient.dpc

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

/**
 * Enrollment baked into a vendor's system image (vendor kit, 0.16.0): the server URL and an
 * enrollment token in a small `key=value` file the vendor's build copies to one of
 * [PATHS]. A device that comes out of the factory with it enrolls by itself on first boot:
 * no adb, no QR, no typing.
 *
 * Read only while the agent has nothing of its own yet (no device key, no token, never
 * unenrolled), so a device that was moved to another server or re-enrolled by hand never
 * goes back to the factory's choice. Apps can read these partitions' `etc` (system_file),
 * so no SELinux change is needed on the vendor side.
 */
object FactoryConfig {

    private const val TAG = "FactoryConfig"

    val PATHS = listOf(
        "/system/etc/aio-mdm/aio-mdm.conf",
        "/system_ext/etc/aio-mdm/aio-mdm.conf",
        "/product/etc/aio-mdm/aio-mdm.conf",
        "/odm/etc/aio-mdm/aio-mdm.conf",
        "/vendor/etc/aio-mdm/aio-mdm.conf",
    )

    /** The file found in the image, or null. */
    fun file(): File? = PATHS.asSequence().map(::File).firstOrNull { runCatching { it.canRead() }.getOrDefault(false) }

    /** `key=value` lines; `#` starts a comment; blanks ignored. */
    fun read(): Map<String, String> {
        val f = file() ?: return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { raw ->
                val line = raw.substringBefore('#').trim()
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
        }.onFailure { Log.w(TAG, "read ${f.path} failed: ${it.message}") }.getOrDefault(emptyMap())
    }

    /**
     * Seed [AgentConfig] from the image when this device has never been set up. Returns true
     * when it put an enrollment token in place (the caller then enrolls).
     */
    fun seedIfFresh(config: AgentConfig): Boolean {
        if (config.unenrolled) return false
        if (config.apiKey.isNotBlank() || config.enrollToken.isNotBlank() || config.lastEnrollToken.isNotBlank()) return false
        val kv = read()
        val server = kv["server_url"].orEmpty()
        val token = kv["enroll_token"].orEmpty()
        if (server.isBlank() || token.isBlank()) return false
        config.serverUrl = server
        config.enrollToken = token
        Log.i(TAG, "factory enrollment from ${file()?.path} → $server")
        return true
    }

    /** True when this copy of the agent came with the system image (vendor preinstall). */
    fun preinstalled(ctx: Context): Boolean =
        (ctx.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
