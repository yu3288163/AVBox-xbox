package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.github.avbox.osc.util.LocalScriptSource
import java.io.File
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import android.content.Intent
import com.github.avbox.osc.ui.MainActivity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.avbox.osc.R
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.ui.ConfigManageActivity
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.ui.components.CapsuleSegmentedButton
import com.github.avbox.osc.ui.components.LoadState
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.LoadStateBox
import com.github.avbox.osc.ui.components.LocalSheetDismiss
import com.github.avbox.osc.ui.components.LocalSheetDismissThen
import com.github.avbox.osc.ui.components.SegmentOption
import com.github.avbox.osc.ui.components.SettingsCard
import com.github.avbox.osc.ui.components.SettingsCardPosition
import com.github.avbox.osc.ui.components.SettingsGroup
import com.github.avbox.osc.ui.components.clearFocusOnDrag
import com.github.avbox.osc.ui.components.RowLeadingIcon
import com.github.avbox.osc.ui.components.SearchField
import com.github.avbox.osc.ui.components.SettingsOptionRow
import com.github.avbox.osc.ui.components.SettingsSwitch
import com.github.avbox.osc.ui.components.SettingsSwitchRow
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.ui.components.glassSurface
import com.github.avbox.osc.ui.theme.cardContainer
import com.github.avbox.osc.util.HistoryHelper
import com.github.avbox.osc.ui.components.marqueeOnOverflow

