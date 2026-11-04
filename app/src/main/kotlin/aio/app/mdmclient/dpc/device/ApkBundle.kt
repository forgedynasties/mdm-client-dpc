package aio.app.mdmclient.dpc.device

import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * Split APK bundles (0.15.0, 4 Nov 2026): an .xapk (APKPure), .apks (bundletool, SAI) or
 * .apkm (APKMirror) is a ZIP of an app's APKs: the base, config splits (screen density,
 * language, CPU type) and feature splits. The server hands the file over as it is; this
 * picks the parts to install in one session.
 */
object ApkBundle {
    private val ABI_SPLIT = Regex("""^(?:split_)?config\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)\.apk$""", RegexOption.IGNORE_CASE)

    /**
     * The entries to install, or null when [file] is a plain APK. Every APK at the top of
     * the ZIP goes in, except CPU-type splits for other CPUs: of those only the device's
     * best one. Throws [IllegalArgumentException] for a ZIP that is neither, and for a
     * bundle with expansion files (OBB), which go to shared storage this app can't write.
     */
    fun parts(file: File): List<String>? {
        val names = try {
            ZipFile(file).use { z -> z.entries().asSequence().map { it.name }.toList() }
        } catch (e: Exception) {
            throw IllegalArgumentException("not an APK or an APK bundle: ${e.message}")
        }
        if ("AndroidManifest.xml" in names) return null
        if (names.any { it.startsWith("Android/obb/", true) || it.endsWith(".obb", true) }) {
            throw IllegalArgumentException("the bundle carries expansion files (OBB), which the MDM can't install")
        }
        val apks = names.filter { it.endsWith(".apk", true) && '/' !in it }
        require(apks.isNotEmpty()) { "no APKs inside: is it a split APK bundle?" }
        val abiSplits = apks.mapNotNull { n -> ABI_SPLIT.find(n)?.let { n to it.groupValues[1].lowercase() } }.toMap()
        if (abiSplits.isEmpty()) return apks
        val have = abiSplits.values.toSet()
        val best = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }.firstOrNull { it in have }
            ?: throw IllegalArgumentException("the bundle has no build for this device's CPU (${Build.SUPPORTED_ABIS.joinToString()})")
        return apks.filter { n -> abiSplits[n]?.let { it == best } ?: true }
    }
}
