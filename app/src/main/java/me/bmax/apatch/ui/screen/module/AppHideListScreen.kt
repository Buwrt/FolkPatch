package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.folk.FolkScaffold
import me.bmax.apatch.util.AppHideRules
import me.bmax.apatch.util.HmaConfig
import me.bmax.apatch.util.HmaConfigIo
import me.bmax.apatch.util.ui.showToast

private const val BLACKLIST_TEMPLATE = "黑名单"

/**
 * Edits Hide-My-Applist's config.json in place.
 *
 * HMA is caller-centric, so this screen has two sides that must not be mixed
 * up. "Protected apps" are the scope keys: the apps doing the asking, such as a
 * banking app or a game, and they are what HMA shields. "Hidden apps" are the
 * blacklist template contents: the root traces HMA keeps out of that app's
 * sight. One tap sorts every installed package into the right side.
 */
@Composable
@Destination<RootGraph>
fun AppHideListScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<HmaConfigIo.Installed>>(emptyList()) }
    var config: HmaConfig.Config? by remember { mutableStateOf(null) }
    var targets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var traces by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var tabIndex by remember { mutableIntStateOf(0) }

    fun load() {
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val parsed = HmaConfigIo.read()
            val installed = HmaConfigIo.installedApps(context)
            withContext(Dispatchers.Main.immediate) {
                if (parsed == null) {
                    log = context.getString(R.string.hma_missing)
                } else {
                    config = parsed
                    apps = installed
                    targets = parsed.scope.keys.toSet()
                    traces = parsed.template(BLACKLIST_TEMPLATE)?.appList?.toSet() ?: emptySet()
                    loaded = true
                }
                busy = false
            }
        }
    }

    fun oneTap() {
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val base = HmaConfigIo.read() ?: HmaConfig.Config(
                org.json.JSONObject().apply { put("configVersion", 93) }
            )
            val installed = HmaConfigIo.installedApps(context)
            val pair = HmaConfigIo.applyOneTap(context, base, BLACKLIST_TEMPLATE, true)
            withContext(Dispatchers.Main.immediate) {
                config = base
                apps = installed
                targets = base.scope.keys.toSet()
                traces = base.template(BLACKLIST_TEMPLATE)?.appList?.toSet() ?: emptySet()
                loaded = true
                log = context.getString(R.string.hma_scanned, pair.first, pair.second)
                busy = false
            }
        }
    }

    fun save() {
        val cfg = config ?: return
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val blacklist = cfg.ensureTemplate(BLACKLIST_TEMPLATE, isWhitelist = false)
            blacklist.setAppList(traces)
            targets.forEach { pkg ->
                val appConfig = cfg.scopeConfig(pkg)
                appConfig.useWhitelist = false
                appConfig.setApplyTemplates(listOf(BLACKLIST_TEMPLATE))
            }
            cfg.scope.keys.toList().forEach { pkg ->
                if (pkg !in targets) cfg.removeScope(pkg)
            }
            val lines = ArrayList<String>()
            val ok = HmaConfigIo.write(context, cfg) { line -> lines.add(line) }
            withContext(Dispatchers.Main.immediate) {
                log = lines.joinToString("\n")
                busy = false
                showToast(
                    context,
                    context.getString(if (ok) R.string.hma_saved else R.string.hma_failed)
                )
            }
        }
    }

    FolkScaffold(
        title = stringResource(R.string.hma_title),
        onBack = dropUnlessResumed { navigator.popBackStack() },
        snackbarHostState = null,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.hma_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = ::oneTap,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                    Text(
                        text = stringResource(R.string.hma_scan),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (!loaded) {
                    Button(
                        onClick = ::load,
                        enabled = !busy,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(stringResource(R.string.hma_load))
                    }
                }
            }

            if (busy) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
                }
            } else if (loaded) {
                HmaBody(
                    apps = apps,
                    targets = targets,
                    traces = traces,
                    query = query,
                    tabIndex = tabIndex,
                    onQueryChange = { query = it },
                    onTabChange = { tabIndex = it },
                    onTargetToggle = { pkg ->
                        targets = if (pkg in targets) targets - pkg else targets + pkg
                    },
                    onTraceToggle = { pkg ->
                        traces = if (pkg in traces) traces - pkg else traces + pkg
                    },
                    onSave = ::save,
                    saving = busy,
                    targetCount = targets.size,
                    traceCount = traces.size,
                )
            }

            if (log.isNotEmpty()) {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun HmaBody(
    apps: List<HmaConfigIo.Installed>,
    targets: Set<String>,
    traces: Set<String>,
    query: String,
    tabIndex: Int,
    onQueryChange: (String) -> Unit,
    onTabChange: (Int) -> Unit,
    onTargetToggle: (String) -> Unit,
    onTraceToggle: (String) -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    targetCount: Int,
    traceCount: Int,
) {
    val wantedTargets = tabIndex == 0
    val accent = if (wantedTargets) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, modifier = Modifier.padding(top = 12.dp)) {
            Tab(
                selected = wantedTargets,
                onClick = { onTabChange(0) },
                text = { Text(stringResource(R.string.hma_tab_targets, targetCount)) },
            )
            Tab(
                selected = !wantedTargets,
                onClick = { onTabChange(1) },
                text = { Text(stringResource(R.string.hma_tab_traces, traceCount)) },
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.hma_search)) },
        )

        val selected = if (wantedTargets) targets else traces
        val visible = apps.filter {
            (query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.pkg.contains(query, ignoreCase = true)) &&
                (query.isNotBlank() || it.pkg in selected ||
                    AppHideRules.classify(it.pkg, it.isSystem).kind ==
                    if (wantedTargets) AppHideRules.Kind.DETECTOR else AppHideRules.Kind.ROOT_TRACE)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(visible, key = { it.pkg }) { app ->
                val kind = AppHideRules.classify(app.pkg, app.isSystem)
                HmaAppRow(
                    label = app.label,
                    pkg = app.pkg,
                    reason = kind.reason,
                    icon = if (wantedTargets) {
                        Icons.Filled.Security
                    } else {
                        Icons.Filled.VisibilityOff
                    },
                    accent = accent,
                    checked = app.pkg in selected,
                    onCheckedChange = {
                        if (wantedTargets) onTargetToggle(app.pkg) else onTraceToggle(app.pkg)
                    },
                )
            }
        }

        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            Icon(Icons.Filled.Save, contentDescription = null)
            Text(
                text = stringResource(R.string.hma_save),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
