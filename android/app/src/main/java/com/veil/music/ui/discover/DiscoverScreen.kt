package com.veil.music.ui.discover

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.veil.music.data.model.Album
import com.veil.music.ui.components.ShelfSkeleton
import com.veil.music.ui.navigation.LocalNavBarFocus
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 发现页（首页）—— 按 Apple Music 网页版「新发现」（music.apple.com/cn/new）的版面重做。
 *
 * 网页版的骨架其实很简单，但比例和留白很讲究：
 *  1. 左上角一个大标题（H1），下面一行灰色说明；
 *  2. 一张「流卡（flowcase）」大图：小字栏目名 → 大标题 → 一句说明，图从边到边铺满；
 *  3. 之后是一列货架：标题 + 一个细 chevron，下面一排方形封面（圆角很小、无卡片底），
 *     封面下是歌单名与作者，两行都小、都紧；
 *  4. 货架与货架之间留出明显大于行内的空白，让「一张封面」和「另一张封面」不粘在一起。
 *
 * 电视上做了三处换算：卡片一行 6 张（网页约 6 张）、左右留白 64dp、字号整体上浮一档
 * —— 3 米视距下 12px 的说明文字是看不见的。其余（对齐、圆角、层级、灰阶）尽量照抄。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenCollection: (Album) -> Unit,
    modifier: Modifier = Modifier,
    vm: DiscoverViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    // Hero + 推荐歌单的前几张，去重后作为「流卡」轮播的候选。
    val featured = remember(state.hero, state.sections) {
        buildList {
            state.hero?.let { add(it) }
            state.sections.firstOrNull()?.albums?.forEach { add(it) }
        }.distinctBy { it.id }.take(6)
    }
    var focusedIndex by remember(featured) { mutableIntStateOf(0) }
    val featuredIds = remember(featured) { featured.mapTo(HashSet()) { it.id } }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var hadFocus by remember { mutableStateOf(false) }

    when {
        state.isLoading && state.hero == null && state.sections.isEmpty() -> {
            Box(modifier.fillMaxSize()) { ShelfSkeleton() }
        }

        state.error != null && state.sections.isEmpty() && state.hero == null -> {
            Box(modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("没有拉到内容", color = Color(0xFFFF453A), fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(state.error ?: "", color = Color(0xFF8E8E93), fontSize = 13.sp)
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        onClick = vm::refresh,
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFFFA233B)),
                    ) {
                        Text("重试", color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
                    }
                }
            }
        }

        else -> {
            BoxWithConstraints(modifier.fillMaxSize()) {
                // ── 沉浸底色：跟随当前焦点的那张封面，切换时 600ms 渐变 ───────────
                // 网页版是纯白底，电视上全白太刺眼；改用「封面色压到 10%」的底色，
                // 既保留 Apple 那种「整页被一张图染色」的感觉，又不会抢歌词和文字。
                val backdrop = featured.getOrNull(focusedIndex)?.artworkUrl
                    ?: state.hero?.artworkUrl
                    ?: featured.firstOrNull()?.artworkUrl
                Crossfade(targetState = backdrop, animationSpec = tween(600), label = "backdrop") { url ->
                    if (url != null) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().alpha(0.20f),
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0.0f to Color(0xE6000000),
                                        0.30f to Color(0xCC000000),
                                        0.62f to Color(0xF2000000),
                                        1.0f to Color(0xFF000000),
                                    )
                                )
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().onFocusChanged { st ->
                        // 焦点离开列表 = 回顶栏：把列表滚回 0，保证「新发现」标题与流卡完整可见。
                        if (st.hasFocus) hadFocus = true
                        else if (hadFocus) scope.launch { listState.animateScrollToItem(0) }
                    },
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item("title") {
                        PageHeader(
                            sourceName = state.source.displayName,
                            subtitle = if (state.isLoading) "正在从 ${state.source.displayName} 拉取…" else "为你的耳朵精选的新歌、新专辑与歌单",
                        )
                    }

                    if (featured.isNotEmpty()) {
                        item("featured") {
                            FeatureFlow(
                                albums = featured,
                                onFocus = { focusedIndex = it },
                                onOpen = onOpenCollection,
                            )
                        }
                    }

                    items(state.sections, key = { it.title }) { section ->
                        val albums = if (section === state.sections.firstOrNull()) {
                            section.albums.filterNot { it.id in featuredIds }
                        } else {
                            section.albums
                        }
                        if (albums.isNotEmpty()) {
                            AppleShelf(
                                title = section.title,
                                albums = albums,
                                chartStyle = section.title.contains("排行"),
                                onOpen = onOpenCollection,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 顶部大标题 ──────────────────────────────────────────────────────────────

@Composable
private fun PageHeader(sourceName: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = H_PADDING, end = H_PADDING, top = 12.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "新发现",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.9).sp,
            )
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
        SourcePill(sourceName)
    }
}

/** 右上角的当前数据源标识，让「切了源」这件事随时可见。 */
@Composable
private fun SourcePill(name: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFF30D158)))
            Spacer(Modifier.width(8.dp))
            Text(name, color = Color(0xFFE5E5EA), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ── 流卡（flowcase）────────────────────────────────────────────────────────

/**
 * Apple 的 flowcase：一张**通栏**的大图，小字栏目名压在上面，标题大、说明小。
 *
 * 保留「一张卡 + 左右键翻页」的交互（不做成一排小卡）：电视上 6 张并排时每张只剩
 * 280dp，封面上的字根本看不清；一整张卡才是「这一屏在推荐什么」的正确表达。
 * 翻页圆点按网页版的样式放在图下方居中。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FeatureFlow(
    albums: List<Album>,
    onFocus: (Int) -> Unit,
    onOpen: (Album) -> Unit,
) {
    var index by remember(albums) { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val current = albums.getOrNull(index) ?: return
    val navFocus = LocalNavBarFocus.current
    val borderWidth by animateDpAsState(if (focused) 2.dp else 0.dp, tween(160), label = "heroBorder")

    LaunchedEffect(index, albums) { onFocus(index) }

    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = H_PADDING)) {
            // 网页版流卡接近 2.7:1；电视上限制最大高度，保证首屏还能露出第一排货架。
            val heroHeight: Dp = (maxWidth / 2.9f).coerceIn(280.dp, 500.dp)
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clip(shape)
                    .background(Color(0xFF141418))
                    .border(borderWidth, Color.White.copy(alpha = 0.85f), shape)
                    .onFocusChanged { focused = it.isFocused }
                    .focusProperties { up = navFocus ?: FocusRequester.Default }
                    .focusable()
                    .onKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (ev.key) {
                            Key.DirectionLeft -> if (index > 0) { index--; true } else false
                            Key.DirectionRight -> if (index < albums.lastIndex) { index++; true } else false
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onOpen(current); true }
                            else -> false
                        }
                    },
            ) {
                Crossfade(targetState = index, animationSpec = tween(420), label = "heroPage") { page ->
                    val album = albums.getOrNull(page) ?: return@Crossfade
                    Box(Modifier.fillMaxSize()) {
                        if (album.artworkUrl != null) {
                            AsyncImage(
                                model = album.artworkUrl(1200),
                                contentDescription = album.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        // 网页版的压暗是「下重上轻」：标题在左下，图片上半部还能看清。
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.0f to Color(0x33000000),
                                    0.45f to Color(0x66000000),
                                    0.78f to Color(0xD9000000),
                                    1.0f to Color(0xF2000000),
                                )
                            )
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f)
                                .padding(horizontal = 36.dp, vertical = 28.dp),
                        ) {
                            Text(
                                "精品推荐",
                                color = Color(0xFFFA233B),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                album.title,
                                color = Color.White,
                                fontSize = 34.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.6).sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                listOfNotNull(
                                    album.artistName.takeIf { it.isNotBlank() },
                                    album.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                                ).joinToString(" · "),
                                color = Color(0xFFD1D1D6),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White)
                                    .padding(horizontal = 18.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("▶", color = Color.Black, fontSize = 10.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("播放", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // 翻页圆点（网页版流卡下方那一排）
        if (albums.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                albums.indices.forEach { i ->
                    val on = i == index
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (on) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.28f))
                    )
                }
            }
        }
    }
}

// ── 货架 ────────────────────────────────────────────────────────────────────

/**
 * Apple 的货架：标题 22–24sp 粗体 + 一个细 chevron，下面是方形封面。
 *
 * 封面本身**没有卡片底、没有描边**，焦点时才出现一圈白边 + 轻微放大；
 * 文字两行紧贴封面（网页版的 line-height 很紧），这是它和普通 TV 应用观感上最大的差别。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppleShelf(
    title: String,
    albums: List<Album>,
    chartStyle: Boolean,
    onOpen: (Album) -> Unit,
) {
    Column(Modifier.padding(top = 40.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = H_PADDING),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.width(6.dp))
            Text("›", color = Color(0xFF6E6E73), fontSize = 24.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 一行 6 张（网页版同密度），卡片宽度按可视宽度算，任何分辨率都不会裁半张。
            val cardWidth: Dp = ((maxWidth - H_PADDING * 2 - CARD_GAP * 5) / 6).coerceIn(140.dp, 260.dp)
            LazyRow(
                contentPadding = PaddingValues(horizontal = H_PADDING),
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            ) {
                items(albums.size) { i ->
                    val album = albums[i]
                    AppleCard(
                        album = album,
                        size = cardWidth,
                        rank = if (chartStyle) i + 1 else null,
                        onClick = { onOpen(album) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppleCard(
    album: Album,
    size: Dp,
    rank: Int?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.width(size).onFocusChanged { focused = it.isFocused || it.hasFocus },
        scale = CardDefaults.scale(focusedScale = 1.06f, pressedScale = 0.97f),
        // Apple 的焦点语言：一圈细白边 + 极轻的光，不发光团、不变色块。
        glow = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.10f), 8.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.75f)),
                shape = shape,
            )
        ),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        shape = CardDefaults.shape(shape),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape), contentAlignment = Alignment.Center) {
                if (album.artworkUrl != null) {
                    AsyncImage(
                        model = album.artworkUrl((size.value * 2).toInt()),
                        contentDescription = album.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(album.color)))
                }
                // 排行榜：右下角一个大号排名，网页版榜单货架就是这么标的。
                if (rank != null) {
                    Text(
                        "$rank",
                        color = Color.White,
                        fontSize = (size.value * 0.26f).sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                        // 封面可能是浅色，白字需要一圈暗影才读得出来。
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.65f),
                                blurRadius = 14f,
                            )
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 8.dp, bottom = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(top = 9.dp, start = 1.dp, end = 1.dp)) {
                Text(
                    album.title,
                    color = Color(0xFFF5F5F7),
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.1).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    album.artistName,
                    color = Color(0xFF8A8A8E),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 与 LazyColumn 的左右留白保持一致（网页版约 4% 视宽）。 */
private val H_PADDING = 64.dp

/** 卡片间距：网页版约 28px，电视上取 22dp。 */
private val CARD_GAP = 22.dp
