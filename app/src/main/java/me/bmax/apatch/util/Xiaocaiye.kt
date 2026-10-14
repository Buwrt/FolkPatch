package me.bmax.apatch.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import me.bmax.apatch.APApplication
import java.io.File
import java.io.FileOutputStream

/**
 * Built-in XiaoCaiYe helper.
 *
 * The helper is shipped as an APK asset tree (app/src/main/assets/xiaocaiye) and
 * staged into the module directory at runtime, so it behaves like an installed
 * APM module without the user ever flashing one. Nothing here runs unless the
 * user opens the helper screen and presses a volume key.
 */
object Xiaocaiye {

    private const val TAG = "Xiaocaiye"
    private const val ASSET_ROOT = "xiaocaiye"
    const val MODULE_DIR = "/data/adb/modules/xiaocaiye"

    /** Which script a volume key maps to. Mirrors the module's action.sh. */
    enum class Option { UPDATE_KEYBOX, UPDATE_PACKAGES }

    /**
     * Copies the asset tree into the app's private files dir. The shell cannot
     * read straight out of the APK, so the payload has to land on disk first.
     */
    private fun stage(context: Context): File? {
        val root = File(context.filesDir, ASSET_ROOT)
        return try {
            copyAssetDir(context, ASSET_ROOT, root)
            root
        } catch (e: Exception) {
            Log.e(TAG, "stage failed", e)
            null
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val assets = context.assets
        val children = assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            // Leaf: a file.
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(dest, child))
        }
    }

    /**
     * Publishes the staged payload to /data/adb/modules/xiaocaiye and marks the
     * scripts and binaries executable. Re-runs are cheap: files are overwritten.
     */
    private fun publish(staged: File): Boolean {
        val result = withNewRootShell {
            newJob().add(
                "mkdir -p $MODULE_DIR",
                "cp -Rf ${staged.absolutePath}/. $MODULE_DIR/",
                "chmod -R 0755 $MODULE_DIR",
                "find $MODULE_DIR -type f -name '*.sh' -exec chmod 0755 {} +",
                "ls $MODULE_DIR",
            ).exec()
        }
        Log.i(TAG, "publish result: ${result.isSuccess}")
        return result.isSuccess
    }

    private fun ensureInstalled(context: Context): Boolean {
        val staged = stage(context) ?: return false
        return publish(staged)
    }

    /**
     * Environment the helper scripts expect.
     *
     * The bundled curl is dynamically linked and was compiled against a CA path
     * that does not exist on Android, so without SSL_CERT_FILE every HTTPS fetch
     * fails silently (the scripts call curl with -s, which hides the TLS error
     * and surfaces only as "MD5校验文件获取失败"). OPENSSL_CONF is the openssl
     * config shipped next to the bundle.
     */
    private fun env(): String {
        val prefix = "$MODULE_DIR/arm64-v8a"
        return listOf(
            "export APATCH=true",
            "export KSU=false",
            "export PREFIX=$prefix",
            "export LD_LIBRARY_PATH=$prefix/lib",
            "export SSL_CERT_FILE=$prefix/etc/ssl/cert.pem",
            "export OPENSSL_CONF=$prefix/etc/ssl/openssl.cnf",
            // The bundled curl must win: a system curl would ignore our CA file.
            "export PATH=$prefix/bin:/system/bin:/system/xbin:/vendor/bin" +
                ":/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin" +
                ":/system_ext/bin:/odm/bin:/vendor/xbin",
        ).joinToString("; ")
    }

    /** Builds the shell invocation for [option], with the module's own env set up. */
    private fun command(option: Option): String {
        val env = env()

        val scripts = when (option) {
            Option.UPDATE_KEYBOX -> listOf("XiaoCaiYe/update_keybox.sh")
            // action.sh on APatch: refresh the APatch exclusion list first, then
            // the Tricky Store target list.
            Option.UPDATE_PACKAGES -> listOf(
                "XiaoCaiYe/APatch.sh",
                "XiaoCaiYe/update_packages.sh",
            )
        }
        val body = scripts.joinToString("; ") { "sh $MODULE_DIR/$it" }
        return "$env; $body"
    }

    /**
     * Runs a read-only environment check and streams the result.
     *
     * The helper scripts swallow curl errors (they pass -s), so a failing fetch
     * only shows up as a generic message. This prints the curl build, whether
     * the shipped CA bundle exists, and a verbose HTTPS fetch with its HTTP
     * status, which is enough to tell a TLS problem from a DNS or SELinux one.
     */
    fun diagnose(context: Context, onLine: (String) -> Unit): Boolean {
        if (!ensureInstalled(context)) {
            onLine("Failed to install helper payload")
            return false
        }

        val prefix = "$MODULE_DIR/arm64-v8a"
        val env = env()
        val steps = arrayOf(
            "$env; echo '--- curl build ---'; curl -V 2>&1 | head -3",
            "$env; echo '--- which curl ---'; command -v curl",
            "$env; echo '--- CA bundle ---'; ls -l $prefix/etc/ssl/cert.pem 2>&1",
            "$env; echo '--- libs ---'; ls $prefix/lib 2>&1",
            "$env; echo '--- fetch md5 (verbose) ---'; curl -sS -m 25 " +
                "-o /data/local/tmp/kc_md5.txt " +
                "-w 'http=%{http_code} size=%{size_download}' " +
                "https://dcdn.xiaocaiye.cn/keybox/keybox_md5.txt 2>&1; echo",
            "$env; echo '--- md5 content ---'; cat /data/local/tmp/kc_md5.txt 2>&1; echo",
            "$env; echo '--- dns ---'; getprop net.dns1; " +
                "ping -c 1 -W 2 dcdn.xiaocaiye.cn 2>&1 | head -2",
            "$env; echo '--- selinux ---'; getenforce; id -u",
        )

        val stdout = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(onLine) }
        }
        val stderr = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(onLine) }
        }

        val result = withNewRootShell {
            newJob().add(*steps).to(stdout, stderr).exec()
        }
        Log.i(TAG, "diagnose -> ${result.isSuccess}")
        return result.isSuccess
    }

    /**
     * Applies an app-hide blacklist / whitelist in one shot.
     *
     * The lists are written to disk first (the shell cannot read straight out of
     * the APK, and embedding a long app list in a shell command risks blowing the
     * command length limit), then the bundled script merges them into
     * /data/adb/tricky_store/target.txt and /data/adb/ap/package_config.
     *
     * @param black apps to hide, as (package name, uid)
     * @param white apps that must never be hidden (highest priority)
     */
    fun applyHideList(
        context: Context,
        black: List<Pair<String, Int>>,
        white: List<String>,
        onLine: (String) -> Unit,
    ): Boolean {
        if (!ensureInstalled(context)) {
            onLine("Failed to install helper payload")
            return false
        }

        val dir = File(context.filesDir, "hideconf").apply { mkdirs() }
        val blackFile = File(dir, "black.txt")
        val whiteFile = File(dir, "white.txt")
        return try {
            blackFile.writeText(black.joinToString("\n") { "${it.first},${it.second}" } + "\n")
            whiteFile.writeText(white.joinToString("\n") + "\n")

            val env = env()
            val steps = arrayOf(
                "$env; mkdir -p $MODULE_DIR/XiaoCaiYe",
                "$env; cp -f ${blackFile.absolutePath} $MODULE_DIR/XiaoCaiYe/隐藏黑名单.txt",
                "$env; cp -f ${whiteFile.absolutePath} $MODULE_DIR/XiaoCaiYe/隐藏白名单.txt",
                "$env; sh $MODULE_DIR/XiaoCaiYe/apply_hide_list.sh",
            )

            val stdout = object : CallbackList<String?>() {
                override fun onAddElement(s: String?) { s?.let(onLine) }
            }
            val stderr = object : CallbackList<String?>() {
                override fun onAddElement(s: String?) { s?.let(onLine) }
            }

            val result = withNewRootShell {
                newJob().add(*steps).to(stdout, stderr).exec()
            }
            Log.i(TAG, "applyHideList(${black.size} black / ${white.size} white) -> ${result.isSuccess}")
            result.isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "applyHideList failed", e)
            onLine("applyHideList failed: ${e.message}")
            false
        }
    }

    /**
     * Runs [option] on a root shell, streaming stdout/stderr line by line.
     * Returns false when the payload could not be installed or the shell failed.
     */
    fun run(
        context: Context,
        option: Option,
        onLine: (String) -> Unit,
    ): Boolean {
        if (!ensureInstalled(context)) {
            onLine("Failed to install helper payload")
            return false
        }

        val stdout = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(onLine) }
        }
        val stderr = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(onLine) }
        }

        val result = withNewRootShell {
            newJob().add(command(option)).to(stdout, stderr).exec()
        }
        Log.i(TAG, "run($option) -> ${result.isSuccess}")
        return result.isSuccess
    }
}
