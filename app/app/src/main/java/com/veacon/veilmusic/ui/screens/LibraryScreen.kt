package com.veacon.veilmusic.ui.screens

import com.veacon.veilmusic.i18n.tr
import com.veacon.veilmusic.ui.components.menuInitialFocus
import com.veacon.veilmusic.R

import com.veacon.veilmusic.ui.components.focusedArtworkBorder
import com.veacon.veilmusic.ui.components.pageHorizontalInset
import com.veacon.veilmusic.ui.components.FadeThrough
import com.veacon.veilmusic.ui.components.TouchSurface as Surface

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import com.veacon.veilmusic.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import com.veacon.veilmusic.ui.navigation.ReturnLazyVerticalGrid as LazyVerticalGrid
import com.veacon.veilmusic.ui.navigation.items
import com.veacon.veilmusic.ui.navigation.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import com.veacon.veilmusic.ui.components.returnToTabOnUp
import com.veacon.veilmusic.ui.components.rememberTabAwareListState
import com.veacon.veilmusic.ui.components.rememberTabAwareGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.veacon.veilmusic.data.model.Album
import com.veacon.veilmusic.data.model.Artist
import com.veacon.veilmusic.data.model.Song
import com.veacon.veilmusic.data.local.LocalLibraryStore
import com.veacon.veilmusic.data.network.PlaylistDto
import com.veacon.veilmusic.ui.components.AlbumCard
import com.veacon.veilmusic.ui.components.capsuleAwareBottom
import com.veacon.veilmusic.ui.components.LocalMiniCapsuleFocus
import com.veacon.veilmusic.ui.viewmodel.LibraryViewModel
import com.veacon.veilmusic.ui.viewmodel.LibrarySection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.veacon.veilmusic.ui.viewmodel.PlayerViewModel
import com.veacon.veilmusic.ui.viewmodel.SortField

