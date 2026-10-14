package me.bmax.apatch.util

import org.json.JSONObject

/**
 * Reader and writer for Hide-My-Applist's config.json.
 *
 * The decision logic HMA actually runs is HMAService.shouldHide:
 *
 *   appConfig = scope[caller] ?: return false          // unconfigured apps pass
 *   if query in extraAppList        -> return !useWhitelist
 *   if query in extraOppositeAppList -> return useWhitelist
 *   for each applied template:
 *       if query in template.appList -> return !useWhitelist
 *   if useWhitelist && excludeSystemApps && query is a system app -> return false
 *   return useWhitelist
 *
 * Two things follow from that, and both shape this file. First, the template's
 * own `isWhitelist` flag is never consulted at runtime: it only tells the HMA
 * UI how to present the template. The mode that matters is
 * `AppConfig.useWhitelist`. Second, the semantics are caller-centric: `scope` is
 * keyed by the app asking the question, and the lists hold what should be kept
 * from it. So a blacklist template holds root traces (Magisk, LSPosed), and the
 * scope keys are the detectors those traces must be hidden from.
 *
 * Every object keeps the JSONObject it was parsed from, so fields this screen
 * does not know about (presets, settings templates, newer HMA-OSS options) are
 * written back untouched.
 */
object HmaConfig {

    /** HMA's own package, and where it keeps the config. */
    const val PKG = "com.tsng.hidemyapplist"
    const val PATH = "/data/data/$PKG/files/config.json"

    /** A template is just a named bag of packages plus a UI hint. */
    class Template(val raw: JSONObject, val name: String) {
        var isWhitelist: Boolean
            get() = raw.optBoolean("isWhitelist", false)
            set(value) = raw.put("isWhitelist", value)

        val appList: MutableSet<String>
            get() = raw.optJSONArray("appList")?.let { arr ->
                LinkedHashSet<String>().apply {
                    for (i in 0 until arr.length()) arr.optString(i, "")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            } ?: LinkedHashSet()

        fun setAppList(list: Collection<String>) {
            raw.put("appList", org.json.JSONArray().apply { list.sorted().forEach(::put) })
        }
    }

    /** Per-target-app config. Only the fields this screen edits are exposed. */
    class AppConfig(val raw: JSONObject) {
        var useWhitelist: Boolean
            get() = raw.optBoolean("useWhitelist", false)
            set(value) = raw.put("useWhitelist", value)

        var excludeSystemApps: Boolean
            get() = raw.optBoolean("excludeSystemApps", true)
            set(value) = raw.put("excludeSystemApps", value)

        val applyTemplates: MutableSet<String>
            get() = jsonStringSet(raw, "applyTemplates")

        fun setApplyTemplates(list: Collection<String>) {
            putStringSet(raw, "applyTemplates", list)
        }

        val extraAppList: MutableSet<String>
            get() = jsonStringSet(raw, "extraAppList")

        fun setExtraAppList(list: Collection<String>) {
            putStringSet(raw, "extraAppList", list)
        }

        val extraOppositeAppList: MutableSet<String>
            get() = jsonStringSet(raw, "extraOppositeAppList")

        fun setExtraOppositeAppList(list: Collection<String>) {
            putStringSet(raw, "extraOppositeAppList", list)
        }
    }

    /** The whole file: untouched root object plus the two maps we work on. */
    class Config(val raw: JSONObject) {
        val templates = LinkedHashMap<String, Template>()
        val scope = LinkedHashMap<String, AppConfig>()

        /** Blacklist templates, i.e. "hide what is listed". */
        val blacklistTemplates: List<Template>
            get() = templates.values.filter { !it.isWhitelist }

        /** Whitelist templates, i.e. "hide what is not listed". */
        val whitelistTemplates: List<Template>
            get() = templates.values.filter { it.isWhitelist }

        fun template(name: String): Template? = templates[name]

        /** Creates a template if it is missing; existing ones are returned as-is. */
        fun ensureTemplate(name: String, isWhitelist: Boolean): Template {
            templates[name]?.let { return it }
            val raw = JSONObject().apply {
                put("isWhitelist", isWhitelist)
                put("appList", org.json.JSONArray())
            }
            return Template(raw, name).also { templates[name] = it }
        }

        fun scopeConfig(pkg: String): AppConfig = scope.getOrPut(pkg) { AppConfig(JSONObject()) }

        fun removeScope(pkg: String): Boolean = scope.remove(pkg) != null

        /** Serialises scope and templates back into the root object. */
        fun flush() {
            val tplRoot = JSONObject()
            templates.forEach { (name, tpl) -> tplRoot.put(name, tpl.raw) }
            raw.put("templates", tplRoot)

            val scopeRoot = JSONObject()
            scope.forEach { (pkg, cfg) -> if (cfg.raw.length() > 0) scopeRoot.put(pkg, cfg.raw) }
            raw.put("scope", scopeRoot)
        }

        fun toJson(pretty: Boolean = true): String {
            flush()
            return if (pretty) raw.toString(2) else raw.toString()
        }
    }

    private fun jsonStringSet(raw: JSONObject, key: String): MutableSet<String> {
        val arr = raw.optJSONArray(key) ?: return LinkedHashSet()
        return LinkedHashSet<String>().apply {
            for (i in 0 until arr.length()) arr.optString(i, "")?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun putStringSet(raw: JSONObject, key: String, list: Collection<String>) {
        raw.put(key, org.json.JSONArray().apply { list.sorted().forEach(::put) })
    }

    /** Parses a config.json. Returns null when the file is not an HMA config. */
    fun parse(text: String): Config? {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        if (!root.has("scope") && !root.has("templates")) return null

        val config = Config(root)
        root.optJSONObject("templates")?.let { obj ->
            val names = obj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val raw = obj.optJSONObject(name) ?: continue
                config.templates[name] = Template(raw, name)
            }
        }
        root.optJSONObject("scope")?.let { obj ->
            val pkgs = obj.keys()
            while (pkgs.hasNext()) {
                val pkg = pkgs.next()
                val raw = obj.optJSONObject(pkg) ?: continue
                config.scope[pkg] = AppConfig(raw)
            }
        }
        return config
    }

    /**
     * Reproduces HMAService.shouldHide for preview purposes.
     *
     * This mirrors HMA so the screen can show what a given configuration will
     * do before it is written. It is deliberately a copy of HMA's own order of
     * checks, including the fact that a template's isWhitelist is ignored.
     */
    fun shouldHide(
        config: Config,
        caller: String,
        query: String,
        isQuerySystem: Boolean,
    ): Boolean {
        if (caller == query) return false
        if (caller in AppHideRules.PROTECTED || query in AppHideRules.PROTECTED) return false

        val appConfig = config.scope[caller] ?: return false
        if (query in appConfig.extraAppList) return !appConfig.useWhitelist
        if (query in appConfig.extraOppositeAppList) return appConfig.useWhitelist

        for (name in appConfig.applyTemplates) {
            val tpl = config.template(name) ?: continue
            if (query in tpl.appList) return !appConfig.useWhitelist
        }

        if (appConfig.useWhitelist && appConfig.excludeSystemApps && isQuerySystem) return false
        return appConfig.useWhitelist
    }
}
