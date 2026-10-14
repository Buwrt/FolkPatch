package me.bmax.apatch.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import me.bmax.apatch.Natives
import java.io.File

/**
 * One-tap hide-list scanner for the XiaoCaiYe helper.
 *
 * Rules follow the Hide-My-Applist decision shape: the resolved app list is
 * interpreted either as a blacklist (hide what is listed) or a whitelist (hide
 * what is not listed), with system apps optionally exempt. The result is
 * written to the Tricky Store target list and mirrored into the APatch
 * exclusion list.
 */
object AppHideManager {

    private const val TAG = "AppHideManager"
    const val TARGET_FILE = "/data/adb/tricky_store/target.txt"

    /** Mirrors HMA's AppConfig: one work mode plus the resolved package list. */
    data class Config(
        var useWhitelist: Boolean = false,
        var excludeSystemApps: Boolean = true,
        val appList: MutableSet<String> = LinkedHashSet(),
    )

    data class Entry(
        val pkg: String,
        val label: String,
        val isSystem: Boolean,
        val uid: Int,
        val reason: String,
        val kind: AppHideRules.Kind,
        /** User override; null means "follow the scanned verdict". */
        var hiddenOverride: Boolean? = null,
        val scannedHidden: Boolean,
    ) {
        val hidden: Boolean get() = hiddenOverride ?: scannedHidden
    }

    private fun currentTargets(): Set<String> = try {
        val f = File(TARGET_FILE)
        if (f.canRead()) {
            f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } else emptySet()
    } catch (e: Exception) {
        Log.w(TAG, "cannot read $TARGET_FILE", e)
        emptySet()
    }

    /**
     * Scans installed packages on the calling (IO) thread.
     *
     * Only [AppHideRules.Kind.DETECTOR] entries default to hidden: those are the
     * apps that actually request an attestation. Root tooling is scanned and
     * shown but stays visible, because putting it in target.txt does nothing.
     */
    fun scan(context: Context): List<Entry> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledPackages failed", e)
            emptyList()
        }

        val entries = installed.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val pkg = pi.packageName ?: return@mapNotNull null
            val system = isSystemApp(ai)
            val decision = AppHideRules.classify(pkg, system)
            Entry(
                pkg = pkg,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg),
                isSystem = system,
                uid = ai.uid,
                reason = decision.reason,
                kind = decision.kind,
                scannedHidden = decision.kind == AppHideRules.Kind.DETECTOR,
            )
        }

        // Attestation targets that must be present even when the package is not.
        val present = entries.map { it.pkg }.toSet()
        val synthetic = listOf(
            "android", "com.google.android.gms",
            "com.google.android.gsf", "com.android.vending",
        ).filter { it !in present }.map {
            Entry(
                pkg = it, label = it, isSystem = true, uid = 0,
                reason = "attestation", kind = AppHideRules.Kind.DETECTOR,
                scannedHidden = true,
            )
        }

        return (entries + synthetic).sortedWith(
            compareByDescending<Entry> { it.hidden }.thenBy { it.label.lowercase() }
        )
    }

    private fun isSystemApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** Package to uid map, needed to mirror the hide list into APatch. */
    private fun uidMap(context: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        runCatching {
            context.packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
                .forEach { pi ->
                    pi.applicationInfo?.let { out[pi.packageName] = it.uid }
                }
        }
        return out
    }

    /**
     * Writes [hidden] to the Tricky Store target list and mirrors it into the
     * APatch exclusion list. Must run off the main thread.
     */
    fun apply(context: Context, hidden: Set<String>, onLine: (String) -> Unit): Boolean {
        val previous = currentTargets()
        val final = hidden.toSortedSet()

        val staged = File(context.filesDir, "hide_target.txt").apply {
            writeText(final.joinToString("\n") + "\n")
        }

        val result = withNewRootShell {
            newJob().add(
                "mkdir -p /data/adb/tricky_store",
                "cp -f ${staged.absolutePath} $TARGET_FILE",
                "chmod 0644 $TARGET_FILE",
            ).exec()
        }
        if (!result.isSuccess) {
            onLine("failed to write $TARGET_FILE")
            return false
        }
        onLine("target.txt: ${final.size} package(s)")

        // Mirror into the APatch exclusion list so su is denied for the same
        // set. Non-fatal: attestation spoofing already works without it.
        val okExclude = try {
            Natives.su()
            val configs = PkgConfig.readConfigs()
            val byPkg = configs.values.associateBy { it.pkg }.toMutableMap()
            val uids = uidMap(context)
            val touched = ArrayList<PkgConfig.Config>()
            final.forEach { pkg ->
                val uid = uids[pkg] ?: return@forEach
                val cfg = byPkg[pkg] ?: PkgConfig.Config(pkg, 0, 0, Natives.Profile(uid = uid))
                cfg.profile.uid = uid
                cfg.exclude = 1
                cfg.allow = 0
                touched.add(cfg)
            }
            configs.values.filter { it.exclude == 1 && !final.contains(it.pkg) }
                .forEach { old -> touched.add(PkgConfig.Config(old.pkg, 0, 0, old.profile)) }
            if (touched.isNotEmpty()) PkgConfig.updateConfigs(touched)
            true
        } catch (e: Exception) {
            Log.w(TAG, "exclude mirror failed", e)
            onLine("exclude mirror skipped: ${e.message}")
            false
        }
        if (okExclude) onLine("package_config: exclusion list synced")

        val added = final - previous
        val removed = previous - final
        onLine("added ${added.size}, removed ${removed.size}")
        return true
    }

    /**
     * Imports an HMA config.json and returns the protected target apps.
     *
     * HMA's scope keys are the apps it shields from applist detection, which is
     * the same population that needs a spoofed attestation here. The template
     * packages (root traces) are deliberately not returned.
     */
    fun importHma(context: Context, uri: Uri, onLine: (String) -> Unit): Set<String>? {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
            if (text.isNullOrBlank()) {
                onLine("empty file")
                return null
            }
            val targets = AppHideRules.importHmaScope(text)
            if (targets == null) {
                onLine("not an HMA config (no scope field)")
                return null
            }
            val templates = AppHideRules.importHmaTemplatePackages(text)
            if (targets.isEmpty()) {
                onLine("config has no scope entries")
                return null
            }
            onLine("imported ${targets.size} target app(s)")
            if (templates.isNotEmpty()) {
                onLine("${templates.size} root-trace package(s) kept visible")
            }
            targets
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            onLine("import failed: ${e.message}")
            null
        }
    }
}