/** Sorted snapshots let a sort/section fade finish before the new order is shown. */
private data class LibraryItems(
    val playlists: List<LocalLibraryStore.LocalPlaylist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val songs: List<Song> = emptyList(),
    val recents: List<Song> = emptyList(),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playerVm: PlayerViewModel,
    onAlbumClick: (String) -> Unit = {},
    onPlaylistClick: (id: String, name: String, artworkUrl: String?) -> Unit = { _, _, _ -> },
    onArtistClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    contentFocusRequester: FocusRequester? = null,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    // Survive navigation away/back (saved in the nav backstack), so returning to Library
    // lands on the same section instead of resetting to Playlists.
    var section by rememberSaveable { mutableStateOf(LibrarySection.Songs) }
    // 「编辑」模式：打开后歌曲行 / 歌单卡片右侧出现删除按钮；切节或离开页面自动退出。
    var editing by remember { mutableStateOf(false) }
    val sectionStates=androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(section) { vm.selectSection(section); editing = false }
    // 焦点所在层级：1=侧栏, 2=列表, 3=编辑按钮。用来实现 Back 的逐级返回。
    var focusZone by remember { mutableStateOf(0) }
    // 编辑模式下按返回键退出编辑，而不是退出资料库。
    androidx.activity.compose.BackHandler(enabled = editing) { editing = false }
    DisposableEffect(vm) { onDispose { vm.onHidden() } }
    val state by vm.state.collectAsStateWithLifecycle()
    val localEntryFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    // Down from the tab bar lands on the sidebar row of the section currently shown (「歌曲」 on the
    // first visit — see libraryEntryFocus).
    val rowFocus = remember { LibrarySection.entries.associateWith { FocusRequester() } }
    val entryScope = rememberCoroutineScope()
    val sortRoute = remember { LibrarySortFocusRoute() }
    androidx.activity.compose.BackHandler(enabled = !editing && focusZone == 3) { listFocus.requestFocus() }
    androidx.activity.compose.BackHandler(enabled = !editing && focusZone == 2) { rowFocus[section]?.requestFocus() }
    androidx.activity.compose.BackHandler(enabled = !editing && focusZone == 1) { onReturnToTab?.invoke() }
    val sortFocus = sortRoute.control
    // Right from the sidebar enters the list itself (not the sort control).
    val contentRoute = remember { LibraryContentEntryRoute() }
    val currentSection by rememberUpdatedState(section)
    val returnNavigation = com.veacon.veilmusic.ui.navigation.LocalNavigationReturn.current

    var showImport by remember { mutableStateOf(false) }
    // 在 FadeThrough 外面取好：里面的 section 是解构出来的 val，不能赋值。
    val openImport: () -> Unit = { section = LibrarySection.Playlists; showImport = true }
    var importing by remember { mutableStateOf(false) }
    var playlistMenu by remember { mutableStateOf<LocalLibraryStore.LocalPlaylist?>(null) }
    // 删除歌单前先弹二次确认，避免误删。
    var confirmDelete by remember { mutableStateOf<LocalLibraryStore.LocalPlaylist?>(null) }
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    fun toast(message: String) {
        android.widget.Toast.makeText(toastContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }
    // 登录后「资料库」显示 Apple Music 云端歌单；与本地歌单共用卡片和详情页，只是来源不同。
    val appleAuth = remember(toastContext) { com.veacon.veilmusic.data.AppleDisplayAuth.get(toastContext) }
    val appleState by appleAuth.state.collectAsState()
    // 「从 Apple Music 同步歌单」的进度（空闲时 running=false）。
    val appleSync by vm.appleSync.collectAsState()
    val applePlaylists = remember(appleState.playlists) {
        appleState.playlists.map { p ->
            LocalLibraryStore.LocalPlaylist(
                id = p.id, name = p.name, coverUrl = p.artworkUrl,
                source = LocalLibraryStore.SOURCE_APPLE,
            )
        }
    }

    val pinnedIds by vm.pinnedIds.collectAsState()
    val orderedItems = remember(section, state, pinnedIds) {
        when (section) {
            LibrarySection.Playlists -> LibraryItems(playlists = vm.sortedPlaylists())
            LibrarySection.Albums -> LibraryItems(albums = vm.sortedAlbums())
            LibrarySection.Artists -> LibraryItems(artists = vm.sortedArtists())
            LibrarySection.Songs -> LibraryItems(songs = vm.sortedSongs())
            LibrarySection.Recents -> LibraryItems(recents = state.recents)
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val inset = pageHorizontalInset
    val sidebarWidth = 208.dp.coerceAtMost((maxWidth - inset * 2) * 0.32f)
    Row(Modifier.fillMaxSize().padding(horizontal = inset)) {
        // Modern left sidebar (Songs / Playlists / Albums / Artists). Focus selects the section —
        // the tab bar behaves the same way — so no OK press is needed to switch.
        SectionSidebar(section, onReturnToTab, sidebarWidth, contentRoute::request, rowFocus, { section = it },
        onZoneEnter = { focusZone = 1 }, focusEnabled = !editing)

        // Always mount this entry group so Down is safe even during first loading.
        // Down from the tab bar goes to the sidebar row; the list is reached with Right.
        Box(modifier = Modifier.weight(1f).fillMaxHeight()
            .focusRequester(contentFocusRequester ?: localEntryFocus)
            .onFocusChanged { if (it.hasFocus) focusZone = 2 }
            .libraryEntryFocus(
                onEnterRequest = { entryScope.launch { runCatching { rowFocus.getValue(currentSection).requestFocus() } } },
                restoring = returnNavigation?.restoring == true,
                sort = sortRoute,
                entry = contentRoute,
            ).focusGroup()) {
            FadeThrough(Triple(section, state, orderedItems), modifier = Modifier.fillMaxSize(),
                contentKey = { (section, state, _) -> Triple(section, state.sort, when {
                    state.error != null -> "error"
                    else -> "content"
                }) }) { (section, state, orderedItems) ->
                when {
                    state.error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(tr(R.string.msg_096, com.veacon.veilmusic.i18n.AppLanguage.message(state.error)), color = Color(0xFFFF3B30), fontSize = 14.sp)
                    }
                    else -> Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
                        // 排序固定「添加日期 · 降序」；右上角只保留云同步与编辑两个图标。
                        LibraryActionsBar(
                            onReturnToTab = onReturnToTab,
                            editFocus = sortFocus,
                            listFocus = listFocus,
                            editing = editing,
                            // 只有「歌曲」「播放列表」支持编辑删除。
                            onToggleEdit = if (section == LibrarySection.Songs || section == LibrarySection.Playlists) {
                                {
                                    val next = !editing
                                    // 防呆：播放列表里如果一张可编辑的歌单都没有（全是云端在线歌单），
                                    // 进了编辑也无处可删、焦点无处可落——提示后留在铅笔上，不进入编辑。
                                    val blocked = next && section == LibrarySection.Playlists &&
                                        (applePlaylists + orderedItems.playlists).none { pl ->
                                            !(pl.source == LocalLibraryStore.SOURCE_APPLE && !pl.id.startsWith("apple-"))
                                        }
                                    if (blocked) {
                                        toast("云端歌单无法删除")
                                    } else {
                                        editing = next
                                        // 进入编辑立刻把焦点送进列表（侧栏此时已不可聚焦）。
                                        if (next) runCatching { listFocus.requestFocus() }
                                    }
                                }
                            } else null,
                            // 播放列表右上角的云图标：强制重新拉取 Apple Music 云端歌单。
                            onRefreshApple = if (section == LibrarySection.Playlists)
                                { { appleAuth.refresh(); toast(if (appleState.signedIn) "正在刷新云端歌单…" else "请先在「设置 · Apple Music」里登录") } } else null,
                            refreshingApple = appleState.checking,
                            onBarFocus = { focusZone = 3 },
                        )
                        Box(Modifier.weight(1f).focusRequester(listFocus).focusGroup()) {
                            // 只有这一节真有右上角按钮时，才把「右边缘 → 按钮」的焦点路由打开；
                            // 专辑 / 艺人 / 最近播放没有按钮，右边缘就老老实实停在卡片上。
                            val hasActions = section == LibrarySection.Songs || section == LibrarySection.Playlists
                            CompositionLocalProvider(
                                LocalLibrarySortFocus provides (if (hasActions) sortRoute else null),
                                LocalLibraryContentEntryFocus provides contentRoute.control,
                            ) {
                            // 只有线性列表（歌曲）整块接管 Right；网格（歌单/专辑）必须保留
                            // 卡片间的左右移动，否则每按一次 Right 都会被拽到右上角按钮上（光标乱跑）。
                            Box(Modifier.fillMaxSize()) {
                            sectionStates.SaveableStateProvider(section.name) {
                            when (section) {
                                LibrarySection.Playlists -> {
                                    PlaylistGrid(
                                        tabBarFocused = tabBarFocused,
                                        onReturnToTab = onReturnToTab,
                                        playlists  = applePlaylists + orderedItems.playlists,
                                        pinnedIds  = pinnedIds,
                                        importing  = importing,
                                        editing    = editing,
                                        editButtonFocus = sortFocus,
                                        onOpen     = { playlist ->
                                            onPlaylistClick(playlist.id, playlist.name, playlist.coverUrl)
                                        },
                                        onPlay     = { vm.playPlaylist(it.id, playerVm) },
                                        onMenu     = { playlistMenu = it },
                                        onImport   = { showImport = true },
                                        onDelete   = { confirmDelete = it },
                                    )
                                }
                                LibrarySection.Albums  -> AlbumGrid(orderedItems.albums, onAlbumClick, onReturnToTab, tabBarFocused)
                                LibrarySection.Artists -> ArtistList(orderedItems.artists, onArtistClick, onReturnToTab, tabBarFocused)
                                LibrarySection.Songs -> SongList(orderedItems.songs, playerVm, vm, onArtistClick, onAlbumClick, onReturnToTab, tabBarFocused,
                                    onImportPlaylist = openImport, editing = editing,
                                    editButtonFocus = if (section == LibrarySection.Songs) sortFocus else null,
                                    onDeleteSong = { song ->
                                        vm.removeFavorite(song.id)
                                        toast("已从资料库移除「${song.title}」")
                                    })
                                LibrarySection.Recents -> RecentsList(orderedItems.recents, playerVm, vm, onArtistClick, onAlbumClick, onReturnToTab, tabBarFocused)
                            }
                            }
                            }
                            }
                        }
                    }
                }
            }

        }
    }

    // 导入歌单：只弹二维码，链接在手机上粘（电视端输入框会卡住遥控器）
    FadeThrough(showImport, contentKey = { if (it) true else null }) { visible ->
        if (visible) {
            ImportPlaylistDialog(
                serverUrl = vm.serverUrl(),
                sync = appleSync,
                onSyncApple = { vm.syncApplePlaylists { message -> toast(message) } },
                onDismiss = { showImport = false },
            )
        }
    }

    // 删除歌单前的二次确认
    FadeThrough(confirmDelete, contentKey = { it?.id }) { target ->
        target?.let { pl ->
            com.veacon.veilmusic.ui.components.ConfirmationDialog(
                title = "删除歌单？",
                message = "「${pl.name}」将从本地资料库移除，共 ${pl.trackCount} 首，删除后无法恢复。",
                confirmLabel = "删除",
                onDismiss = { confirmDelete = null },
                onConfirm = {
                    confirmDelete = null
                    vm.deletePlaylist(pl.id)
                    toast("已删除「${pl.name}」")
                },
                confirmColor = Color(0xFFFF3B30),
            )
        }
    }

    // 歌单长按菜单
    FadeThrough(playlistMenu, contentKey = { it?.id }) { retained ->
        retained?.let { pl ->
            PlaylistMenuDialog(
                playlist   = pl,
                pinned     = pl.id in pinnedIds,
                onDismiss  = { playlistMenu = null },
                onReimport = {
                    playlistMenu = null
                    val url = pl.sourceUrl
                    if (url == null) {
                        toast("文本歌单无法重新导入")
                    } else {
                        importing = true
                        vm.importPlaylist(url) { message -> importing = false; toast(message) }
                    }
                },
                onTogglePin = { playlistMenu = null; vm.togglePin(pl.id) },
            )
        }
    }
}

} // page bounds

