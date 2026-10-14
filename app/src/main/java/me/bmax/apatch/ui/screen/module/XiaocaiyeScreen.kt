package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.KeyEventBlocker
import me.bmax.apatch.ui.component.folk.FolkScaffold
import me.bmax.apatch.ui.theme.tokens.FolkMotion
import me.bmax.apatch.util.Xiaocaiye

private const val MAX_LOG_CHARS = 100_000

/**
 * The XiaoCaiYe helper, built into the manager.
 *
 * The payload ships as an APK asset and is published to the module directory on
 * first use, so no APM module has to be flashed. Volume keys drive it exactly
 * like the module's own action.sh: Volume Up refreshes the keybox, Volume Down
 * refreshes the Tricky Store target list and the APatch exclusion list.
 */
@Composable
@Destination<RootGraph>
fun XiaocaiyeScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var log by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }

    val hintUp = stringResource(R.string.xiaocaiye_vol_up)
    val hintDown = stringResource(R.string.xiaocaiye_vol_down)
    val doneLabel = stringResource(R.string.xiaocaiye_done)
    val failLabel = stringResource(R.string.xiaocaiye_failed)

    fun append(line: String) {
        // Keep the saveable state under the Binder transaction limit.
        val next = if (log.isEmpty()) line else "$log\n$line"
        log = if (next.length > MAX_LOG_CHARS) next.takeLast(MAX_LOG_CHARS) else next
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
            withContext(Dispatchers.Main.immediate) {
                append(if (ok) doneLabel else failLabel)
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
