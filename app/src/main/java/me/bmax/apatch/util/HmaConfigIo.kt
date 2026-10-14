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
 * A single hardcoded path is not enough. The original HMA keeps the file in its
 * own data dir, the OSS fork keeps a runtime copy under /data/misc, and on
 * Android 11+ the data dir is also reachable through /data_mirror. So every
 * candidate is probed before giving up, and whichever path worked is remembered
 * on the config so writes go back to the same file.
 */
object HmaConfigIo {

    private const val TAG = "HmaConfigIo"

    const val DEFAULT_PATH = "/data/data/com.tsng.hidemyapplist/files/config.json"

    /** HMA package names seen across the original, debug and OSS builds. */
    private val PACKAGES = listOf(
        "com.tsng.hidemyapplist",
        "org.frknkrc44.hma_oss",
    )

    fun candidatePaths(): List<String> = buildList {
        PACKAGES.forEach { pkg ->
            add("/data/data/$pkg/files/config.json")
            add("/data_mirror/data_ce/null/0/$pkg/files/config.json")
        }
        add("/data/misc/hide_my_applist/config.json")
    }

    private fun runShell(vararg commands: String): List<String> {
        val out = ArrayList<String>()
        val sink = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(out::add) }
        }
        return try {
            withNewRootShell { newJob().add(*commands).to(sink, sink).exec() }
            out
        } catch (e: Exception) {
            Log.e(TAG, "shell failed", e)
            out + listOf("shell error: ${e.message}")
        }
    }

    /**
     * Full environment report, shown when no config can be found.
     *
     * Without this the screen can only say "not found", which does not tell the
     * user whether HMA is missing, has a different package name, has not saved
     * a config yet, or is simply not readable from this root shell.
     */
    fun diagnose(): String {
        val paths = candidatePaths().joinToString(" ")
        return runShell(
            "echo '== identity =='; id -u; id -un; getenforce",
            "echo '== candidate paths =='",
            "for p in $paths /data/misc/hide_my_applist*/config.json; do " +
                "if [ -f \"\$p\" ]; then echo \"FOUND \$p \$(stat -c '%s bytes uid=%u mode=%a' \"\$p\")\"; " +
                "else echo \"MISSING \$p\"; fi; done",
            "echo '== installed hma data dirs =='",
            "ls -d /data/data/*hidemyapplist* /data/data/*hma_oss* 2>/dev/null || echo none",
            "echo '== /data/misc dirs =='",
            "ls -d /data/misc/hide_my_applist* 2>/dev/null || echo none",
            "echo '== hma files dir =='",
            "ls -l /data/data/com.tsng.hidemyapplist/files/ 2>/dev/null || echo none",
            "echo '== misc listing =='",
            "ls -lR /data/misc/hide_my_applist* 2>/dev/null | head -25 || echo none",
        ).joinToString("\n")
    }

    /** Picks the path a brand new config should be written to. */
    fun resolveWritePath(): String {
        val pkgs = PACKAGES.joinToString(" ")
        val found = runShell(
            "for d in $pkgs; do if [ -d \"/data/data/\$d\" ]; then echo \"\$d\"; fi; done"
        ).map { it.trim() }.firstOrNull { it.isNotBlank() }
        return if (found != null) "/data/data/$found/files/config.json" else DEFAULT_PATH
    }

    /**
     * Reads the config from the first candidate that holds a parseable file.
     *
     * Candidates include /data/misc paths expanded by the shell, since those
     * contain a generated name that cannot be known ahead of time.
     */
    fun read(): HmaConfig.Config? {
        val paths = candidatePaths().toMutableList()
        paths += runShell("ls /data/misc/hide_my_applist*/config.json 2>/dev/null || true")
            .map { it.trim() }
            .filter { it.endsWith("config.json") }

        for (path in paths.distinct()) {
            val lines = runShell("cat \"$path\" 2>&1")
            if (lines.isEmpty()) continue
            val text = lines.joinToString("\n")
            if (!text.trimStart().startsWith("{")) continue
            val config = HmaConfig.parse(text) ?: continue
            config.sourcePath = path
            Log.i(TAG, "read config from $path")
            return config
        }
        return null
    }

    /**
     * Writes the config back to [path].
     *
     * The owner is taken from the HMA data dir rather than hardcoded and the
     * mode is restored to 600, otherwise a root-owned copy would leave HMA
     * unable to read its own file.
     */
    fun write(
        context: Context,
        config: HmaConfig.Config,
        path: String,
        onLine: (String) -> Unit,
    ): Boolean {
        val staged = File(context.filesDir, "hma_config.json").apply {
            writeText(config.toJson())
        }
        val dir = path.substringBeforeLast("/")
        val lines = runShell(
            "mkdir -p \"$dir\"",
            "[ -f \"$path\" ] && cp -f \"$path\" \"$path.bak\" || true",
            "cp -f ${staged.absolutePath} \"$path\"",
            "uid=\$(stat -c %u /data/data/\$(ls /data/data | grep -E 'hidemyapplist|hma_oss' | head -1) 2>/dev/null)",
            "[ -n \"\$uid\" ] && chown \$uid \"$path\" || true",
            "chmod 600 \"$path\"",
            "ls -l \"$path\"",
        )
        lines.forEach(onLine)
        val ok = lines.any { it.contains(path.substringAfterLast("/")) && it.startsWith("-") }
        if (!ok) onLine("failed to write $path")
        else onLine("config.json updated")
        return ok
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
            val appConfig = config.scopeConfig(pkg)
            appConfig.useWhitelist = false
            appConfig.setApplyTemplates(listOf(blacklistTemplateName))
        }

        return targets.size to traces.size
    }
}