/** Modern left sidebar — vertical section nav. Selected row = red-tinted pill with a
 *  bright accent bar; unselected are quiet. Bigger, cleaner than the old sidebar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionSidebar(selected: LibrarySection, onReturnToTab: (() -> Unit)?, sidebarWidth: androidx.compose.ui.unit.Dp, onRequestContent: () -> Boolean, rowFocus: Map<LibrarySection, FocusRequester>, onSelect: (LibrarySection) -> Unit,
    /** 侧栏获得焦点时通知页面（Back 逐级返回用）。 */
    onZoneEnter: () -> Unit = {},
    /** 编辑模式下侧栏不可聚焦：遥控器只能停在可删除的条目上。 */
    focusEnabled: Boolean = true,
) {
    val inputModeManager = androidx.compose.ui.platform.LocalInputModeManager.current
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(sidebarWidth)
            .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))
            .padding(end = 16.dp, top = 96.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            tr(R.string.msg_089),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        LibrarySection.entries.forEachIndexed { idx, s ->
            val isSel = s == selected
            val isFirst = idx == 0
            val isLast = idx == LibrarySection.entries.lastIndex
            // 层级一的下边界出口：侧栏最后一项（艺人）按 Down 直接落到迷你播放胶囊；
            // 胶囊没开（null）时也把 Down 吞掉，停在艺人上，不让焦点乱跳。
            val capsuleFocus = LocalMiniCapsuleFocus.current
            Surface(
                onClick = { onSelect(s) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSel) Color(0x33FA233B) else Color.Transparent,
                    focusedContainerColor = if (isSel) Color(0x4DFA233B) else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
                modifier = Modifier.fillMaxWidth()
                    .then(rowFocus[s]?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .focusProperties { canFocus = focusEnabled }
                    // 和顶栏一致：焦点落到哪一节就直接切过去，不用再按 OK。
                    .onFocusChanged {
                        if (it.isFocused) { onZoneEnter(); if (inputModeManager.inputMode == androidx.compose.ui.input.InputMode.Keyboard) onSelect(s) }
                    }
                    // 侧栏方向键全显式管理：
                    // Right → 进内容区（层级二）；Up 只在第一项（歌曲）回全局顶栏，
                    // 其余行让空间导航自然上移；Down 只在最后一项（艺人）落到胶囊。
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionRight -> onRequestContent()
                            Key.DirectionUp -> if (isFirst) { onReturnToTab?.invoke(); true } else false
                            Key.DirectionDown -> if (isLast) {
                                runCatching { capsuleFocus?.requestFocus() }
                                true
                            } else false
                            else -> false
                        }
                    },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp))
                            .background(if (isSel) Color(0xFFFA233B) else Color.Transparent)
                    )
                    Text(
                        text = s.label,
                        fontSize = 16.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSel) com.veacon.veilmusic.ui.theme.AppPalette.primary else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                    )
                }
            }
        }
    }
}

