package me.bmax.apatch.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * Runs commands and returns their combined output.
     *
     * The shell writes stdout from its own thread while the caller is already
     * iterating the result, so the buffer must be concurrent and the value
     * handed back must be a snapshot. Returning the live list let the shell
     * thread mutate it mid-iteration and crashed the caller with
     * ConcurrentModificationException.
     */
    private fun runShell(vararg commands: String): List<String> {
        val out = CopyOnWriteArrayList<String>()
        val sink = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(out::add) }
        }
        return try {
            withNewRootShell { newJob().add(*commands).to(sink, sink).exec() }
            ArrayList(out)
        } catch (e: Exception) {
            Log.e(TAG, "shell failed", e)
            ArrayList(out) + listOf("shell error: ${e.message}")
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
     * Writes the config back to [path], trying three independent methods.
     *
     * Reading works but writing did not, which points at the staging step: the
     * config is written into this app's private dir first, and if the root
     * shell cannot read that dir (SELinux rather than DAC) both copy methods
     * fail for the same reason. So the third method stops touching the file
     * system entirely and streams the payload through the shell as base64
     * chunks, which only needs permission to create the target.
     *
     * Every method is verified by byte count before it is accepted, and each
     * step is reported so a failure can be read rather than guessed at.
     */
    fun write(
        context: Context,
        config: HmaConfig.Config,
        path: String,
        onLine: (String) -> Unit,
    ): Boolean {
        val payload = config.toJson()
        val expected = payload.toByteArray(Charsets.UTF_8).size
        val dir = path.substringBeforeLast("/")

        onLine("target: $path")
        onLine("payload: $expected bytes")
        onLine("--- before ---")
        runShell(
            "id -u",
            "getenforce 2>&1",
            "ls -ld \"$dir\" 2>&1",
            "ls -l \"$path\" 2>&1",
            "stat -c '%s bytes uid=%u gid=%g mode=%a ctx=%C' \"$path\" 2>&1",
        ).forEach(onLine)

        runShell("[ -f \"$path\" ] && cp -f \"$path\" \"$path.bak\" 2>&1 || echo 'no existing file to back up'")
            .forEach(onLine)

        val methods = mutableListOf<Pair<String, () -> List<String>>>()

        // Method 1: copy from the app's private dir.
        val staged = File(context.filesDir, "hma_config.json")
        methods += "copy from ${staged.absolutePath}" to {
            staged.writeText(payload, Charsets.UTF_8)
            staged.setReadable(true, false)
            staged.setWritable(true, false)
            runShell(
                "ls -l ${staged.absolutePath} 2>&1",
                "cp -f ${staged.absolutePath} \"$path\" 2>&1",
            )
        }

        // Method 2: redirect the same file into the target.
        methods += "redirect from ${staged.absolutePath}" to {
            runShell("cat ${staged.absolutePath} > \"$path\" 2>&1")
        }

        // Method 3: stream base64 through the shell, no source file needed.
        methods += "base64 stream" to {
            val b64 = android.util.Base64.encodeToString(
                payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val out = ArrayList<String>()
            out += runShell("rm -f \"$path\" 2>&1")
            b64.chunked(4096).forEach { chunk ->
                out += runShell("printf '%s' '$chunk' | base64 -d >> \"$path\" 2>&1")
            }
            out
        }

        var written = false
        for ((name, attempt) in methods) {
            onLine("--- method: $name ---")
            runCatching { attempt().forEach(onLine) }
                .onFailure { onLine("threw: ${it.message}") }

            val size = runShell("stat -c %s \"$path\" 2>&1")
                .firstOrNull()?.trim()?.toIntOrNull() ?: -1
            if (size == expected) {
                written = true
                onLine("OK: $size bytes matches payload")
                break
            }
            onLine("FAILED: got $size bytes, wanted $expected")
        }

        if (!written) {
            onLine("all three write methods failed")
            return false
        }

        // Restore ownership and mode so HMA can still read its own file.
        onLine("--- fix ownership ---")
        runShell(
            "uid=\$(stat -c %u \"$dir\" 2>/dev/null)",
            "echo uid=\$uid",
            "chown \$uid \"$path\" 2>&1",
            "chmod 600 \"$path\" 2>&1",
            "stat -c '%s bytes uid=%u mode=%a ctx=%C' \"$path\" 2>&1",
        ).forEach(onLine)

        val finalSize = runShell("stat -c %s \"$path\" 2>&1")
            .firstOrNull()?.trim()?.toIntOrNull()
        val ok = finalSize == expected
        onLine(if (ok) "config.json updated" else "size changed after chown: $finalSize")
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
     * One-tap fill: sort installed apps into HMA's two lists.
     *
     * Root traces (Magisk, LSPosed, Shizuku) land on the blacklist because they
     * are what must be hidden. Everything else is left visible, and the
     * whitelist is left empty for the user to opt apps into as overrides.
     */
    fun applyOneTap(
        context: Context,
        config: HmaConfig.Config,
        removeDisappeared: Boolean,
    ): Pair<Int, Int> {
        val apps = installedApps(context)
        val blacklist = config.ensureTemplate(HmaConfig.BLACKLIST_TEMPLATE, isWhitelist = false)
        config.ensureTemplate(HmaConfig.WHITELIST_TEMPLATE, isWhitelist = true)

        val blacks = LinkedHashSet(blacklist.appList)
        val whites = LinkedHashSet(
            config.template(HmaConfig.WHITELIST_TEMPLATE)?.appList ?: emptySet()
        )

        apps.forEach { app ->
            if (AppHideRules.classify(app.pkg, app.isSystem).kind ==
                AppHideRules.Kind.ROOT_TRACE
            ) {
                blacks.add(app.pkg)
            }
        }

        if (removeDisappeared) {
            val present = apps.map { it.pkg }.toSet()
            blacks.removeAll { it !in present }
            whites.removeAll { it !in present }
            config.scope.keys.toList().forEach { pkg ->
                if (pkg !in present) config.removeScope(pkg)
            }
        }

        blacklist.setAppList(blacks)
        config.template(HmaConfig.WHITELIST_TEMPLATE)?.setAppList(whites)
        syncScope(context, config, whites)
        return blacks.size to whites.size
    }

    /**
     * Rewrites the scope from the current blacklist/whitelist choice.
     *
     * Detectors become scope entries that apply the blacklist template. The
     * whitelist is mirrored into each entry's extraOppositeAppList, which
     * HMAService.shouldHide checks before any template, so a whitelisted
     * package stays visible even when the blacklist also names it.
     */
    fun syncScope(
        context: Context,
        config: HmaConfig.Config,
        whites: Set<String>,
    ): Int {
        val apps = installedApps(context)
        val targets = apps.filter {
            AppHideRules.classify(it.pkg, it.isSystem).kind == AppHideRules.Kind.DETECTOR
        }.map { it.pkg }

        targets.forEach { pkg ->
            val appConfig = config.scopeConfig(pkg)
            appConfig.useWhitelist = false
            appConfig.setApplyTemplates(listOf(HmaConfig.BLACKLIST_TEMPLATE))
            appConfig.setExtraOppositeAppList(whites)
        }
        return targets.size
    }
}
