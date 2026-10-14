package me.bmax.apatch.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 小助手 · 应用隐藏「黑白名单」自动分类器。
 *
 * 语义约定：
 *  - 黑名单(BLACK) = 需要隐藏的应用。写入 Tricky Store 的 target.txt，
 *    同时在 package_config 中标记 exclude=1，对该应用隐藏 root 痕迹。
 *  - 白名单(WHITE) = 明确不隐藏的应用。从上面两个列表中移除。
 *
 * 判定优先级（从高到低）：
 *   1. 强制白名单包名/关键字（root 管理器、终端、Shizuku、备份、模块工具）
 *   2. 强制黑名单包名/关键字（金融、政务、DRM、游戏、企业办公）
 *   3. Play 完整性必需的少数系统组件（gms / gsf / vending / android）
 *   4. 普通系统应用 -> 白名单（隐藏系统组件收益低、风险高）
 *   5. 其余未知第三方应用 -> 黑名单（保守：宁可多隐藏）
 *
 * 分类结果只决定「是否隐藏」，不自动授予 root。root 授权仍然在 SuperUser 页单独进行，
 * 已有被授权应用的配置会被原样保留。
 */
object HideListClassifier {

    enum class Kind { BLACK, WHITE }

    data class ScannedApp(
        val pkg: String,
        val label: String,
        val uid: Int,
        val isSystem: Boolean,
    )

    data class Verdict(
        val app: ScannedApp,
        val kind: Kind,
        val reason: String,
    )

    /** 决策来源，UI 上用于标注「规则」还是「用户手动调整」。 */
    enum class Source { RULE, MANUAL }

    data class Result(
        val app: ScannedApp,
        var kind: Kind,
        val reason: String,
        var source: Source = Source.RULE,
    )

    // ------------------------------------------------------------------
    // 强制白名单：这些应用一旦被隐藏，root 相关功能会直接失效或自锁
    // ------------------------------------------------------------------
    private val WHITE_PKGS = setOf(
        // FolkPatch / APatch / KernelPatch 生态
        "me.bmax.apatch", "github.ColdAsSunny.Kernel", "me.yuki.folk", "mi.yuki.folk",
        "me.sanori.kernelsu", "com.rifsxd.ksunext", "me.gowri.kernelsu",
        // 其他 root 方案
        "com.topjohnwu.magisk", "me.weishu.kernelsu", "me.kernelsu",
        // 模块 / WebUI / 仓库管理
        "io.github.a13e300.ksuwebui", "com.dergoogler.mmrl", "dev.dergoogler.mmrl",
        "com.xiaomiu.module", "org.lsposed.manager", "io.github.qauxv",
        // 授权与调试
        "moe.shizuku.privileged.api", "rikka.appops", "com.termux", "com.termux.api",
        "com.termux.boot", "com.android.shell", "com.android.development",
        // 备份（备份工具需要读 /data，隐藏后常出问题）
        "org.swiftapps.swiftbackup", "com.swiftapps.swiftbackup",
        "com.oasisfeng.nevo", "net.dinglisch.android.taskerm",
        // 本机管理器自身所在的 UID 组，避免把管理器自己排除掉
        "android",
    )

    private val WHITE_KEYWORDS = listOf(
        "kernelsu", "kernelpatch", "magisk", "apatch", "shizuku", "termux",
        "lsposed", "zygisk", "riru", "mmrl", "ksuwebui", "swiftbackup",
        "tasker", "appops",
    )

    // ------------------------------------------------------------------
    // 强制黑名单：会对 root / 解锁 bootloader 做强检测的应用
    // ------------------------------------------------------------------
    private val BLACK_PKGS = setOf(
        // Play 完整性必需组件：必须进 target.txt，否则强完整性拿不到
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        // 国内主流银行
        "com.icbc", "com.boc", "com.bankcomm", "com.abchina", "com.ccb",
        "com.psbc", "com.cmbchina", "com.cmbc", "com.pingan", "com.spdb",
        "com.cib", "com.hxb", "com.cebbank", "com.gzcb", "com.bony",
        "com.bankofshanghai", "com.nbcb", "com.qdccb", "com.hnxs",
        // 支付 / 钱包
        "com.eg.android.AlipayGphone", "com.tencent.mm", "com.unionpay",
        "com.unionpay.mobilepay", "com.huawei.wallet", "com.mi.pay",
        "com.oppo.wallet", "com.vivo.wallet", "com.samsung.android.samsungpay",
        // 证券 / 理财
        "com.htsc", "com.guosen", "com.csc", "com.zqrbx", "com.eastmoney",
        "com.xueqiu", "com.tigerbrokers", "com.futu", "cn.com.htsc",
        // 政务 / 税务 / 社保
        "com.zjrcu", "com.szrcb", "com.tax", "com.chinatax", "com.si",
        // DRM / 流媒体
        "com.netflix.mediaclient", "com.disney.disneyplus", "com.hbo.hbonow",
        "com.amazon.avod.thirdpartyclient", "com.spotify.music",
        "com.apple.android.music", "com.google.android.youtube",
        // 企业办公 / MDM
        "com.microsoft.office.outlook", "com.microsoft.teams",
        "com.microsoft.office.officehub", "com.tencent.wework",
        "com.alibaba.android.rimet", "com.bytedance.lark",
        "com.microsoft.emmx", "com.vmware.horizon",
    )