/**
 * 资料库右上角的操作区（层级三）：只有「刷新云端歌单」（云）和「编辑」（铅笔）两个圆形图标按钮。
 *
 * 排序已固定为「添加日期 · 降序」（见 [com.veacon.veilmusic.ui.viewmodel.LibraryViewModel]），
 * 这里不再提供排序入口。方向键层级：
 * - Left / Right 只在云 ↔ 铅笔之间互移，到红框边缘停住（不跳回侧栏、不越界）；
 * - Down 回内容列表（层级二）；
 * - Up 回全局顶栏（红框的上边界出口）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LibraryActionsBar(
    onReturnToTab: (() -> Unit)?,
    editFocus: FocusRequester,
    listFocus: FocusRequester? = null,
    editing: Boolean = false,
    /** 传 null 表示这一节不支持编辑（专辑 / 艺人 / 最近播放）。 */
    onToggleEdit: (() -> Unit)? = null,
    /** 传 null 表示不显示「刷新云端歌单」的云图标。 */
    onRefreshApple: (() -> Unit)? = null,
    refreshingApple: Boolean = false,
    /** 焦点落到红框任意按钮（云 / 铅笔）时通知页面 —— Back 逐级返回用。 */
    onBarFocus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cloudFocus = remember { FocusRequester() }
    Row(
        modifier = modifier.fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 刷新云端歌单：强制重新拉取 Apple Music 资料库里的歌单列表。
        if (onRefreshApple != null) {
            val spin = if (refreshingApple) rememberCloudSpin() else 0f
            Surface(
                onClick = { if (!refreshingApple) onRefreshApple() },
                modifier = Modifier.focusRequester(cloudFocus)
                    .onFocusChanged { if (it.isFocused) onBarFocus() }
                    .semantics { contentDescription = "刷新 Apple Music 云端歌单" }
                    .focusProperties {
                        // 红框左边缘：停住，不跳回侧栏。
                        left = FocusRequester.Cancel
                        right = editFocus
                        if (listFocus != null) down = listFocus
                    }
                    // 红框内再按 Up 才回全局顶栏。
                    .returnToTabOnUp(onReturnToTab),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = com.veacon.veilmusic.ui.components.contentActionColors(
                    com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                ),
                border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(Modifier.requiredSize(44.dp), contentAlignment = Alignment.Center) {
                    CloudRefreshIcon(
                        color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                        rotation = spin,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        // 编辑：铅笔图标（编辑中变成对勾 + 红底），点开后歌曲 / 歌单可以删除。
        if (onToggleEdit != null) {
            Surface(
                onClick = onToggleEdit,
                modifier = Modifier.focusRequester(editFocus)
                    .onFocusChanged { if (it.isFocused) onBarFocus() }
                    .semantics { contentDescription = if (editing) "完成编辑" else "编辑资料库" }
                    .focusProperties {
                        // 有云时 Left 在红框内互移；无云（歌曲区）时 Left 回列表这条便捷道保留。
                        left = if (onRefreshApple != null) cloudFocus else listFocus ?: FocusRequester.Cancel
                        if (listFocus != null) down = listFocus
                    }
                    // 焦点在「编辑」时再按 Up 才回顶栏。
                    .returnToTabOnUp(onReturnToTab),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = if (editing) Color(0xFFFA233B)
                        else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                    focusedContainerColor = if (editing) Color(0xFFFF4757)
                        else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFDCDCE1), Color(0xFF4A4A50)),
                ),
                border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(Modifier.requiredSize(44.dp), contentAlignment = Alignment.Center) {
                    com.veacon.veilmusic.ui.components.Icon(
                        if (editing) com.veacon.veilmusic.ui.components.Glyph.CHECK
                        else com.veacon.veilmusic.ui.components.Glyph.EDIT,
                        size = 22.dp,
                        color = if (editing) Color.White else com.veacon.veilmusic.ui.theme.AppPalette.primary,
                    )
                }
            }
        }
    }
}

/**
 * 资料库右上角的「云」图标：点一下强制重新拉取 Apple Music 云端歌单。
 * [rotation] 在同步中由 [rememberCloudSpin] 驱动旋转，用作进度反馈。
 */
@Composable
private fun CloudRefreshIcon(color: Color, rotation: Float, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size).rotate(rotation)) {
        val u = this.size.minDimension / 24f
        drawCircle(color, radius = 4.4f * u, center = Offset(8.2f * u, 12.4f * u))
        drawCircle(color, radius = 6.1f * u, center = Offset(12.4f * u, 10.1f * u))
        drawCircle(color, radius = 4.2f * u, center = Offset(17.2f * u, 12.5f * u))
        drawRoundRect(
            color = color,
            topLeft = Offset(6.4f * u, 11.2f * u),
            size = Size(12.8f * u, 5.4f * u),
            cornerRadius = CornerRadius(2.7f * u, 2.7f * u),
        )
    }
}

