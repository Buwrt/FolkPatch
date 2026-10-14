package me.bmax.apatch.util

/**
 * Rules that decide whether an installed app should be hidden (blacklist) or
 * left visible (whitelist) by the one-tap hide-list scanner.
 *
 * Matching is intentionally package-name based: labels are localized, so a
 * keyword hit on the label alone is unreliable across locales.
 */
object AppHideRules {

    enum class Verdict { HIDE, KEEP }

    data class Decision(val verdict: Verdict, val reason: String)

    /** Packages that must never be hidden, or the manager itself breaks. */
    private val KEEP_EXACT = setOf(
        "android",
        "me.bmax.apatch",
        "me.yuki.folk",
        "mi.yuki.folk",
        "github.ColdAsSunny.Kernel",
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.noshufou.android.su",
        "moe.shizuku.privileged.api",
        "rikka.appops",
        "com.termux",
        "com.termux.api",
        "com.termux.boot",
        "com.android.shell",
        "com.android.systemui",
        "com.android.settings",
        "com.android.providers.settings",
        "com.google.android.webview",
        "com.google.android.trichromelibrary",
    )

    /** Substrings that mark a package as root/hacker tooling -> never hide. */
    private val KEEP_KEYWORDS = listOf(
        "shizuku", "termux", "magisk", "apatch", "kernelpatch", "kernelsu",
        "supersu", "superuser", "mmrl", "foxmmm", "ksuwebui", "lsposed",
        "lspatch", "zygisk", "riru", "xposed", "edxposed", "taichi",
        "appops", "adb", "busybox", "terminal", "mtmanager", "materialfiles",
        "rootexplorer", "solid.explorer", "swiftbackup", "titaniumbackup",
        "oandbackup", "neobackup", "seedvault",
    )

    /** Finance / payment / securities / government: the strongest detectors. */
    private val HIDE_FINANCE = listOf(
        "bank", "pay", "wallet", "alipay", "unionpay", "yunshanfu",
        "icbc", "ccb", "cmbchina", "bankcomm", "boc", "abc", "psbc",
        "hxb", "cib", "cebbank", "pingan", "cmbc", "spdb", "ebcchina",
        "stock", "securities", "broker", "trade", "eastmoney", "hexin",
        "htsc", "tonghuashun", "xueqiu", "futunn", "tigerbrokers",
        "insurance", "policy", "credit", "loan", "huabei", "jdbank",
        "duolabao", "lakala", "squareup", "venmo", "zelle", "revolut",
        "n26", "monzo", "wise", "samsungpay", "applepay", "googlepay",
        "gpay", "phonepe", "paytm", "stripe", "klarna", "afterpay",
    )

    /** DRM / streaming with hardware-backed attestation. */
    private val HIDE_DRM = listOf(
        "netflix", "disney", "hulu", "hbomax", "hbogo", "primevideo",
        "amazonvideo", "spotify", "youtube", "hbnow", "peacocktv",
        ".paramount", "crunchyroll", "vudu", "fubo", "sling", "dazn",
        "tv.apple", "mlb", "nba", "espn", "dmm", "abema", "unext",
        "u-next", "hulu", "wowow", "tver", "radiko", "rakuten.tv",
    )

    /** Games with anti-cheat, plus MDM / corporate containers. */
    private val HIDE_SENSITIVE = listOf(
        "game", "games", "genshin", "mihoyo", "hoyoverse", "tencent.tmgp",
        "pubg", "freefire", "mobilelegends", "arenaofvalor", "lolm",
        "honorofkings", "fortnite", "roblox", "codm", "callofduty",
        "garena", "riot", "epicgames", "nintendo", "playstation",
        "intune", "mdm", "workspaceone", "mobileiron", "airwatch",
        "samsungknox", "knox", "good.work", "blackberry", "citrix",
        "gov", "government", "12306", "ctrip", "alipay", "wechat",
    )

    /**
     * The four packages a Tricky Store target list is expected to contain
     * regardless of what else is installed.
     */
    val ALWAYS_HIDE_EXACT = setOf(
        "android",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
    )

    private fun lower(pkg: String) = pkg.lowercase()

    private fun hits(pkg: String, keywords: List<String>): String? {
        val p = lower(pkg)
        return keywords.firstOrNull { p.contains(it) }
    }

    /**
     * Classifies one package.
     *
     * Order matters: keep-rules win over hide-rules so root tooling is never
     * hidden, then explicit hide categories, then the Tricky Store defaults,
     * then a conservative fallback (unknown user apps hidden, unknown system
     * apps kept).
     */
    fun decide(pkg: String, isSystem: Boolean): Decision {
        if (pkg in KEEP_EXACT) {
            return Decision(Verdict.KEEP, "core")
        }
        hits(pkg, KEEP_KEYWORDS)?.let {
            return Decision(Verdict.KEEP, "tool:$it")
        }
        if (pkg in ALWAYS_HIDE_EXACT) {
            return Decision(Verdict.HIDE, "attestation")
        }
        hits(pkg, HIDE_FINANCE)?.let {
            return Decision(Verdict.HIDE, "finance:$it")
        }
        hits(pkg, HIDE_DRM)?.let {
            return Decision(Verdict.HIDE, "drm:$it")
        }
        hits(pkg, HIDE_SENSITIVE)?.let {
            return Decision(Verdict.HIDE, "sensitive:$it")
        }
        return if (isSystem) {
            Decision(Verdict.KEEP, "system")
        } else {
            Decision(Verdict.HIDE, "user")
        }
    }
}
