package me.bmax.apatch.util

import org.json.JSONObject

/**
 * Hide-list rules modelled on Hide-My-Applist (HMA).
 *
 * HMA's model is caller-centric: `scope` is keyed by the app that performs the
 * PackageManager query (the app being protected), and each entry names the
 * packages that should be hidden from it. FolkPatch cannot hook PackageManager,
 * but the same two-mode decision shape applies to what this screen actually
 * controls: which apps get a spoofed key attestation via Tricky Store.
 *
 * Mode semantics, identical to HMA:
 *   useWhitelist = false (blacklist) -> hide everything IN the list
 *   useWhitelist = true  (whitelist) -> hide everything NOT in the list
 *
 * The critical difference from HMA is what belongs in the list. HMA hides root
 * traces (Magisk, LSPosed modules) from detectors. Tricky Store hides detectors
 * from the attestation chain. So this classifier sorts apps into two buckets and
 * only the DETECTOR bucket is a valid hide-list entry; root tooling must stay
 * out of target.txt or the file becomes noise.
 */
object AppHideRules {

    /** Which side of the hide decision an installed app falls on. */
    enum class Kind {
        /** Banking, payment, DRM, games, detectors: wants a spoofed attestation. */
        DETECTOR,
        /** Root managers, Xposed/LSPosed modules, Shizuku, terminals: never hide. */
        ROOT_TRACE,
        /** Plain system component. */
        SYSTEM,
        /** Nothing matched. */
        UNKNOWN,
    }

    data class Decision(val kind: Kind, val reason: String)

    /** Root managers and su implementations. */
    private val ROOT_MANAGERS = setOf(
        "com.topjohnwu.magisk", "io.github.vvb2060.magisk",
        "me.bmax.apatch", "me.yuki.folk", "mi.yuki.folk",
        "github.ColdAsSunny.Kernel", "com.topjohnwu.magisk.alpha",
        "eu.chainfire.supersu", "com.noshufou.android.su",
        "me.weishu.exp", "top.canyie.dreamland.manager",
        "org.meowcat.edxposed.manager", "de.robv.android.xposed.installer",
        "org.lsposed.manager", "com.tsng.hidemyapplist",
        "dev.tsng.hidemyapplist", "io.github.vvb2060.mahoshojo",
        "moe.shizuku.privileged.api", "moe.shizuku.redirectstorage",
        "rikka.appops", "com.termux", "com.android.shell",
    )

    /** Substrings marking root / hooking / privacy tooling. */
    private val ROOT_KEYWORDS = listOf(
        "magisk", "apatch", "kernelsu", "kernelpatch", "sukisu", "supersu",
        "superuser", "shizuku", "dhizuku", "termux", "lsposed", "lspatch",
        "edxposed", "xposed", "dreamland", "taichi", "zygisk", "riru",
        "hidemyapplist", "hma_oss", "mmrl", "foxmmm", "ksuwebui",
        "appops", "xprivacylua", "thanox", "busybox", "storage-redirect",
        "storageisolation", "fakelocation", "mocklocation",
    )

    /** Known detector / checker apps. */
    private val DETECTOR_EXACT = setOf(
        "icu.nullptr.applistdetector", "com.tsng.applistdetector",
        "com.byxiaorun.detector", "com.zhenxi.hunter",
        "com.reveny.nativecheck", "icu.nullptr.nativetest",
        "krypton.tbsafetychecker", "com.dexprotector.detector.envchecks",
        "net.corepass.app", "chunqiu.safe", "chunqiu.safe.detector",
        "luna.safe.luna", "me.garfieldhan.holmes",
        "com.android.vending", "com.google.android.gms",
        "com.google.android.gsf",
    )

    /** Substrings marking attestation-sensitive apps. */
    private val DETECTOR_FINANCE = listOf(
        "bank", "pay", "wallet", "alipay", "unionpay", "yunshanfu",
        "icbc", "ccb", "cmbchina", "bankcomm", "psbc", "cebbank",
        "pingan", "cmbc", "spdb", "stock", "securities", "broker",
        "eastmoney", "hexin", "htsc", "tonghuashun", "xueqiu",
        "futunn", "tigerbrokers", "insurance", "huabei", "jdbank",
        "creditcard", "venmo", "zelle", "revolut", "n26", "monzo",
        "samsungpay", "gpay", "googlepay", "phonepe", "paytm",
    )

    private val DETECTOR_DRM = listOf(
        "netflix", "disney", "hulu", "hbomax", "primevideo", "amazonvideo",
        "spotify", "youtube", "peacocktv", "crunchyroll", "vudu",
        "dazn", "abema", "unext", "u-next", "tver", "rakuten.tv",
    )

    private val DETECTOR_GAME = listOf(
        "tmgp", "sgame", "pubgm", "pubg", "freefire", "mobilelegends",
        "arenaofvalor", "honorofkings", "genshin", "mihoyo", "hoyoverse",
        "fortnite", "roblox", "codm", "callofduty", "garena",
    )

    private val DETECTOR_MISC = listOf(
        "detector", "detect", "safetycheck", "safetynet", "integrity",
        "envcheck", "tamper", "rootcheck", "rootbeer", "tbsafety",
        "intune", "mdm", "workspaceone", "mobileiron", "airwatch",
        "knox", "gov", "12306", "ctrip",
    )

    /**
     * Mirrors HMA's Constants.packagesShouldNotHide. HMAService.shouldHide
     * returns false for these on both the caller and the query side, so they can
     * never be filtered whatever the mode is.
     */
    val PROTECTED = setOf(
        "android", "android.media", "android.uid.system", "android.uid.shell",
        "android.uid.systemui", "com.android.permissioncontroller",
        "com.android.providers.downloads", "com.android.providers.downloads.ui",
        "com.android.providers.media", "com.android.providers.media.module",
        "com.android.providers.settings", "com.google.android.providers.media.module",
        "com.google.android.permissioncontroller", "com.miui.securitycenter",
        "com.android.systemui", "com.android.settings",
        "com.google.android.webview", "com.google.android.trichromelibrary",
        "com.android.shell",
    )

    private fun lower(pkg: String) = pkg.lowercase()

    private fun hits(pkg: String, keywords: List<String>): String? {
        val p = lower(pkg)
        return keywords.firstOrNull { p.contains(it) }
    }

    /**
     * Classifies one package. Root tooling wins over detector rules so a module
     * whose name happens to contain "pay" is never mistaken for a banking app.
     */
    fun classify(pkg: String, isSystem: Boolean): Decision {
        if (pkg in PROTECTED) return Decision(Kind.SYSTEM, "protected")
        if (pkg in ROOT_MANAGERS) return Decision(Kind.ROOT_TRACE, "root-manager")
        hits(pkg, ROOT_KEYWORDS)?.let { return Decision(Kind.ROOT_TRACE, "root:$it") }
        if (pkg in DETECTOR_EXACT) return Decision(Kind.DETECTOR, "detector")
        hits(pkg, DETECTOR_FINANCE)?.let { return Decision(Kind.DETECTOR, "finance:$it") }
        hits(pkg, DETECTOR_DRM)?.let { return Decision(Kind.DETECTOR, "drm:$it") }
        hits(pkg, DETECTOR_GAME)?.let { return Decision(Kind.DETECTOR, "game:$it") }
        hits(pkg, DETECTOR_MISC)?.let { return Decision(Kind.DETECTOR, "check:$it") }
        return if (isSystem) Decision(Kind.SYSTEM, "system")
        else Decision(Kind.UNKNOWN, "user")
    }
}
