package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.generated.destinations.AppHideListScreenDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.APApplication
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.KeyEventBlocker
import me.bmax.apatch.ui.component.folk.FolkScaffold
import me.bmax.apatch.ui.theme.tokens.FolkMotion
import me.bmax.apatch.util.Xiaocaiye

private const val MAX_LOG_CHARS = 100_000

private const val PREF_AUTO_UPDATE = "xiaocaiye_auto_update"
private const val PREF_LAST_REFRESH = "xiaocaiye_last_refresh"
private const val AUTO_UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L

/**
 * The XiaoCaiYe helper, built into the manager.
 *
 * The payload ships as an APK asset and is published to the module directory on
 * first use, so no APM module has to be flashed. Volume keys drive it exactly
 * like the module's own action.sh: Volume Up refreshes the keybox, Volume Down
 * refreshes the Tricky Store target list and the APatch exclusion list.
 *
 * On top of that the page shows how long the installed keybox stays valid and
 * whether the keybox host is reachable, and can refresh the keybox on its own
 * when it gets old.
 */
@Composable
@Destination<RootGraph>(wrappers = [me.bmax.apatch.ui.navigation.NavContainerTransformWrapper::class])
fun XiaocaiyeScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val prefs = remember { APApplication.sharedPreferences }

    var log by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }

    var keybox by remember { mutableStateOf<Xiaocaiye.KeyboxStatus?>(null) }
    var reachable by remember { mutableStateOf<Boolean?>(null) }
    var autoUpdate by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO_UPDATE, false)) }

    val hintUp = stringResource(R.string.xiaocaiye_vol_up)
    val hintDown = stringResource(R.string.xiaocaiye_vol_down)
    val doneLabel = stringResource(R.string.xiaocaiye_done)
    val failLabel = stringResource(R.string.xiaocaiye_failed)

    fun append(line: String) {
        // Keep the saveable state under the Binder transaction limit.
        val next = if (log.isEmpty()) line else "$log\n$line"
        log = if (next.length > MAX_LOG_CHARS) next.takeLast(MAX_LOG_CHARS) else next
    }

    fun refreshStatus() {
        scope.launch(Dispatchers.IO) {
            val box = runCatching { Xiaocaiye.keyboxStatus() }
                .getOrElse { Xiaocaiye.KeyboxStatus(Xiaocaiye.KeyboxState.UNKNOWN) }
            val net = runCatching { Xiaocaiye.networkReachable() }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                keybox = box
                reachable = net
            }
        }
    }

    fun trigger(option: Xiaocaiye.Option) {
        if (busy) return
        busy = true
        append("> ${if (option == Xiaocaiye.Option.UPDATE_KEYBOX) hintUp else hintDown}")
        scope.launch(Dispatchers.IO) {
            val ok = Xiaocaiye.run(context, option) { line ->
                runCatching {
                    scope.launch(Dispatchers.Main.immediate) { append(line) }
                }
            }
            if (ok && option == Xiaocaiye.Option.UPDATE_KEYBOX) {
                prefs.edit().putLong(PREF_LAST_REFRESH, System.currentTimeMillis()).apply()
            }
            val box = runCatching { Xiaocaiye.keyboxStatus() }
                .getOrElse { Xiaocaiye.KeyboxStatus(Xiaocaiye.KeyboxState.UNKNOWN) }
            withContext(Dispatchers.Main.immediate) {
                append(if (ok) doneLabel else failLabel)
                keybox = box
                busy = false
            }
        }
    }

    fun diagnose() {
        if (busy) return
        busy = true
        log = ""
        append("> diagnose")
        scope.launch(Dispatchers.IO) {
            Xiaocaiye.diagnose(context) { line ->
                runCatching {
                    scope.launch(Dispatchers.Main.immediate) { append(line) }
                }
            }
            withContext(Dispatchers.Main.immediate) { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        refreshStatus()
        // Refresh on entry only when the keybox has not been touched for a while,
        // so opening the page repeatedly does not hammer the host.
        val last = prefs.getLong(PREF_LAST_REFRESH, 0L)
        val due = autoUpdate &&
            (last == 0L || System.currentTimeMillis() - last >= AUTO_UPDATE_INTERVAL_MS)
        if (due) trigger(Xiaocaiye.Option.UPDATE_KEYBOX)
    }

    FolkScaffold(
        title = stringResource(R.string.xiaocaiye_title),
        onBack = dropUnlessResumed { navigator.popBackStack() },
        snackbarHostState = null,
        actions = {
            androidx.compose.material3.TextButton(
                onClick = dropUnlessResumed { navigator.navigate(AppHideListScreenDestination) },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.hide_list_title))
            }
            androidx.compose.material3.TextButton(onClick = ::diagnose, enabled = !busy) {
                Text(stringResource(R.string.xiaocaiye_diagnose))
            }
        },
    ) { innerPadding ->
        // Swallow the volume keys so they drive the helper instead of the volume.
        KeyEventBlocker { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.VolumeUp -> trigger(Xiaocaiye.Option.UPDATE_KEYBOX)
                    Key.VolumeDown -> trigger(Xiaocaiye.Option.UPDATE_PACKAGES)
                    else -> {}
                }
            }
            event.key == Key.VolumeUp || event.key == Key.VolumeDown
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
        ) {
            Text(
                text = stringResource(R.string.xiaocaiye_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )

            StatusCard(
                keybox = keybox,
                reachable = reachable,
                autoUpdate = autoUpdate,
                busy = busy,
                onAutoUpdateChange = { checked ->
                    autoUpdate = checked
                    prefs.edit().putBoolean(PREF_AUTO_UPDATE, checked).apply()
                },
                onRefreshNow = { trigger(Xiaocaiye.Option.UPDATE_KEYBOX) },
                onRetry = ::refreshStatus,
            )

            androidx.compose.material3.FilledTonalButton(
                onClick = dropUnlessResumed { navigator.navigate(AppHideListScreenDestination) },
                enabled = !busy,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Text(stringResource(R.string.hide_list_title))
            }
            Text(
                text = hintUp,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                text = hintDown,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 2.dp),
            )


            val statusText = if (busy) {
                stringResource(R.string.xiaocaiye_running)
            } else {
                log
            }
            Text(
                modifier = Modifier.padding(vertical = 12.dp),
                text = statusText,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = FontFamily.Monospace,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
            )
        }

        LaunchedEffect(log) {
            scrollState.animateScrollTo(
                scrollState.maxValue,
                animationSpec = FolkMotion.ScrollIntoView,
            )
        }
    }
}

