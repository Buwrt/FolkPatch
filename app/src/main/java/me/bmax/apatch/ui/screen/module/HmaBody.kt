package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R
import me.bmax.apatch.util.AppHideRules
import me.bmax.apatch.util.HmaConfigIo

/**
 * The two editable sides of an HMA config.
 *
 * Tab 0 is the protected side (scope keys, tinted primary) and tab 1 is the
 * hidden side (blacklist template contents, tinted error). Both show the same
 * installed app list but filter on a different [AppHideRules.Kind] so each tab
 * defaults to the apps that belong on it.
 */
@Composable
fun HmaBody(
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
) {
    val wantedTargets = tabIndex == 0
    val accent = if (wantedTargets) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val wantedKind = if (wantedTargets) {
        AppHideRules.Kind.DETECTOR
    } else {
        AppHideRules.Kind.ROOT_TRACE
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, modifier = Modifier.padding(top = 12.dp)) {
            Tab(
                selected = wantedTargets,
                onClick = { onTabChange(0) },
                text = { Text(stringResource(R.string.hma_tab_targets, targets.size)) },
            )
            Tab(
                selected = !wantedTargets,
                onClick = { onTabChange(1) },
                text = { Text(stringResource(R.string.hma_tab_traces, traces.size)) },
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
            if (query.isBlank()) {
                it.pkg in selected || AppHideRules.classify(it.pkg, it.isSystem).kind == wantedKind
            } else {
                it.label.contains(query, ignoreCase = true) ||
                    it.pkg.contains(query, ignoreCase = true)
            }
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
