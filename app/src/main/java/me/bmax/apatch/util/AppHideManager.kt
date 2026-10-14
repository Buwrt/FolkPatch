package me.bmax.apatch.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import me.bmax.apatch.Natives
import java.io.File

/**
 * One-tap hide-list scanner for the XiaoCaiYe helper.
 *
 * It walks every installed package, classifies it with [AppHideRules], lets the
 * user flip individual entries, then writes the result to the two files the
 * hiding stack actually reads:
 *
 *  - /data/adb/tricky_store/target.txt : the attestation spoof target list,
 *    which is what "hidden" means for Play Integrity / keybox flows.
 *  - /data/adb/ap/package_config : the APatch per-uid exclusion list, so the
 *    same apps are also excluded from su for defence in depth.
 */
object AppHideManager {

    private const val TAG = "AppHideManager"
    const val TARGET_FILE = "/data/adb/tricky_store/target.txt"

    data class Entry(
        val pkg: String,
        val label: String,
        val isSystem: Boolean,
        val uid: Int,
        val reason: String,
        /** User override; null means "follow the scanned verdict". */
        var hiddenOverride: Boolean? = null,
        val scannedHidden: Boolean,
    ) {
        val hidden: Boolean get() = hiddenOverride ?: scannedHidden
    }

    /** Hidden-ness before this run, used to report what actually changed. */
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
     * Returns entries sorted hidden-first then by label.
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
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val updSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val decision = AppHideRules.decide(pkg, isSystem || updSystem)
            Entry(
                pkg = pkg,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg),
                isSystem = isSystem || updSystem,
                uid = ai.uid,
                reason = decision.reason,
                scannedHidden = decision.verdict == AppHideRules.Verdict.HIDE,
            )
        }

        // The Tricky Store defaults are always part of the hide list even if a
        // device somehow lacks the package.
        val present = entries.map { it.pkg }.toSet()
        val synthetic = AppHideRules.ALWAYS_HIDE_EXACT.filter { it !in present }.map {
            Entry(
                pkg = it, label = it, isSystem = true, uid = 0,
                reason = "attestation", scannedHidden = true,
            )
        }

        return (entries + synthetic).sortedWith(
            compareByDescending<Entry> { it.hidden }.thenBy { it.label.lowercase() }
        )
    }

    /**
     * Writes [hidden] to the Tricky Store target list and mirrors it into the
     * APatch exclusion list. Must run off the main thread.
     *
     * The payload is staged in the app's private dir first because a root shell
     * cannot read straight out of the APK, and the copy is done with `cp`
     * instead of a shell heredoc to avoid quoting surprises with odd package
     * names.
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
        // set. Failures here are non-fatal: attestation spoofing already works.
        val okExclude = try {
            Natives.su()
            val configs = PkgConfig.readConfigs()
            val byPkg = configs.values.associateBy { it.pkg }.toMutableMap()
            val uids = context.packageManager
            val resolved = HashMap<String, Int>()
            runCatching {
                uids.getInstalledPackages(PackageManager.GET_META_DATA).forEach { pi ->
                    resolved[pi.packageName] = pi.applicationInfo?.uid ?: 0
                }
            }
            val touched = ArrayList<PkgConfig.Config>()
            final.forEach { pkg ->
                val uid = resolved[pkg] ?: return@forEach
                val existing = byPkg[pkg]
                val cfg = existing ?: PkgConfig.Config(
                    pkg, 0, 0, Natives.Profile(uid = uid)
                )
                cfg.profile.uid = uid
                cfg.exclude = 1
                cfg.allow = 0
                touched.add(cfg)
            }
            // Anything previously excluded but now whitelisted must be cleared.
            configs.values.filter { it.exclude == 1 && !final.contains(it.pkg) }
                .forEach { old ->
                    val cleared = PkgConfig.Config(
                        old.pkg, 0, 0, old.profile
                    )
                    touched.add(cleared)
                }
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
}