private fun badgeText(name: String, url: String, emptyText: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> emptyText
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit, initialOpenRepo: Boolean = false) {
    val context = LocalContext.current
    val vm: ConfigManageViewModel = viewModel(factory = ConfigManageViewModel.defaultFactory())
    var mode by rememberSaveable { mutableStateOf(ConfigMode.Vod) }
    var addDialogOpen by remember { mutableStateOf(false) }
    var localScriptDialog by remember { mutableStateOf(false) }
    var repoSheetOpen by remember { mutableStateOf(false) }
    var siteManageDir by rememberSaveable { mutableStateOf<String?>(null) }
    val vodItems by vm.vodItems.collectAsState()
    val liveItems by vm.liveItems.collectAsState()
    val activeUrl by vm.activeUrl.collectAsState()
    val liveActiveUrl by vm.liveActiveUrl.collectAsState()
    val liveFollow by vm.liveFollow.collectAsState()
    val disabledUrls by vm.disabledUrls.collectAsState()
    val pendingSwitch by vm.pendingSwitch.collectAsState()
    val selected by vm.selected.collectAsState()
    val manageMode by vm.manageMode.collectAsState()
    val editTarget by vm.editTarget.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()
    val tmdbState by vm.tmdbState().collectAsState()
    val tmdbTesting by vm.tmdbTesting().collectAsState()

    val isVod = mode == ConfigMode.Vod
    val isTmdb = mode == ConfigMode.Tmdb
    val currentItems = if (isVod) vodItems else liveItems

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    LaunchedEffect(mode) {
        vm.onModeChanged()
        repoSheetOpen = false
    }

    BackHandler(enabled = manageMode) { vm.exitManageMode() }

    val canSwitchRepo = !isTmdb && if (isVod) {
        HistoryHelper.isApiLineUrl(activeUrl)
    } else {
        ApiConfig.get().isLiveApiLineMode() && HistoryHelper.isLiveApiLineUrl(liveActiveUrl)
    }

    val repoEntries = if (isVod) HistoryHelper.getApiLines() else HistoryHelper.liveApiLines()

    val repoActiveUrl = if (isVod) activeUrl else liveActiveUrl

    // 从首页「订阅源」弹窗点「切换仓库」进来时，等线路状态就绪后自动展开换仓面板。
    var repoAutoOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialOpenRepo, canSwitchRepo, repoEntries.size) {
        if (initialOpenRepo && canSwitchRepo && repoEntries.isNotEmpty() && !repoAutoOpened) {
            repoAutoOpened = true
            repoSheetOpen = true
        }
    }

    val focusManager = LocalFocusManager.current

    val noSourceText = stringResource(R.string.config_no_source)
    val vodBadge = remember(vodItems, activeUrl, noSourceText) {
        badgeText(
            vodItems.firstOrNull { parseSubscribe(it).url == activeUrl }?.let { parseSubscribe(it).name }.orEmpty(),
            activeUrl,
            noSourceText,
        )
    }
    val siteDir = siteManageDir
    if (siteDir != null) {
        BackHandler { siteManageDir = null }
        SiteManageScreen(
            dir = siteDir,
            onBack = { siteManageDir = null },
            onReloadSource = { vm.reloadActiveSource(isVod) },
        )
        return
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_config_manage),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = { if (manageMode) vm.exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
            AnimatedContent(
                targetState = manageMode && currentItems.isNotEmpty() && !isTmdb,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configTopAction",
            ) { managing ->
                if (managing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = stringResource(R.string.common_edit),
                            enabled = selected.size == 1,
                            onClick = { vm.openEdit(selected.firstOrNull()?.let { parseSubscribe(it) }) },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete),
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                vm.deleteSelected(
                                    if (isVod) SubscribeMode.Vod else SubscribeMode.Live,
                                )
                            },
                        )
                        val onlyLocal = selected.size == 1 &&
                            LocalScriptSource.isLocalScript(parseSubscribe(selected.first()).url)
                        if (onlyLocal) {
                            ManageActionIcon(
                                iconRes = R.drawable.ic_site_manage,
                                contentDescription = stringResource(R.string.config_site_manage),
                                enabled = true,
                                onClick = {
                                    siteManageDir = LocalScriptSource.path(parseSubscribe(selected.first()).url)
                                },
                            )
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isTmdb) {
                            if (canSwitchRepo) {
                                // 多仓订阅：把「换仓」做成带文字的按钮，避免只靠图标看不出入口。
                                Row(
                                    modifier = Modifier
                                        .height(40.dp)
                                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                                        .clickable { repoSheetOpen = true }
                                        .padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_detail_switch_source),
                                        contentDescription = stringResource(R.string.config_switch_repo),
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = stringResource(R.string.config_switch_repo_count, repoEntries.size),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                            }
                            TopBarActionBox(
                                iconRes = R.drawable.ic_subscribe_add,
                                contentDescription = if (isVod) {
                                    stringResource(R.string.config_add_subscribe)
                                } else {
                                    stringResource(R.string.config_add_live_source)
                                },
                                onClick = { addDialogOpen = true },
                            )
                            TopBarActionBox(
                                iconRes = R.drawable.ic_import,
                                contentDescription = stringResource(R.string.config_add_local_script),
                                onClick = { localScriptDialog = true },
                            )
                        }
                    }
                }
            }
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus() })
                },
        ) {
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.common_vod),
                        value = ConfigMode.Vod,
                        iconPainter = painterResource(R.drawable.ic_config_vod),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.common_tmdb),
                        value = ConfigMode.Tmdb,
                        iconPainter = painterResource(R.drawable.ic_config_tmdb),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.common_live),
                        value = ConfigMode.Live,
                        iconPainter = painterResource(R.drawable.ic_config_live),
                    ),
                ),
                selectedValue = mode,
                onOptionSelected = { mode = it },
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = topPad + 8.dp),
            )
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val toRight = targetState.ordinal > initialState.ordinal
                    (
                        slideInHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) full / 4 else -full / 4
                        } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
                        ).togetherWith(
                        slideOutHorizontally(spring(stiffness = Spring.StiffnessMedium)) { full ->
                            if (toRight) -full / 4 else full / 4
                        } + fadeOut(spring(stiffness = Spring.StiffnessMedium))
                    )
                },
                label = "configSegment",
            ) { m ->
                val vodMode = m == ConfigMode.Vod
                val configItems = if (vodMode) vodItems else liveItems
                if (m == ConfigMode.Tmdb) {
                    ConfigTmdbScreen(
                        state = tmdbState,
                        actions = remember(vm) { vm.tmdbActions() },
                        testing = tmdbTesting,
                    )
                } else if (configItems.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!vodMode) {
                            FollowVodCard(
                                checked = liveFollow,
                                subtitle = if (activeUrl.isEmpty()) {
                                    stringResource(R.string.config_no_vod_source)
                                } else {
                                    stringResource(R.string.config_current_vod_source, vodBadge)
                                },
                                onFollow = { vm.followLiveNow() },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                            )
                        }
                        LoadStateBox(
                            state = LoadState.Empty,
                            emptyText = stringResource(
                                if (vodMode) R.string.config_empty_subscribe
                                else R.string.config_empty_live_source
                            ),
                            errorText = "",
                            retryText = "",
                            emptyIconRes = R.drawable.ic_empty_record,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                    }
                } else {
                    val ordered = remember(configItems, activeUrl, liveActiveUrl, liveFollow, vodMode) {
                        configItems.sortedByDescending {
                            val url = parseSubscribe(it).url
                            if (vodMode) url == activeUrl else !liveFollow && url == liveActiveUrl
                        }
                    }
                    val cardContent: @Composable (String, Modifier) -> Unit = { value, cardModifier ->
                        val item = parseSubscribe(value)
                        val ctx = LocalContext.current
                        val goHome: () -> Unit = {
                            ctx.startActivity(
                                Intent(ctx, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_GO_HOME, true)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    )
                            )
                            (ctx as? Activity)?.finish()
                        }
                        val inUse = if (isVod) {
                            item.url == activeUrl || HistoryHelper.isApiLineSourceOf(item.url, activeUrl)
                        } else {
                            !liveFollow && (
                                item.url == liveActiveUrl ||
                                    HistoryHelper.isLiveApiLineSourceOf(item.url, liveActiveUrl)
                                )
                        }
                        // 多仓订阅在卡片上直接写明当前仓与仓数，不用猜哪个按钮能切。
                        val repoSummary = if (canSwitchRepo && inUse && repoEntries.isNotEmpty()) {
                            val current = repoEntries
                                .firstOrNull { HistoryHelper.apiLineUrl(it) == repoActiveUrl }
                                ?.let { HistoryHelper.apiLineName(it) }
                                .orEmpty()
                            stringResource(R.string.config_current_repo, current, repoEntries.size)
                        } else {
                            null
                        }
                        SubscribeCard(
                            modifier = cardModifier,
                            item = item,
                            repoSummary = repoSummary,
                            onRepoClick = if (canSwitchRepo && inUse) ({ repoSheetOpen = true }) else null,
                            active = inUse,
                            disabled = item.url in disabledUrls,
                            manageMode = manageMode,
                            selected = value in selected,
                            onClick = {
                                if (manageMode) {
                                    vm.toggleSelected(value)
                                } else if (inUse) {
                                    // 已经在这个接口上，再点一次就是“用这个接口去看”，直接回首页宫格
                                    goHome()
                                } else {
                                    vm.requestSwitch(item, isVod)
                                }
                            },
                            onLongClick = { vm.longPressSelect(value) },
                            onCheckedChange = { checked ->
                                if (checked) {
                                    vm.requestSwitch(item, isVod)
                                } else if (!isVod) {
                                    vm.followLiveNow()
                                } else {
                                    goHome()
                                }
                            },
                        )
                    }
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier
                            .fillMaxSize()
                            .clearFocusOnDrag(focusManager),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 24.dp,
                            bottom = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!vodMode) {
                            item(key = "Live#follow") {
                                FollowVodCard(
                                    checked = liveFollow,
                                    subtitle = if (activeUrl.isEmpty()) {
                                        stringResource(R.string.config_no_vod_source)
                                    } else {
                                        stringResource(R.string.config_current_vod_source, vodBadge)
                                    },
                                    onFollow = { vm.followLiveNow() },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        items(ordered, key = { it }) { value -> cardContent(value, Modifier.animateItem()) }
                    }
                }
            }
        }
    }

    val editing = editTarget
    if (localScriptDialog) {
        AddScriptDialog(
            title = stringResource(R.string.config_add_local_script),
            onDismiss = { localScriptDialog = false },
            onSave = { name, path ->
                localScriptDialog = false
                if (path.isNotEmpty()) {
                    val count = LocalScriptSource.count(path)
                    if (count <= 0) {
                        Toast.makeText(context, context.getString(R.string.toast_local_script_empty), Toast.LENGTH_LONG).show()
                    } else if (vm.addLocalSource(name.ifBlank { File(path).name }, LocalScriptSource.SCHEME + path)) {
                        Toast.makeText(context, context.getString(R.string.toast_local_script_added, count), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onPickFolder = { onPicked ->
                (context as? ConfigManageActivity)?.launchFolderPick { picked, pickedName ->
                    onPicked(picked, pickedName)
                }
            },
        )
    }

    if (addDialogOpen || editing != null) {
        AddSubscribeDialog(
            title = if (editing != null) {
                if (isVod) stringResource(R.string.config_edit_subscribe) else stringResource(R.string.config_edit_live_source)
            } else {
                if (isVod) stringResource(R.string.config_add_subscribe) else stringResource(R.string.config_add_live_source)
            },
            urlSupportingText = if (isVod) "" else stringResource(R.string.config_live_source_hint),
            initialName = editing?.name.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = {
                addDialogOpen = false
                vm.closeEdit()
            },
            onSave = { name, url ->
                if (editing != null) vm.commitEdit(isVod, editing, name, url) else vm.commitAdd(isVod, name, url)
                addDialogOpen = false
            },
            onPickFile = { onPicked ->
                (context as? ConfigManageActivity)?.launchLocalConfig { pickedName, pickedApi ->
                    onPicked(pickedName, pickedApi)
                }
            },
            // 点播订阅支持直接选目录：把目录里所有 py/js 一次性变成一份本地脚本订阅源。
            // 目录选择、扫描、生成 json 全在 LocalScriptSource 里，这里只负责把 lscript:// 链接填进表单。
            onPickFolder = if (isVod) ({ onPicked: (String, String) -> Unit ->
                (context as? ConfigManageActivity)?.launchFolderPick { path, pickedName ->
                    val count = LocalScriptSource.count(path)
                    if (count <= 0) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_local_script_empty),
                            Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        onPicked(path, pickedName)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_local_script_added, count),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }) else null,
        )
    }

    val pending = pendingSwitch
    if (pending != null) {
        AVBoxAlertDialog(
            onDismissRequest = { vm.cancelPendingSwitch() },
            title = { Text(stringResource(R.string.dialog_source_disabled_title)) },
            text = {
                Text(stringResource(R.string.dialog_source_disabled_message, pending.item.name))
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = { dismissThen { vm.enableAndSwitch() } }) {
                    Text(stringResource(R.string.dialog_source_disabled_confirm))
                }
            },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (repoSheetOpen) {
        RepoSwitchSheet(
            entries = repoEntries,
            activeUrl = repoActiveUrl,
            disabledUrls = disabledUrls,
            onDismiss = { repoSheetOpen = false },
            onSelect = { url ->
                val name = HistoryHelper.apiLineName(
                    repoEntries.firstOrNull { HistoryHelper.apiLineUrl(it) == url }.orEmpty(),
                )
                vm.requestSwitch(SubscribeSource(name, url), isVod)
                if (vm.pendingSwitch.value != null) repoSheetOpen = false
            },
        )
    }
}

@Composable
private fun RepoSwitchSheet(
    entries: List<String>,
    activeUrl: String,
    disabledUrls: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val dismissAnimated = LocalSheetDismiss.current
    var accepted by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val visible = remember(entries, query) {
        val q = query.trim()
        if (q.isEmpty()) entries else entries.filter {
            HistoryHelper.apiLineName(it).contains(q, ignoreCase = true) ||
                HistoryHelper.apiLineUrl(it).contains(q, ignoreCase = true)
        }
    }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_switch_repo_count, entries.size),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // 多仓动辄几十条，给个搜索框直接按仓名定位（例如「备用」）。
        if (entries.size > 8) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                hint = stringResource(R.string.config_repo_search_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            visible.forEachIndexed { index, entry ->
                val url = HistoryHelper.apiLineUrl(entry)
                SettingsCard(
                    position = when {
                        visible.size <= 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == visible.size - 1 -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = HistoryHelper.apiLineName(entry),
                        selected = url == activeUrl,
                        onClick = onClick@{
                            if (accepted) return@onClick
                            accepted = true
                            if (url.isNotEmpty() && url != activeUrl) onSelect(url)
                            dismissAnimated()
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (url in disabledUrls) {
                                    DisabledSourceTag()
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 180.dp)
                                        .marqueeOnOverflow(),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowVodCard(
    checked: Boolean,
    subtitle: String,
    onFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsCard(position = SettingsCardPosition.SINGLE, modifier = modifier) {
        SettingsSwitchRow(
            title = stringResource(R.string.live_follow_vod_source),
            leadingIconRes = R.drawable.ic_subscribe_source,
            subtitle = subtitle,
            subtitleMaxLines = 1,
            checked = checked,
            onCheckedChange = { next -> if (next) onFollow() },
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    repoSummary: String? = null,
    onRepoClick: (() -> Unit)? = null,
    active: Boolean,
    disabled: Boolean,
    manageMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLeadingIcon(R.drawable.ic_subscribe_source, enabled = true)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .marqueeOnOverflow(),
                    )
                    if (disabled) {
                        Spacer(Modifier.width(8.dp))
                        DisabledSourceTag()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    modifier = Modifier.marqueeOnOverflow(),
                    text = item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!repoSummary.isNullOrEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    // 多仓订阅：这一行本身就是切仓入口，点一下直接展开仓库列表。
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = onRepoClick != null) { onRepoClick?.invoke() }
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .marqueeOnOverflow(),
                            text = repoSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (onRepoClick != null) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_detail_switch_source),
                                contentDescription = stringResource(R.string.config_switch_repo),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = manageMode,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.7f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configRowControl",
            ) { managing ->
                if (managing) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    SettingsSwitch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

@Composable
private fun DisabledSourceTag() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = stringResource(R.string.config_source_disabled_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddScriptDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFolder: (onPicked: (path: String, name: String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var folderLabel by remember { mutableStateOf("") }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable {
                            onPickFolder { picked, label ->
                                path = picked
                                folderLabel = label
                                if (name.isBlank()) name = label
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = stringResource(R.string.config_pick_local),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_name)) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_path)) },
                    supportingText = {
                        Text(stringResource(R.string.config_script_path_hint))
                    },
                )
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                onClick = { dismissThen { onSave(name.trim().ifBlank { folderLabel }, path.trim()) } },
                enabled = path.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onDismiss() } }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AddSubscribeDialog(
    title: String,
    urlSupportingText: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFile: (onPicked: (name: String, api: String) -> Unit) -> Unit,
    onPickFolder: ((onPicked: (path: String, name: String) -> Unit) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var folderLabel by remember { mutableStateOf("") }
    val urlHint: (@Composable () -> Unit)? = if (urlSupportingText.isEmpty()) {
        null
    } else {
        {
            Text(
                text = urlSupportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val folderHint: (@Composable () -> Unit)? = if (onPickFolder == null) {
        null
    } else {
        {
            Text(
                text = stringResource(R.string.config_subscribe_pick_folder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                if (onPickFolder != null) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                            .clickable {
                                onPickFolder { picked, label ->
                                    url = LocalScriptSource.SCHEME + picked
                                    folderLabel = label
                                    if (name.isBlank()) name = label
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_script),
                            contentDescription = stringResource(R.string.config_pick_script_folder),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable {
                            onPickFile { pickedName, pickedApi ->
                                url = pickedApi
                                if (name.isBlank()) name = pickedName
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = stringResource(R.string.config_pick_local),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_name)) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_url)) },
                    supportingText = urlHint ?: folderHint,
                )
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                onClick = { dismissThen { onSave(name.trim().ifBlank { folderLabel }, url.trim()) } },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(onClick = { dismissThen { onDismiss() } }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
