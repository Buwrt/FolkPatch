package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
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
 * The two lists the user edits.
 *
 * Both tabs show the same installed apps and keep their own checkbox state, so
 * the two lists are independent. Blacklist means "hide this from the target
 * app"; whitelist means "keep this visible", and it is checked first in HMA, so
 * it wins when the same package appears on both sides.
 *
 * The blacklist tab pre-filters to root traces because those are what actually
 * need hiding. The whitelist starts empty, since nothing is exempt by default,
 * and the user searches to add exceptions.
 */
@Composable
fun HmaBody(
    apps: List<HmaConfigIo.Installed>,
    blacks: Set<String>,
    whites: Set<String>,
    query: String,
    tabIndex: Int,
    onQueryChange: (String) -> Unit,
    onTabChange: (Int) -> Unit,
    onBlackToggle: (String) -> Unit,
    onWhiteToggle: (String) -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
) {
    val isBlack = tabIndex == 0
    val accent = if (isBlack) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val selected = if (isBlack) blacks else whites

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, modifier = Modifier.padding(top = 12.dp)) {
            Tab(
                selected = isBlack,
                onClick = { onTabChange(0) },
                text = { Text(stringResource(R.string.hma_tab_black, blacks.size)) },
            )
            Tab(
                selected = !isBlack,
                onClick = { onTabChange(1) },
                text = { Text(stringResource(R.string.hma_tab_white, whites.size)) },
            )
        }

        Text(
            text = stringResource(
                if (isBlack) R.string.hma_black_hint else R.string.hma_white_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.hma_search)) },
        )

        val visible = apps.filter {
            if (query.isBlank()) {
                if (isBlack) {
                    it.pkg in blacks ||
                        AppHideRules.classify(it.pkg, it.isSystem).kind ==
                        AppHideRules.Kind.ROOT_TRACE
                } else {
                    it.pkg in whites
                }
            } else {
                it.label.contains(query, ignoreCase = true) ||
                    it.pkg.contains(query, ignoreCase = true)
            }
        }

        if (!isBlack && query.isBlank() && whites.isEmpty()) {
            Text(
                text = stringResource(R.string.hma_white_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 8.dp),
            )
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
                    icon = if (isBlack) Icons.Filled.Block else Icons.Filled.Check,
                    accent = accent,
                    checked = app.pkg in selected,
                    onCheckedChange = {
                        if (isBlack) onBlackToggle(app.pkg) else onWhiteToggle(app.pkg)
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
