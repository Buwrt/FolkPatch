package me.bmax.apatch.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import me.bmax.apatch.APApplication
import java.io.File
import java.io.FileOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

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
     * Where the keybox may live. update_keybox.sh writes to
     * /data/adb/tricky_store/keybox.xml, which is not the modules directory,
     * so both are probed and a find over /data/adb catches anything else.
     */
    private val KEYBOX_PATHS = listOf(
        "/data/adb/tricky_store/keybox.xml",
        "/data/adb/modules/tricky_store/keybox.xml",
        "/data/adb/XiaoCaiYe/keybox.xml",
        "/data/adb/modules/xiaocaiye/keybox.xml",
    )

    /** A keybox is treated as due for a refresh once it is this close to expiry. */
    const val EXPIRY_WARNING_DAYS = 7

    enum class KeyboxState { OK, EXPIRING, EXPIRED, MISSING, UNKNOWN }

    data class KeyboxStatus(
        val state: KeyboxState,
        /** Whole days until the earliest certificate in the chain expires. */
        val daysLeft: Int? = null,
    )

    /** Runs read-only shell commands and returns every output line. */
    private fun collectLines(vararg commands: String): List<String> {
        val out = ArrayList<String>()
        val stdout = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(out::add) }
        }
        val stderr = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) { s?.let(out::add) }
        }
        return try {
            withNewRootShell { newJob().add(*commands).to(stdout, stderr).exec() }
            out
        } catch (e: Exception) {
            Log.e(TAG, "collectLines failed", e)
            out
        }
    }

    /**
     * Reads the installed keybox and reports how long it stays valid.
     *
     * A keybox file carries no plain-text expiry, so the certificates inside it
     * are parsed and the earliest `notAfter` wins - that is the moment the
     * whole chain stops being usable.
     */
    fun keyboxStatus(): KeyboxStatus {
        // Probe the known paths, then fall back to a scan so a keybox in an
        // unexpected place still gets picked up.
        val lines = collectLines(
            *KEYBOX_PATHS.map { "cat $it 2>/dev/null" }.toTypedArray(),
            "find /data/adb -maxdepth 4 -type f -name 'keybox.xml' 2>/dev/null | head -5",
        ).toMutableList()
        val found = lines.filter { it.startsWith("/") && it.endsWith("keybox.xml") }.distinct()
        for (path in found) {
            lines += collectLines("cat $path 2>/dev/null")
        }
        val text = lines.joinToString("\n")
        if (!text.contains("<Keybox", ignoreCase = true) &&
            !text.contains("BEGIN CERTIFICATE")
        ) {
            return KeyboxStatus(KeyboxState.MISSING)
        }

        val expiry = earliestExpiry(text) ?: return KeyboxStatus(KeyboxState.UNKNOWN)
        val daysLeft = ((expiry - System.currentTimeMillis()) / 86_400_000L).toInt()
        val state = when {
            daysLeft < 0 -> KeyboxState.EXPIRED
            daysLeft <= EXPIRY_WARNING_DAYS -> KeyboxState.EXPIRING
            else -> KeyboxState.OK
        }
        return KeyboxStatus(state, daysLeft.coerceAtLeast(0))
    }

    private fun earliestExpiry(text: String): Long? {
        val factory = runCatching { CertificateFactory.getInstance("X.509") }.getOrNull()
            ?: return null
        val pem = Regex(
            "-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----",
            RegexOption.DOT_MATCHES_ALL,
        )
        var earliest: Long? = null
        for (match in pem.findAll(text)) {
            val encoded = "-----BEGIN CERTIFICATE-----" +
                match.groupValues[1] +
                "-----END CERTIFICATE-----"
            runCatching {
                val cert = factory.generateCertificate(
                    encoded.byteInputStream()
                ) as? X509Certificate ?: return@runCatching
                val time = cert.notAfter.time
                if (earliest == null || time < earliest!!) earliest = time
            }
        }
        return earliest
    }

    /** True when the keybox host answers over HTTPS. */
    fun networkReachable(): Boolean {
        val env = env()
        val lines = collectLines(
            "$env; curl -sS -m 10 -o /dev/null " +
                "-w 'http=%{http_code}' " +
                "https://dcdn.xiaocaiye.cn/keybox/keybox_md5.txt 2>&1; echo",
        )
        val joined = lines.joinToString("\n")
        Log.i(TAG, "network check -> $joined")
        return joined.contains("http=200")
    }

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
