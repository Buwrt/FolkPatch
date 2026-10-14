package me.bmax.apatch.ui.screen.module

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import me.bmax.apatch.util.HmaConfig
import me.bmax.apatch.util.HmaConfigIo
import me.bmax.apatch.util.ui.showToast
import org.json.JSONObject

private const val BLACKLIST_TEMPLATE = HmaConfig.BLACKLIST_TEMPLATE
private const val WHITELIST_TEMPLATE = HmaConfig.WHITELIST_TEMPLATE

/**
 * Edits Hide-My-Applist's config.json.
 *
 * HMA is caller-centric, so this screen has two sides that must not be mixed
 * up. Protected apps are the scope keys: the apps doing the asking, such as a
 * banking app or a game, and they are what HMA shields. Hidden apps are the
 * blacklist template contents: the root traces HMA keeps out of their sight.
 *
 * Two lists are exposed: a blacklist of apps to hide, and a whitelist of apps
 * to keep visible. The whitelist is applied as an override and wins when a
 * package appears on both sides.
 *
 * The config is read through the root shell, which can fail for reasons the
 * user cannot see, so importing and exporting through the file picker is kept
 * as a fallback path that works regardless of what the root shell can reach.
 */
@Composable
@Destination<RootGraph>
fun AppHideListScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<HmaConfigIo.Installed>>(emptyList()) }
    var config by remember { mutableStateOf<HmaConfig.Config?>(null) }
    var blacks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var whites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var tabIndex by remember { mutableIntStateOf(0) }
    var sourcePath by remember { mutableStateOf("") }

    fun adopt(parsed: HmaConfig.Config, installed: List<HmaConfigIo.Installed>) {
        config = parsed
        apps = installed
        blacks = parsed.template(BLACKLIST_TEMPLATE)?.appList?.toSet() ?: emptySet()
        whites = parsed.template(WHITELIST_TEMPLATE)?.appList?.toSet() ?: emptySet()
        loaded = true
    }

    fun load() {
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val parsed = HmaConfigIo.read()
            val installed = HmaConfigIo.installedApps(context)
            withContext(Dispatchers.Main.immediate) {
                if (parsed == null) {
                    // No config at any known path: dump the environment so the
                    // user can see which HMA is installed and where it looks.
                    log = context.getString(R.string.hma_missing) + "\n" + HmaConfigIo.diagnose()
                } else {
                    sourcePath = parsed.sourcePath
                    adopt(parsed, installed)
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
                JSONObject().apply { put("configVersion", 93) }
            ).apply { sourcePath = HmaConfigIo.resolveWritePath() }
            val installed = HmaConfigIo.installedApps(context)
            val counts = HmaConfigIo.applyOneTap(context, base, true)
            withContext(Dispatchers.Main.immediate) {
                sourcePath = base.sourcePath
                adopt(base, installed)
                log = context.getString(R.string.hma_scanned, counts.first, counts.second)
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
            cfg.ensureTemplate(BLACKLIST_TEMPLATE, isWhitelist = false).setAppList(blacks)
            cfg.ensureTemplate(WHITELIST_TEMPLATE, isWhitelist = true).setAppList(whites)
            HmaConfigIo.syncScope(context, cfg, whites)
            val target = cfg.sourcePath.ifBlank { HmaConfigIo.resolveWritePath() }
            val lines = ArrayList<String>()
            val ok = HmaConfigIo.write(context, cfg, target) { line -> lines.add(line) }
            withContext(Dispatchers.Main.immediate) {
                log = lines.joinToString("\n")
                busy = false
                if (!ok) {
                    log = lines.joinToString("\n") + "\n---- env ----\n" + HmaConfigIo.diagnose()
                }
                showToast(
                    context,
                    context.getString(if (ok) R.string.hma_saved else R.string.hma_failed)
                )
            }
        }
    }

    // Fallback entry points: if the root shell cannot reach the config, the
    // user can hand us the file directly and take the result back out.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val parsed = text?.let { HmaConfig.parse(it) }
            val installed = HmaConfigIo.installedApps(context)
            withContext(Dispatchers.Main.immediate) {
                if (parsed == null) {
                    log = context.getString(R.string.hma_import_fail)
                } else {
                    sourcePath = ""
                    adopt(parsed, installed)
                    log = context.getString(R.string.hma_import_ok, parsed.scope.size)
                }
                busy = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val cfg = config ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)
                    ?.use { it.write(cfg.toJson().toByteArray()) }
            }.isSuccess
            withContext(Dispatchers.Main.immediate) {
                showToast(
                    context,
                    context.getString(if (ok) R.string.hma_export_ok else R.string.hma_export_fail)
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

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Text(
                        text = stringResource(R.string.hma_import),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = { exportLauncher.launch("hma-config.json") },
                    enabled = !busy && loaded,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text(
                        text = stringResource(R.string.hma_export),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (loaded && sourcePath.isNotBlank()) {
                Text(
                    text = stringResource(R.string.hma_source, sourcePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .fillMaxWidth(),
                )
            } else if (loaded) {
                HmaBody(
                    apps = apps,
                    blacks = blacks,
                    whites = whites,
                    query = query,
                    tabIndex = tabIndex,
                    onQueryChange = { query = it },
                    onTabChange = { tabIndex = it },
                    onBlackToggle = { pkg ->
                        blacks = if (pkg in blacks) blacks - pkg else blacks + pkg
                    },
                    onWhiteToggle = { pkg ->
                        whites = if (pkg in whites) whites - pkg else whites + pkg
                    },
                    onSave = ::save,
                    saving = busy,
                )
            }

            if (log.isNotEmpty()) {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("hma log", log))
                        showToast(context, context.getString(R.string.hma_log_copied))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Filled.Code, contentDescription = null)
                    Text(
                        text = stringResource(R.string.hma_copy_log),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
