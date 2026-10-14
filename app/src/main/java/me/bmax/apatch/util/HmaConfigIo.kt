package me.bmax.apatch.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import java.io.File

/**
 * Reads and writes Hide-My-Applist's config.json through a root shell.
 *
 * /data/data is mode 700, so the file can only be reached as root. Reads go
 * through `cat`; writes land in the app's private dir first and are then copied
 * into place, with the owner and mode restored so HMA can still read its own
 * file afterwards.
 */
object HmaConfigIo {

    private const val TAG = "HmaConfigIo"

    /** Attempts to detect whether HMA is installed. */
    fun isHmaInstalled(): Boolean {
        return withNewRootShell {
            newJob().add("[ -d /data/data/${HmaConfig.PKG} ] && echo yes").exec().isSuccess
        }
    }

    /** Reads the config file. Returns null when it is missing or unreadable. */
    fun read(): HmaConfig.Config? {
        val lines = ArrayList<String>()
        val stdout = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(lines::add) }
        }
        val stderr = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { Log.w(TAG, "read: $it") }
        }
        val result = withNewRootShell {
            newJob().add("cat ${HmaConfig.PATH}").to(stdout, stderr).exec()
        }
        if (!result.isSuccess || lines.isEmpty()) return null
        return HmaConfig.parse(lines.joinToString("\n"))
    }

    /**
     * Writes the config back.
     *
     * The owner is taken from the HMA data dir rather than hardcoded, and the
     * mode is restored to 600, otherwise a root-owned copy would leave HMA
     * unable to read its own config.
     */
    fun write(context: Context, config: HmaConfig.Config, onLine: (String) -> Unit): Boolean {
        val staged = File(context.filesDir, "hma_config.json").apply {
            writeText(config.toJson())
        }

        val lines = ArrayList<String>()
        val stdout = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(lines::add) }
        }
        val stderr = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let { lines.add(it) } }
        }

        val result = withNewRootShell {
            newJob().add(
                "[ -f ${HmaConfig.PATH} ] && cp -f ${HmaConfig.PATH} ${HmaConfig.PATH}.bak || true",
                "cp -f ${staged.absolutePath} ${HmaConfig.PATH}",
                "uid=\$(stat -c %u /data/data/${HmaConfig.PKG})",
                "chown \$uid ${HmaConfig.PATH}",
                "chmod 600 ${HmaConfig.PATH}",
                "ls -l ${HmaConfig.PATH}",
            ).to(stdout, stderr).exec()
        }
        lines.forEach(onLine)
        if (!result.isSuccess) {
            onLine("failed to write ${HmaConfig.PATH}")
            return false
        }
        onLine("config.json updated")
        return true
    }

    /** Installed packages with their labels and system flag. */
    data class Installed(
        val pkg: String,
        val label: String,
        val isSystem: Boolean,
    )

    fun installedApps(context: Context): List<Installed> {
        val pm = context.packageManager
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.e(TAG, "getInstalledPackages failed", e)
            emptyList()
        }
        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val pkg = pi.packageName ?: return@mapNotNull null
            Installed(
                pkg = pkg,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg),
                isSystem = isSystemApp(ai),
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun isSystemApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /**
     * One-tap fill: sort installed apps into HMA's two sides.
     *
     * Detectors (banking, games, checkers) become scope entries that apply the
     * blacklist template, because they are the apps doing the querying. Root
     * traces (Magisk, LSPosed, Shizuku) go into the blacklist template itself,
     * because they are what must be hidden from those queries.
     */
    fun applyOneTap(
        context: Context,
        config: HmaConfig.Config,
        blacklistTemplateName: String,
        removeDisappeared: Boolean,
    ): Pair<Int, Int> {
        val apps = installedApps(context)
        val blacklist = config.ensureTemplate(blacklistTemplateName, isWhitelist = false)

        val traces = LinkedHashSet(blacklist.appList)
        val targets = LinkedHashSet<String>()

        apps.forEach { app ->
            when (AppHideRules.classify(app.pkg, app.isSystem).kind) {
                AppHideRules.Kind.DETECTOR -> targets.add(app.pkg)
                AppHideRules.Kind.ROOT_TRACE -> traces.add(app.pkg)
                else -> Unit
            }
        }

        if (removeDisappeared) {
            val present = apps.map { it.pkg }.toSet()
            traces.removeAll { it !in present }
            config.scope.keys.toList().forEach { pkg ->
                if (pkg !in present && pkg !in targets) config.removeScope(pkg)
            }
        }

        blacklist.setAppList(traces)

        targets.forEach { pkg ->
            val cfg = config.scopeConfig(pkg)
            cfg.useWhitelist = false
            cfg.setApplyTemplates(listOf(blacklistTemplateName))
        }

        return targets.size to traces.size
    }
}