/** Keybox validity, host reachability and the auto-refresh switch. */
@Composable
private fun StatusCard(
    keybox: Xiaocaiye.KeyboxStatus?,
    reachable: Boolean?,
    autoUpdate: Boolean,
    busy: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    onRefreshNow: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        // Tap the card to re-read both values.
        onClick = { if (!busy) onRetry() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.xiaocaiye_status),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))

            val (keyboxText, keyboxTone) = keyboxText(keybox)
            StatusRow(
                icon = Icons.Outlined.VpnKey,
                label = stringResource(R.string.xiaocaiye_keybox),
                value = keyboxText,
                tone = keyboxTone,
            )
            Spacer(Modifier.height(10.dp))
            StatusRow(
                icon = Icons.Outlined.CloudQueue,
                label = stringResource(R.string.xiaocaiye_network),
                value = when (reachable) {
                    true -> stringResource(R.string.xiaocaiye_network_ok)
                    false -> stringResource(R.string.xiaocaiye_network_fail)
                    null -> stringResource(R.string.xiaocaiye_network_checking)
                },
                tone = if (reachable == false) StatusTone.Bad else StatusTone.Neutral,
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.xiaocaiye_auto_update),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.xiaocaiye_auto_update_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoUpdate, onCheckedChange = onAutoUpdateChange)
            }

            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onRefreshNow,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.xiaocaiye_refresh_now))
            }
        }
    }
}

private enum class StatusTone { Good, Warn, Bad, Neutral }

@Composable
private fun keyboxText(status: Xiaocaiye.KeyboxStatus?): Pair<String, StatusTone> {
    return when {
        status == null -> stringResource(R.string.xiaocaiye_keybox_checking) to StatusTone.Neutral
        status.state == Xiaocaiye.KeyboxState.MISSING ->
            stringResource(R.string.xiaocaiye_keybox_missing) to StatusTone.Warn
        status.state == Xiaocaiye.KeyboxState.UNKNOWN ->
            stringResource(R.string.xiaocaiye_keybox_unknown) to StatusTone.Neutral
        status.state == Xiaocaiye.KeyboxState.EXPIRED ->
            stringResource(R.string.xiaocaiye_keybox_expired) to StatusTone.Bad
        status.daysLeft != null && status.state == Xiaocaiye.KeyboxState.EXPIRING ->
            stringResource(R.string.xiaocaiye_keybox_days, status.daysLeft) to StatusTone.Warn
        status.daysLeft != null ->
            stringResource(R.string.xiaocaiye_keybox_days, status.daysLeft) to StatusTone.Good
        else -> stringResource(R.string.xiaocaiye_keybox_unknown) to StatusTone.Neutral
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    label: String,
    value: String,
    tone: StatusTone,
) {
    val color = when (tone) {
        StatusTone.Good -> MaterialTheme.colorScheme.primary
        StatusTone.Warn -> MaterialTheme.colorScheme.tertiary
        StatusTone.Bad -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(7.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                )
            }
        }
    }
}
