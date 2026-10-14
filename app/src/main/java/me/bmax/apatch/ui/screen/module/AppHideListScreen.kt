package me.bmax.apatch.ui.screen.module

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
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
import me.bmax.apatch.util.HideListClassifier
import me.bmax.apatch.util.Xiaocaiye

/**
 * 小助手 · 应用隐藏黑白名单。
 *
 * 用户只需点「一键扫描」，本机全部应用会被规则库自动分成
 * 黑名单（隐藏）/ 白名单（不隐藏），可搜索后逐个微调，再点「应用」写入生效。
 */
@Destination<RootGraph>
@Composable
fun AppHideListScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 分类结果含可变字段且不可 Parcelable，用 remember 保存
    var results by remember { mutableStateOf<List<HideListClassifier.Result>>(emptyList()) }
    var scanned by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = 黑名单, 1 = 白名单
    var query by rememberSaveable { mutableStateOf("") }
    var log by rememberSaveable { mutableStateOf("") }

    val scanLabel = stringResource(R.string.hide_list_scan)
    val scanningLabel = stringResource(R.string.hide_list_scanning)
    val applyLabel = stringResource(R.string.hide_list_apply)
    val applyingLabel = stringResource(R.string.hide_list_applying)
    val doneLabel = stringResource(R.string.hide_list_done)
    val failLabel = stringResource(R.string.hide_list_failed)

    fun scan() {
        if (busy) return
        busy = true
        log = ""
        scope.launch(Dispatchers.IO) {
            val apps = HideListClassifier.scan(context.packageManager)
            val classified = HideListClassifier.classifyAll(apps)
            withContext(Dispatchers.Main.immediate) {
                results = classified
                scanned = true
                busy = false
            }
        }
    }

    fun apply() {
        if (busy || !scanned) return
        busy = true
        log = ""
        val black = results
            .filter { it.kind == HideListClassifier.Kind.BLACK }
            .map { it.app.pkg to it.app.uid }
        val white = results
            .filter { it.kind == HideListClassifier.Kind.WHITE }
            .map { it.app.pkg }
        scope.launch(Dispatchers.IO) {
            val ok = Xiaocaiye.applyHideList(context, black, white) { line ->
                runCatching {
                    scope.launch(Dispatchers.Main.immediate) { log += line + "\n" }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                log += if (ok) doneLabel else failLabel
                busy = false
            }
        }
    }

    fun toggle(item: HideListClassifier.Result) {
        item.kind = if (item.kind == HideListClassifier.Kind.BLACK) {
            HideListClassifier.Kind.WHITE
        } else {
            HideListClassifier.Kind.BLACK
        }
        item.source = HideListClassifier.Source.MANUAL
        // 结果对象自身不可观察，换一个新列表实例来触发重组
        results = ArrayList(results)
    }

    val blackCount = results.count { it.kind == HideListClassifier.Kind.BLACK }
    val whiteCount = results.count { it.kind == HideListClassifier.Kind.WHITE }

    val wantKind = if (tab == 0) HideListClassifier.Kind.BLACK else HideListClassifier.Kind.WHITE
    val q = query.trim().lowercase()
    val visible = remember(results, tab, query) {
        results.filter {
            it.kind == wantKind &&
                (q.isEmpty() ||
                    it.app.label.lowercase().contains(q) ||
                    it.app.pkg.lowercase().contains(q))
        }
    }

    FolkScaffold(
        title = stringResource(R.string.hide_list_title),
        onBack = dropUnlessResumed { navigator.popBackStack() },
        snackbarHostState = null,
        actions = {
            IconButton(onClick = ::scan, enabled = !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = scanLabel)
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
                modifier = Modifier.padding(vertical = 10.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::scan, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy && !scanned) scanningLabel else scanLabel)
                }
                Button(
                    onClick = ::apply,
                    enabled = !busy && scanned && results.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy && scanned) applyingLabel else applyLabel)
                }
            }

            if (scanned) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListTab(
                        text = stringResource(R.string.hide_list_black, blackCount),
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    ListTab(
                        text = stringResource(R.string.hide_list_white, whiteCount),
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.hide_list_search)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    shape = RoundedCornerShape(14.dp),
                )

                Spacer(Modifier.height(8.dp))
                if (visible.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.hide_list_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visible, key = { it.app.pkg }) { item ->
                            AppHideRow(
                                item = item,
                                onToggle = { toggle(item) },
                                moveToWhite = stringResource(R.string.hide_list_move_to_white),
                                moveToBlack = stringResource(R.string.hide_list_move_to_black),
                                manualLabel = stringResource(R.string.hide_list_manual),
                            )
                        }
                    }
                }

                if (log.isNotBlank()) {
                    Text(
                        text = log,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.hide_list_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHideRow(
    item: HideListClassifier.Result,
    onToggle: () -> Unit,
    moveToWhite: String,
    moveToBlack: String,
    manualLabel: String,
) {
    val isBlack = item.kind == HideListClassifier.Kind.BLACK
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isBlack) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 列表可能上百项，逐个 loadIcon 太重，用首字母占位保持滚动流畅
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.app.label.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.app.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.source == HideListClassifier.Source.MANUAL) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = manualLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = item.app.pkg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isBlack) moveToWhite else moveToBlack,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 黑名单 / 白名单切换标签，避免依赖可能处于实验期的 Material3 组件。 */
@Composable
private fun ListTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            icon()
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