@Composable
private fun rememberCloudSpin(): Float {
    val transition = rememberInfiniteTransition(label = "cloudSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "cloudAngle",
    )
    return angle
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistGrid(
    playlists: List<LocalLibraryStore.LocalPlaylist>,
    pinnedIds: Set<String>,
    importing: Boolean,
    onOpen: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onPlay: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onMenu: (LocalLibraryStore.LocalPlaylist) -> Unit,
    onImport: () -> Unit,
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    /** 编辑模式：本地歌单卡片右上角出现删除按钮。 */
    editing: Boolean = false,
    onDelete: (LocalLibraryStore.LocalPlaylist) -> Unit = {},
    /** 层级三「编辑」按钮的焦点：第一排按 Up 落到它。 */
    editButtonFocus: FocusRequester? = null,
) {
    val gridState = rememberTabAwareGridState(tabBarFocused)
    val entryFocus = LocalLibraryContentEntryFocus.current
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, top = 20.dp, bottom = capsuleAwareBottom(48.dp)),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
        versionFooter = true,
    ) {
        gridItemsIndexed(playlists, key = { _, item -> item.id }) { index, playlist ->
            // 层级二方向键边界：
            // Up —— 第一排交给红框铅笔（红框缺席时才回顶栏），其余排逐行上移；
            // Down —— 逐行下移，走到底由列表末尾的版本行接住（版本行再 Down 落胶囊）；
            // Right —— 行内自然右移到最右控件停住（右缘不再被拽到红框）。
            val isFirstRow = index < 4
            val isApple = playlist.source == LocalLibraryStore.SOURCE_APPLE
            // 只在线的云端歌单（来自 Apple 账号、不在本地库里）不能删；
            // 从 Apple 同步下来落到本地库的歌单（id 以 apple- 开头）可以删。
            val deletable = editing && !(isApple && !playlist.id.startsWith("apple-"))
            // 防呆：编辑模式下纯云端在线歌单——置灰降低透明度，且整卡不可聚焦，遥控器直接跳过。
            val cloudLocked = editing && isApple && !playlist.id.startsWith("apple-")
            Box(
                Modifier
                    .then(
                        when {
                            isFirstRow && editButtonFocus != null -> Modifier.focusProperties { up = editButtonFocus }
                            isFirstRow -> Modifier.returnToTabOnUp(onReturnToTab)
                            else -> Modifier
                        }
                    ),
            ) {
                PlaylistCard(
                    playlist    = playlist,
                    pinned      = playlist.id in pinnedIds,
                    editing     = deletable,
                    dimmed      = cloudLocked,
                    onDelete    = { onDelete(playlist) },
                    onOpen      = { onOpen(playlist) },
                    // 云端歌单没有本地曲目，播放/长按都改为打开详情，避免误调本地播放与导入菜单。
                    onPlay      = { if (isApple) onOpen(playlist) else onPlay(playlist) },
                    onMenu      = { if (isApple) onOpen(playlist) else onMenu(playlist) },
                    modifier    = if (index == 0 && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier,
                )
            }
        }
        // 「导入歌单」放在列表末尾：歌单多了以后不该让这个动作用户每次都要跳过。
        item(key = "import-card") {
            Box {
                ImportCard(importing = importing, onClick = onImport,
                    modifier = if (playlists.isEmpty() && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
            }
        }
    }
}

/** 「＋ 导入歌单」卡片：与歌单同尺寸，点击弹出导入对话框。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportCard(importing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF1C1C1F)),
            focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF2C2C31)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF232327))),
                contentAlignment = Alignment.Center,
            ) {
                if (importing) {
                    CircularProgressIndicator(color = Color(0xFFFA233B), modifier = Modifier.size(28.dp))
                } else {
                    Text("＋", fontSize = 40.sp, color = Color(0xFFFA233B))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("导入歌单", fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.Medium)
                Text("QQ 音乐 / 网易云 / 酷狗", fontSize = 10.sp, maxLines = 1,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistCard(
    playlist: LocalLibraryStore.LocalPlaylist,
    pinned: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onDelete: () -> Unit = {},
    /** 防呆置灰：整卡降透明度、内部播放钮也不可聚焦（云端在线歌单在编辑模式下）。 */
    dimmed: Boolean = false,
) {
    Surface(
        // 编辑模式下整张卡片就是「删除」，OK 直接删掉，不会进详情。
        // 防呆置灰（云端在线歌单）：整卡不可聚焦——遥控器光标直接跳过，OK 也无从按起；
        // OK 兜底为空操作，触摸误点也不会进详情。
        onClick      = when { dimmed -> ({}); editing -> onDelete; else -> onOpen },
        onLongClick  = if (editing) null else onMenu,
        modifier     = modifier
            .then(if (dimmed) Modifier.focusProperties { canFocus = false } else Modifier)
            .then(if (dimmed) Modifier.alpha(0.45f) else Modifier),
        shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF414146))),
        scale  = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    .focusedArtworkBorder(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                    .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
            ) {
                if (playlist.coverUrl != null) {
                    AsyncImage(
                        model = playlist.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.MUSIC_NOTE, size = 32.dp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                    }
                }
                if (pinned) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    ) {
                        Text("📌", fontSize = 10.sp)
                    }
                }
                // 编辑模式：删除本地歌单（云端歌单不显示，避免删了又同步回来）。
                if (editing) {
                    Surface(
                        onClick = onDelete,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xCC000000),
                            focusedContainerColor = Color(0xFFFA233B),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
                        // 触摸也能点，但遥控器在编辑模式下只停在卡片上（OK 即删除）。
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp)
                            .focusProperties { canFocus = false },
                    ) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            com.veacon.veilmusic.ui.components.Icon(
                                com.veacon.veilmusic.ui.components.Glyph.CLOSE,
                                size = 13.dp, color = Color.White,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, fontSize = 12.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text(if (playlist.source == LocalLibraryStore.SOURCE_APPLE) "${playlist.sourceLabel} · 云端"
                         else "${playlist.sourceLabel} · ${playlist.trackCount} 首", fontSize = 10.sp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
                Surface(
                    onClick = onPlay,
                    shape  = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
                    border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    modifier = Modifier.size(28.dp)
                        // 置灰的云端卡：行内的播放钮同样不可聚焦，整张卡片对遥控器隐身。
                        .then(if (dimmed) Modifier.focusProperties { canFocus = false } else Modifier),
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        com.veacon.veilmusic.ui.components.Icon(com.veacon.veilmusic.ui.components.Glyph.PLAY, size = 11.dp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>, onAlbumClick: (String) -> Unit, onReturnToTab: (() -> Unit)? = null, tabBarFocused: Boolean = false) {
    val gridState = rememberTabAwareGridState(tabBarFocused)
    val entryFocus = LocalLibraryContentEntryFocus.current
    if (albums.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tr(R.string.msg_125), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA))) }; return }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 32.dp, top = 20.dp, bottom = capsuleAwareBottom(48.dp)),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
        versionFooter = true,
    ) {
        gridItemsIndexed(albums, key = { _, item -> item.id }) { index, album ->
            // 专辑区没有红框（无编辑 / 云按钮）：第一排按 Up 仍回顶栏。
            AlbumCard(album = album, size = 150, onClick = { onAlbumClick(album.id) },
                modifier = Modifier
                    .returnToTabOnUp(if (index < 4) onReturnToTab else null)
                    .then(if (index == 0 && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ArtistList(artists: List<Artist>, onArtistClick: (String) -> Unit, onReturnToTab: (() -> Unit)? = null, tabBarFocused: Boolean = false) {
    val listState = rememberTabAwareListState(tabBarFocused)
    val entryFocus = LocalLibraryContentEntryFocus.current
    if (artists.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(tr(R.string.msg_126), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA))) }; return }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, top = 24.dp, bottom = capsuleAwareBottom(24.dp)), verticalArrangement = Arrangement.spacedBy(4.dp), versionFooter = true) {
        itemsIndexed(artists, key = { _, item -> item.id }) { index, artist ->
            Surface(onClick = { onArtistClick(artist.id) }, modifier = Modifier.fillMaxWidth()
                .then(if (index == 0 && entryFocus != null) Modifier.focusRequester(entryFocus) else Modifier)
                .returnToTabOnUp(if (index == 0) onReturnToTab else null),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                        if (artist.artworkUrl != null) AsyncImage(model = artist.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(artist.name, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecentsList(
    songs: List<Song>,
    playerVm: PlayerViewModel,
    vm: LibraryViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
) {
    SongList(
        songs = songs,
        playerVm = playerVm,
        vm = vm,
        onArtistClick = onArtistClick,
        onAlbumClick = onAlbumClick,
        onReturnToTab = onReturnToTab,
        tabBarFocused = tabBarFocused,
        emptyTitle = tr(R.string.msg_recent),
        emptyHint = tr(R.string.msg_recent_empty),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongList(
    songs: List<Song>,
    playerVm: PlayerViewModel,
    vm: LibraryViewModel,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    onImportPlaylist: (() -> Unit)? = null,
    emptyTitle: String = "还没有收藏的歌曲",
    emptyHint: String = "任意歌曲上长按 →「收藏到资料库」；歌单可在「播放列表」里导入 QQ 音乐 / 网易云 / 酷狗。",
    /** 第一行按 Up 要去的「编辑」按钮焦点。 */
    editButtonFocus: FocusRequester? = null,
    /** 编辑模式：每行右侧出现删除按钮。 */
    editing: Boolean = false,
    onDeleteSong: (Song) -> Unit = {},
) {
    val listState = rememberTabAwareListState(tabBarFocused)
    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(emptyTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                Text(emptyHint,
                    fontSize = 13.sp,
                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                // 空页如果只有文字，遥控器在资料库页会卡在右上角排序按钮上出不来。
                if (onImportPlaylist != null) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        onClick = onImportPlaylist,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color(0xFFFA233B),
                            focusedContainerColor = Color(0xFFFF4757),
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
                    ) {
                        Text(tr(R.string.msg_327), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 11.dp))
                    }
                }
            }
        }
        return
    }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    val entryFocus = LocalLibraryContentEntryFocus.current
    // 编辑模式删除后的焦点接力：删除当前行 → 焦点落下一首；删的是末行 → 落上一首；
    // 列表清空 → 回到顶部「铅笔」。用「删除后的期望行数」对齐时序 —— 数据还没更新时
    // 不抢跑，避免焦点落在即将消失的行上。
    var pendingFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (目标 index, 删除后的期望行数)
    val itemFocusers = remember { mutableMapOf<Int, FocusRequester>() }
    LaunchedEffect(pendingFocus, songs.size) {
        val (target, expected) = pendingFocus ?: return@LaunchedEffect
        if (songs.size != expected) return@LaunchedEffect
        if (songs.isEmpty()) {
            runCatching { editButtonFocus?.requestFocus() }
        } else {
            val idx = target.coerceIn(0, songs.lastIndex)
            runCatching { listState.scrollToItem(idx) }
            withFrameNanos { }
            runCatching { (if (idx == 0) entryFocus else itemFocusers[idx])?.requestFocus() }
        }
        pendingFocus = null
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 32.dp, top = 24.dp, bottom = capsuleAwareBottom(24.dp)), verticalArrangement = Arrangement.spacedBy(4.dp), versionFooter = true) {
        val returnKeys=com.veacon.veilmusic.ui.navigation.occurrenceKeys(songs.map { it.id })
    items(songs.size, key = { returnKeys[it] }) { idx ->
            val song = songs[idx]
            Surface(
                // 编辑模式：OK 直接删除当前项（不进入详情、不播放）；普通模式才播放。
                onClick = {
                    if (editing) {
                        // 下一首会顶到当前位置；删的是末行则落上一首（-1 表示清空）。
                        pendingFocus = if (idx < songs.lastIndex) idx to songs.size - 1
                                       else idx - 1 to songs.size - 1
                        onDeleteSong(song)
                    } else playerVm.playAlbum(songs, idx)
                },
                onLongClick = { if (!editing) menuSong = song },
                onContextMenu = { if (!editing) menuSong = song },
                modifier = Modifier.fillMaxWidth()
                    .then(
                        if (idx == 0 && entryFocus != null) Modifier.focusRequester(entryFocus)
                        else Modifier.focusRequester(itemFocusers.getOrPut(idx) { FocusRequester() })
                    )
                    // 第一行按 Up：进「编辑」按钮（不再直接跳顶栏）；其余行正常上移。
                    .then(if (idx == 0 && editButtonFocus != null) Modifier.focusProperties { up = editButtonFocus } else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))), scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(5.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                        if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(80), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f)) {
                        Text(song.title, fontSize = 13.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text(song.artistName, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                    }
                    Text(song.durationFormatted, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                    // 编辑模式：右侧给一个「×」提示当前是删除态（不再单独聚焦，整个行就是删除键）。
                    if (editing) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            onClick = { onDeleteSong(song) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                                focusedContainerColor = Color(0xFFFA233B),
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                            modifier = Modifier.size(30.dp).focusProperties { canFocus = false },
                        ) {
                            Box(Modifier.fillMaxSize(), Alignment.Center) {
                                com.veacon.veilmusic.ui.components.Icon(
                                    com.veacon.veilmusic.ui.components.Glyph.CLOSE,
                                    size = 12.dp,
                                    color = com.veacon.veilmusic.ui.theme.AppPalette.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    var pickerSong by remember { mutableStateOf<Song?>(null) }
    FadeThrough(menuSong, contentKey = { it?.id }) { retained ->
            retained?.let { s ->
            SongContextMenu(
                song = s,
                onDismiss = { menuSong = null },
                onPlayNext = { playerVm.playNext(s); menuSong = null },
                onAddToQueue = { playerVm.addToQueue(s); menuSong = null },
                onAddToLibrary = { playerVm.addToLibrary(s); menuSong = null },
                addToLibraryLabel = if (vm.isFavorite(s.id)) "取消收藏" else "收藏到资料库",
                onAddToPlaylist = { pickerSong = s; menuSong = null },
                onGoToArtist = s.artistId?.let { id -> { onArtistClick(id); menuSong = null } },
                onGoToAlbum  = s.albumId?.let  { id -> { onAlbumClick(id);  menuSong = null } },
                removeFromPlaylist = { vm.removeFavorite(s.id); menuSong = null },
            )
        }
    }
    FadeThrough(pickerSong, contentKey = { it?.id }) { retained ->
            retained?.let { s ->
            PlaylistPickerDialog(playerVm = playerVm, song = s, onDismiss = { pickerSong = null })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SongContextMenu(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToLibrary: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum:  (() -> Unit)? = null,
    addToLibraryLabel: String? = null,
    removeFromPlaylist: (() -> Unit)? = null,
) {
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        var awaitingOpeningRelease by remember { mutableStateOf(true) }
        Column(
            Modifier.menuInitialFocus(firstFocus).onPreviewKeyEvent { event ->
                if (awaitingOpeningRelease && event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                        awaitingOpeningRelease = false
                        false
                    } else {
                        if (event.type == KeyEventType.KeyUp) awaitingOpeningRelease = false
                        true
                    }
                } else false
            }.width(320.dp).clip(RoundedCornerShape(16.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Header with artwork + song info
            Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)))) {
                    if (song.artworkUrl != null) AsyncImage(model = song.artworkUrl(88), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column {
                    Text(song.title, fontSize = 13.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(song.artistName, fontSize = 11.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), maxLines = 1)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(2.dp))
            ContextMenuItem(tr(R.string.msg_128), onPlayNext, Modifier.focusRequester(firstFocus))
            ContextMenuItem(tr(R.string.msg_129), onAddToQueue)
            if (onAddToLibrary  != null) ContextMenuItem(addToLibraryLabel ?: tr(R.string.msg_130),  onAddToLibrary)
            if (onAddToPlaylist != null) ContextMenuItem(tr(R.string.msg_131), onAddToPlaylist)
            if (onGoToArtist != null) ContextMenuItem(tr(R.string.msg_132), onGoToArtist)
            if (onGoToAlbum  != null) ContextMenuItem(tr(R.string.msg_133),  onGoToAlbum)
            if (removeFromPlaylist != null) ContextMenuItem("从歌单移除", removeFromPlaylist)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContextMenuItem(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.padding(horizontal = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Text(label, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun NoMutPlaceholder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr(R.string.msg_134), color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(tr(R.string.msg_135), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg     by animateColorAsState(if (isSelected) com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)) else Color.Transparent, tween(225))
    val accent by animateColorAsState(if (isSelected) Color(0xFFFA233B) else Color.Transparent, tween(225))
    val text   by animateColorAsState(if (isSelected) com.veacon.veilmusic.ui.theme.AppPalette.primary else com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), tween(225))
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = bg, focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFEDEDF0), Color(0xFF414146)))) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.width(2.dp).height(20.dp).background(accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
            Text(label, fontSize = 13.sp, color = text, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** "添加到播放列表" picker — lists the user's editable library playlists; tap one to append the song. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistPickerDialog(
    playerVm: com.veacon.veilmusic.ui.viewmodel.PlayerViewModel,
    song: Song,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<LocalLibraryStore.LocalPlaylist>?>(null) }
    LaunchedEffect(Unit) { playlists = playerVm.editablePlaylists() }
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        Column(
            Modifier.menuInitialFocus(firstFocus, targetAvailable = !playlists.isNullOrEmpty()).width(360.dp).heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp)).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tr(R.string.msg_131), fontSize = 15.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(4.dp))
            val pls = playlists
            when {
                pls == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color(0xFFFA233B))
                }
                pls.isEmpty() -> Text(tr(R.string.msg_136), color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(pls.size) { i ->
                        val pl = pls[i]
                        ContextMenuItem(pl.name,
                            { playerVm.addToPlaylist(pl.id, pl.name, song); onDismiss() },
                            if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SongLoadError(message: String, isLoading: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, fontSize = 14.sp, color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), modifier = Modifier.weight(1f))
        Surface(onClick = { if (!isLoading) onRetry() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = com.veacon.veilmusic.ui.components.contentActionColors(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225))),
            border = com.veacon.veilmusic.ui.components.contentActionBorder(RoundedCornerShape(50))) {
            Text(if (isLoading) tr(R.string.msg_137) else tr(R.string.msg_138), color = Color(0xFFFA233B), fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/**
 * 导入歌单弹窗：只做「扫码去手机导入」。
 *
 * 曾经这里有个输入框，但电视端单行输入框会吃掉方向键与返回键 —— 遥控器一旦进去就
 * 既到不了按钮也关不掉弹窗。歌单链接本来就该在手机上粘，所以直接去掉输入框，
 * 只留二维码 + 三步说明 + 一个默认聚焦的关闭按钮。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ImportPlaylistDialog(
    serverUrl: String,
    sync: com.veacon.veilmusic.ui.viewmodel.LibraryViewModel.AppleSync,
    onSyncApple: () -> Unit,
    onDismiss: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }

    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(720.dp).clip(RoundedCornerShape(20.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF1C1C1E)))
                .padding(horizontal = 34.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("用手机导入歌单", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            Text(
                "遥控器输入长长一条歌单链接太费劲，扫码到手机上粘贴最快。",
                fontSize = 13.sp, lineHeight = 19.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // 左：二维码（白底黑块，深色主题下也清晰可扫）
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                    ) {
                        if (serverUrl.isNotBlank()) {
                            com.veacon.veilmusic.ui.components.QrCode(
                                content = serverUrl, size = 208.dp, cornerRadius = 12.dp, padding = 8.dp,
                            )
                        } else {
                            Box(Modifier.size(208.dp), Alignment.Center) {
                                Text("配置网页未启动", fontSize = 12.sp,
                                    color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                            }
                        }
                    }
                    Text(serverUrl.ifBlank { "—" }, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                }

                // 右：三步说明
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ImportStep(1, "手机连同一个 Wi-Fi", "电视和手机要在同一个网络下，扫码才打得开。")
                    ImportStep(2, "扫描左侧二维码", "相机或微信扫一扫都可以。")
                    ImportStep(3, "粘贴歌单链接", "支持 QQ 音乐 / 网易云 / 酷狗，也能直接粘「歌名 - 歌手」多行文本。")
                    Text(
                        "导入完成后会自动出现在「资料库 · 播放列表」。",
                        fontSize = 12.sp, lineHeight = 18.sp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(
                com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("从 Apple Music 同步歌单", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
                    Text(
                        if (sync.running) "正在同步… ${sync.done}/${sync.total}"
                        else "把 Apple 账号里的歌单拉进本地资料库，之后就能直接播放。",
                        fontSize = 12.sp, lineHeight = 17.sp,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)),
                    )
                }
                Surface(
                    onClick = { if (!sync.running) onSyncApple() },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                        focusedContainerColor = com.veacon.veilmusic.ui.theme.AppPalette.accent,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                ) {
                    Text(if (sync.running) "同步中…" else "开始同步", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(120.dp).padding(vertical = 12.dp))
                }
            }

            Surface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().focusRequester(closeFocus),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = com.veacon.veilmusic.ui.theme.AppPalette.accent,
                    focusedContainerColor = Color(0xFFFF4757),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp))
            }
        }
    }
}

/** 教程步骤：圆形序号 + 标题 + 说明。 */
@Composable
private fun ImportStep(index: Int, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(50))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary)
            Text(detail, fontSize = 12.sp, lineHeight = 17.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
    }
}

/** 歌单长按菜单：置顶 / 重新导入。
 *  删除**不放在这里** —— 长按很容易误触，删除改到右上角「编辑」模式里做。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlaylistMenuDialog(
    playlist: LocalLibraryStore.LocalPlaylist,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onReimport: () -> Unit,
    onTogglePin: () -> Unit,
) {
    com.veacon.veilmusic.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        val firstFocus = remember { FocusRequester() }
        Column(
            Modifier.menuInitialFocus(firstFocus).width(320.dp).clip(RoundedCornerShape(16.dp))
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(playlist.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = com.veacon.veilmusic.ui.theme.AppPalette.primary, maxLines = 1,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            Text("${playlist.sourceLabel} · ${playlist.trackCount} 首", fontSize = 11.sp,
                color = com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)),
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp)
                .background(com.veacon.veilmusic.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))))
            Spacer(Modifier.height(2.dp))
            ContextMenuItem(if (pinned) "取消置顶" else "置顶", onTogglePin, Modifier.focusRequester(firstFocus))
            if (playlist.sourceUrl != null) ContextMenuItem("重新导入", onReimport)
        }
    }
}












