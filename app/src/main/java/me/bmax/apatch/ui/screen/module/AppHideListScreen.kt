package me.bmax.apatch.ui.screen.module

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import me.bmax.apatch.util.AppHideManager
import me.bmax.apatch.util.AppHideRules
import me.bmax.apatch.util.ui.showToast

/**
 * One-tap app hide list, using Hide-My-Applist's decision model.
 *
 * Scan classifies every installed app into detectors (banking, payment, DRM,
 * games, checkers) and root traces (Magisk, LSPosed, Shizuku). Detectors form
 * the hide list; root traces stay visible because Tricky Store cannot hide an
 * app from a PackageManager query.
 */
@Composable
@Destination<RootGraph>
fun AppHideListScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val entries = remember { mutableStateListOf<AppHideManager.Entry>() }
    var scanned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var tabIndex by remember { mutableIntStateOf(0) }

    // HMA-style work mode. Blacklist is the default, same as HMA.
    var useWhitelist by remember { mutableStateOf(false) }
    var excludeSystemApps by remember { mutableStateOf(true) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val targets = AppHideManager.importHma(context, uri) { line ->
                scope.launch(Dispatchers.Main.immediate) {
                    log = if (log.isEmpty()) line else "$log\n$line"
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (targets != null) {
                    // Blacklist mode: exactly the imported targets are hidden.
                    useWhitelist = false
                    val targetSet = targets.toSet()
                    if (scanned) {
                        entries.forEachIndexed { i, e ->
                            entries[i] = e.copy(hiddenOverride = targetSet.contains(e.pkg))
                        }
                    }
                    showToast(context, context.getString(R.string.hide_list_imported, targets.size))
                }
                busy = false
            }
        }
    }

    fun runScan() {
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val result = AppHideManager.scan(context)
            withContext(Dispatchers.Main.immediate) {
                entries.clear()
                entries.addAll(result)
                scanned = true
                busy = false
            }
        }
    }

    fun applyNow() {
        if (busy || !scanned) return
        busy = true
        log = ""
        // Blacklist: the hidden set is the list. Whitelist: the list is the
        // visible set, so everything else becomes hidden.
        val hidden = if (useWhitelist) {
            entries.filter { !it.hidden }.map { it.pkg }.toSet()
        } else {
            entries.filter { it.hidden }.map { it.pkg }.toSet()
        }
        scope.launch(Dispatchers.IO) {
            val ok = AppHideManager.apply(context, hidden) { line ->
                scope.launch(Dispatchers.Main.immediate) {
                    log = if (log.isEmpty()) line else "$log\n$line"
                }
            }
            withContext(Dispatchers.Main.immediate) {
                busy = false
                showToast(
                    context,
                    context.getString(
                        if (ok) R.string.hide_list_done else R.string.hide_list_failed
                    )
                )
            }
        }
    }

    fun toggle(entry: AppHideManager.Entry) {
        val i = entries.indexOfFirst { it.pkg == entry.pkg }
        if (i < 0) return
        entries[i] = entry.copy(hiddenOverride = !entry.hidden)
    }

    val hideCount = entries.count { it.hidden }
    val keepCount = entries.size - hideCount

    FolkScaffold(
        title = stringResource(R.string.hide_list_title),
        onBack = dropUnlessResumed { navigator.popBackStack() },
        snackbarHostState = null,
        actions = {
            if (scanned) {
                OutlinedButton(onClick = ::runScan, enabled = !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.hide_list_rescan))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.hide_list_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = ::runScan, enabled = !busy, modifier = Modifier.weight(1f)) {
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
                        text = stringResource(R.string.hide_list_scan),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                    enabled = !busy,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                }
            }

            // Work mode, mirroring HMA's blacklist / whitelist toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (useWhitelist) R.string.hide_list_mode_white
                            else R.string.hide_list_mode_black
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(
                            if (useWhitelist) R.string.hide_list_mode_white_desc
                            else R.string.hide_list_mode_black_desc
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useWhitelist, onCheckedChange = { useWhitelist = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.hide_list_exclude_system),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.hide_list_exclude_system_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = excludeSystemApps, onCheckedChange = { excludeSystemApps = it })
            }

            if (scanned && !busy) {
                TabRow(selectedTabIndex = tabIndex, modifier = Modifier.padding(top = 12.dp)) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        text = { Text(stringResource(R.string.hide_list_black, hideCount)) },
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        text = { Text(stringResource(R.string.hide_list_white, keepCount)) },
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hide_list_search)) },
                )

                val wanted = tabIndex == 0
                val visible = entries.filter {
                    it.hidden == wanted && (
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.pkg.contains(query, ignoreCase = true)
                        )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(visible, key = { it.pkg }) { entry ->
                        HideRow(
                            entry = entry,
                            onToggle = { toggle(entry) },
                        )
                    }
                }

                Button(
                    onClick = ::applyNow,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text(
                        text = stringResource(R.string.hide_list_apply, hideCount),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (busy) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            if (log.isNotEmpty()) {
                Text(
                    text = log,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun HideRow(
    entry: AppHideManager.Entry,
    onToggle: () -> Unit,
) {
    val overridden = entry.hiddenOverride != null
    val rootTrace = entry.kind == AppHideRules.Kind.ROOT_TRACE
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.medium,
        color = if (entry.hidden) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (entry.hidden) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (entry.hidden) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (overridden) {
                        Text(
                            text = stringResource(R.string.hide_list_manual),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = entry.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (rootTrace) {
                        "${entry.reason} · ${stringResource(R.string.hide_list_root_trace)}"
                    } else {
                        entry.reason
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (rootTrace) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }

            Icon(
                imageVector = Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = if (entry.hidden) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