    private val BLACK_KEYWORDS = listOf(
        // 金融
        "bank", "banking", "creditcard", "credit.card", "cmb", "icbc", "abchina",
        "alipay", "unionpay", "wallet", "pay", "payment", "securities", "stock",
        "broker", "trader", "fund", "insurance", "finance", "fintech",
        // 政务 / 身份
        "gov", "government", "tax", "invoice", "social.security", "police",
        "customs", "immigration",
        // DRM / 版权流媒体
        "netflix", "disney", "hbo", "primevideo", "spotify", "widevine", "drm",
        // 游戏反作弊
        "anticheat", "anti.cheat", "tencent.tmgp", "mihoyo", "hoyoverse",
        "genshin", "honkai", "pubgm", "tencent.game", "riotgames", "epicgames",
        // 企业 / MDM
        "intune", "mdm", "airwatch", "workspace", "outlook", "teams",
        "wework", "dingtalk", "lark", "feishu",
        // 检测类
        "rootcheck", "safetynet", "integrity", "playintegrity",
    )

    /** 中文名兜底识别：很多国产应用包名不含语义。 */
    private val BLACK_LABEL_KEYWORDS = listOf(
        "银行", "农商", "农信", "信用", "证券", "炒股", "基金", "理财", "保险",
        "支付", "钱包", "云闪付", "数字人民币", "税务", "个税", "社保", "公积金",
        "政务", "公安", "交警", "海关", "政务服务", "医保",
        "企业微信", "钉钉", "飞书", "办公", "门禁", "考勤",
    )

    private val WHITE_LABEL_KEYWORDS = listOf(
        "终端", "开发者", "模块", "刷机", "面具", "Shizuku", "备份", "授权管理",
    )

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    /** 扫描设备上已安装的全部应用。 */
    fun scan(pm: PackageManager): List<ScannedApp> {
        @Suppress("DEPRECATION")
        val installed = try {
            pm.getInstalledPackages(0)
        } catch (e: Exception) {
            emptyList()
        }
        return installed.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val pkg = pi.packageName
            if (pkg.isBlank()) return@mapNotNull null
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            ScannedApp(pkg = pkg, label = label, uid = ai.uid, isSystem = isSystem)
        }.sortedWith(compareBy({ it.label.lowercase() }, { it.pkg }))
    }

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    fun classify(app: ScannedApp): Verdict {
        val pkg = app.pkg.lowercase()
        val label = app.label

        // 1) 强制白名单（root / 开发工具）
        if (app.pkg in WHITE_PKGS) {
            return Verdict(app, Kind.WHITE, "Root/工具类，隐藏会失效")
        }
        if (WHITE_KEYWORDS.any { pkg.contains(it) }) {
            return Verdict(app, Kind.WHITE, "Root/工具类，隐藏会失效")
        }
        if (WHITE_LABEL_KEYWORDS.any { label.contains(it) }) {
            return Verdict(app, Kind.WHITE, "Root/工具类，隐藏会失效")
        }

        // 2) 强制黑名单
        if (app.pkg in BLACK_PKGS) {
            return Verdict(app, Kind.BLACK, reasonOf(app))
        }
        val kw = BLACK_KEYWORDS.firstOrNull { pkg.contains(it) }
        if (kw != null) {
            return Verdict(app, Kind.BLACK, reasonOf(app))
        }
        if (BLACK_LABEL_KEYWORDS.any { label.contains(it) }) {
            return Verdict(app, Kind.BLACK, reasonOf(app))
        }

        // 3) 系统应用：默认不隐藏（隐藏系统组件收益低、风险高）
        if (app.isSystem) {
            return Verdict(app, Kind.WHITE, "系统应用，默认不隐藏")
        }

        // 4) 未知第三方：保守隐藏
        return Verdict(app, Kind.BLACK, "第三方应用，默认隐藏")
    }

    fun classifyAll(apps: List<ScannedApp>): List<Result> =
        apps.map { classify(it).let { v -> Result(v.app, v.kind, v.reason) } }

    private fun reasonOf(app: ScannedApp): String {
        val pkg = app.pkg.lowercase()
        val label = app.label
        return when {
            pkg.startsWith("com.google.android.gms") ||
                pkg == "com.google.android.gsf" ||
                pkg == "com.android.vending" -> "Play 完整性必需"
            BLACK_LABEL_KEYWORDS.any { label.contains(it) } ||
                BLACK_KEYWORDS.any { pkg.contains(it) } -> categoryOf(pkg, label)
            else -> "检测到强校验"
        }
    }

    private fun categoryOf(pkg: String, label: String): String {
        val text = "$pkg $label"
        return when {
            listOf("银行", "bank", "credit", "icbc", "abchina", "cmb").any { text.contains(it) } -> "金融/银行"
            listOf("证券", "炒股", "stock", "broker", "trader", "fund").any { text.contains(it) } -> "证券/理财"
            listOf("支付", "钱包", "pay", "wallet", "alipay", "unionpay").any { text.contains(it) } -> "支付/钱包"
            listOf("政务", "税务", "社保", "gov", "tax", "invoice", "police").any { text.contains(it) } -> "政务/税务"
            listOf("netflix", "disney", "hbo", "spotify", "primevideo", "drm", "widevine").any {
                text.contains(it)
            } -> "DRM/流媒体"
            listOf("游戏", "genshin", "honkai", "mihoyo", "pubgm", "tmgp", "anticheat").any {
                text.contains(it)
            } -> "游戏/反作弊"
            listOf("企业微信", "钉钉", "飞书", "outlook", "teams", "intune", "wework", "lark").any {
                text.contains(it)
            } -> "企业办公/MDM"
            else -> "检测到强校验"
        }
    }
}
