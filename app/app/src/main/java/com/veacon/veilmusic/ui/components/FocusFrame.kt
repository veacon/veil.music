package com.veacon.veilmusic.ui.components

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TV 焦点外圈系统（移植自 am_atv 的 FocusFrame）。
 *
 * 焦点框是**独立 overlay 层**：所有卡片/控件把自己的焦点位置报告给
 * [FocusFrameController]，[FocusFrameOverlay] 在根布局最上层用一条连续
 * spring 绘制渐变色外圈 + 内高光。这样：
 *  - 焦点圈移动有跟手动画（不会因为控件自己的放大动画而错位）；
 *  - 滚动中的位置由 [FocusScrollPlacement] 预修正，卡片还在滚时焦点圈
 *    就贴住它的"最终落点"，行首/行尾来回移动不再错位；
 *  - 外圈画在控件之外，不会被 LazyRow 视口裁剪掉半个。
 */

internal data class FocusFrameTarget(
    val key: Any,
    val bounds: Rect,
    val radiusPx: Float,
    val visibility: State<Float>? = null,
    val transform: FocusFrameTransform? = null,
    val scrollPlacement: FocusScrollPlacement? = null,
    /** NaN = 老调用未设置，overlay 回退 4.dp；0 = 严格贴合；>0 = 指定外扩。 */
    val insetPx: Float = Float.NaN,
) {
    fun destinationBounds(): Rect = scrollPlacement?.destination(bounds, transform?.focusedScale ?: 1f) ?: bounds
}

internal class FocusFrameTransform(
    val scale: State<Float>,
    val liftProgress: State<Float>,
    val liftPx: Float,
    val focusedScale: Float,
    val followImmediately: Boolean = false,
)

internal fun visualBounds(bounds: Rect, scale: Float, liftPx: Float = 0f): Rect {
    val extraX = bounds.width * (scale - 1f) / 2f
    val extraY = bounds.height * (scale - 1f) / 2f
    return Rect(
        bounds.left - extraX,
        bounds.top - extraY - liftPx,
        bounds.right + extraX,
        bounds.bottom + extraY - liftPx,
    )
}

/** Lazy viewport clipping must not turn a full card into a partial focus target. */
internal fun LayoutCoordinates.focusFrameBounds(): Rect = Rect(
    positionInRoot(),
    Size(size.width.toFloat(), size.height.toFloat()),
)

internal class FocusFrameController {
    var target by mutableStateOf<FocusFrameTarget?>(null)
        private set

    fun report(
        key: Any, bounds: Rect, radiusPx: Float,
        visibility: State<Float>? = null, transform: FocusFrameTransform? = null,
        scrollPlacement: FocusScrollPlacement? = null,
        insetPx: Float = Float.NaN,
    ) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val current = target
        if (current?.key == key && current.visibility === visibility && current.transform === transform &&
            current.scrollPlacement === scrollPlacement &&
            abs(current.bounds.left - bounds.left) < 0.75f &&
            abs(current.bounds.top - bounds.top) < 0.75f &&
            abs(current.bounds.width - bounds.width) < 0.75f &&
            abs(current.bounds.height - bounds.height) < 0.75f &&
            abs(current.radiusPx - radiusPx) < 0.75f &&
            (current.insetPx.isNaN() && insetPx.isNaN() || abs(current.insetPx - insetPx) < 0.75f)
        ) return
        target = FocusFrameTarget(key, bounds, radiusPx, visibility, transform, scrollPlacement, insetPx)
    }

    fun clear(key: Any) {
        if (target?.key == key) target = null
    }
}

internal val LocalFocusFrameController = staticCompositionLocalOf<FocusFrameController?> { null }
internal val LocalFocusFrameEnabled = staticCompositionLocalOf { true }
internal val LocalFocusFrameVisibility = staticCompositionLocalOf<State<Float>?> { null }

/**
 * 让子树内所有焦点外圈的可见度跟随 [alpha]。
 * 用于播放页：控制栏自动淡出(alpha→0)时，外圈同步淡出而不残留。
 * 用 rememberUpdatedState 包一层，使重组时 alpha 变化能传到 overlay 每帧读取的 State。
 */
@Composable
internal fun ProvideFocusFrameVisibility(alpha: Float, content: @Composable () -> Unit) {
    val state = rememberUpdatedState(alpha)
    CompositionLocalProvider(LocalFocusFrameVisibility provides state) { content() }
}

internal class FocusFrameGeometry(var bounds: Rect? = null)

/**
 * 滚动中的焦点位置预修正：把"本帧即将发生的滚动位移"提前应用到焦点框
 * 绘制位置，焦点圈在滚动动画期间始终贴在卡片的最终落点上。
 * （移植自 am_atv FocusScrollPlacement，去掉纵向滚动偏移与本项目无关的分支。）
 */
internal class FocusScrollPlacement(
    private val state: androidx.compose.foundation.lazy.LazyListState,
    private val spec: androidx.compose.foundation.gestures.BringIntoViewSpec,
    private val viewport: FocusFrameGeometry,
    private val verticalScrollOffset: State<Float>? = null,
    private val direction: LayoutDirection = LayoutDirection.Ltr,
) {
    fun destination(bounds: Rect, focusedScale: Float): Rect {
        val viewportBounds = viewport.bounds ?: return bounds
        if (viewportBounds.width <= 0f) return bounds
        val visual = visualBounds(bounds, focusedScale)
        val requested = spec.calculateScrollDistance(
            visual.left - viewportBounds.left, visual.width, viewportBounds.width,
        )
        val layout = state.layoutInfo
        val first = layout.visibleItemsInfo.firstOrNull()
        val last = layout.visibleItemsInfo.lastOrNull()
        val distance = reachableFocusScroll(
            requested,
            first?.takeIf { it.index == 0 }?.offset,
            last?.takeIf { it.index == layout.totalItemsCount - 1 }?.let { it.offset + it.size },
            layout.viewportEndOffset,
            layout.afterContentPadding,
            beforePadding = layout.beforeContentPadding,
            isRtl = direction == LayoutDirection.Rtl,
        )
        return bounds.translate(-distance, -(verticalScrollOffset?.value ?: 0f))
    }
}

internal fun reachableFocusScroll(
    requested: Float,
    firstItemOffset: Int?,
    lastItemEnd: Int?,
    viewportEnd: Int,
    afterPadding: Int,
    beforePadding: Int = 0,
    isRtl: Boolean = false,
): Float {
    // Lazy offsets are measured from logical start; bounds and requested
    // distance are physical. Clamp in one coordinate system, then mirror back.
    val sign = if (isRtl) -1f else 1f
    // 下限必须与上限对称地扣除"约束尺寸"：LazyListItemInfo.offset 从容器起点量起，
    // 已包含 start contentPadding（本项目中 pageHorizontalInset，40~120dp）。
    // 旧实现直接拿 firstItemOffset.coerceAtMost(0)，隐含假设左侧 padding 为 0：
    // 行首 item 尚未滚到容器起点时 offset 仍为正，下限被压成 0，负向（回退）的
    // 预测滚动被裁掉，于是"滑到末尾再回到第一个"时焦点框预修正量丢失、与卡片错位。
    // 这里先减去 beforeContentPadding 再夹取，尾侧 afterPadding 同理保持原样。
    val minimum = firstItemOffset?.let { (it - beforePadding).coerceAtMost(0).toFloat() }
        ?: Float.NEGATIVE_INFINITY
    val maximum = lastItemEnd?.let { (it + afterPadding - viewportEnd).coerceAtLeast(0).toFloat() }
        ?: Float.POSITIVE_INFINITY
    return (requested * sign).coerceIn(minimum, maximum) * sign
}

internal val LocalFocusScrollPlacement = staticCompositionLocalOf<FocusScrollPlacement?> { null }

/**
 * 焦点外圈的统一默认值。所有 TouchSurface/TouchCard 在无显式指定时共用，
 * 保证各页面外圈表现一致；个别卡片（如 Spotlight/Gradient）通过 focusRadius/focusScale 覆盖。
 */
internal val DefaultFocusFrameRadius: Dp = 12.dp
internal val DefaultFocusFrameScale: Float = 1.088f

/**
 * 外圈相对控件的额外外扩。外圈是画在控件之外的独立装饰层：
 * - 卡片：控件聚焦时会放大（focusScale），外圈包住放大后的足迹 + 这个 inset；
 * - 不缩放的控件（设置行/开关/药丸）：控件本身放大 1f，外圈不应再按 1.088 放大，
 *   否则会比控件明显大一圈。调用方传 focusScale=1f 后，外圈只比控件大这个 inset。
 *
 * 默认保持 4.dp（卡片外观不变）；不缩放的设置行通过 focusInset=1.dp 显式贴合边界。
 */
internal val DefaultFocusFrameInset: Dp = 4.dp

/**
 * 卡片焦点外圈修饰符：把放大后的绘制足迹与滚动位置报告给 overlay。
 *
 * @param focusInset 外圈相对控件的额外外扩；不缩放控件应传 1.dp 贴合边界。
 * @param clipToControl 为 true 时外圈尺寸严格等于控件布局边界（忽略 focusScale 与 inset），
 *   用于长条形设置行——它们 Material scale=1f 且宽度接近整屏，若按 focusScale 放大会横向溢出。
 */
@Suppress("ModifierComposed")
internal fun Modifier.focusFrame(
    cornerRadius: Dp = DefaultFocusFrameRadius,
    focusedScale: Float = DefaultFocusFrameScale,
    focusInset: Dp = DefaultFocusFrameInset,
    clipToControl: Boolean = false,
): Modifier = composed {
    val controller = LocalFocusFrameController.current ?: return@composed this
    val enabled = LocalFocusFrameEnabled.current
    if (!enabled) return@composed this
    val placement = LocalFocusScrollPlacement.current
    // visibility 由可淡出子树（播放页控制栏）provide：控件 alpha→0 时外圈同步淡出，
    // 避免控件隐藏后外圈残留。默认 null＝始终可见，其它页面行为不变。
    val visibility = LocalFocusFrameVisibility.current
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    val insetPx = with(LocalDensity.current) { focusInset.toPx() }
    val key = remember { Any() }
    val geometry = remember { FocusFrameGeometry() }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(controller, key) { onDispose { controller.clear(key) } }
    fun reportBounds(bounds: androidx.compose.ui.geometry.Rect) {
        val frame = if (clipToControl) bounds else visualBounds(bounds, focusedScale)
        // 严格贴合模式 inset 归零：外圈描边正好压在控件边界上，不会比选项大一圈。
        controller.report(key, frame, radiusPx, visibility = visibility,
            insetPx = if (clipToControl) 0f else insetPx, scrollPlacement = placement)
    }
    this
        .onGloballyPositioned { coordinates ->
            geometry.bounds = coordinates.focusFrameBounds()
            if (focused) geometry.bounds?.let(::reportBounds)
        }
        .onFocusChanged {
            focused = it.isFocused
            geometry.bounds?.let { bounds ->
                if (it.isFocused) reportBounds(bounds) else controller.clear(key)
            }
        }
}

/** One continuous spring; retargeting never cancels its clock or loses velocity. */
internal class FocusFrameMotion {
    private val values = FloatArray(5)
    private val velocities = FloatArray(5)
    private val anchor = FloatArray(5)
    private var anchorKey: Any? = null
    private var initialized = false
    var opacity = 0f
        private set
    var isMoving = false
        private set
    val left get() = values[0]
    val top get() = values[1]
    val width get() = values[2]
    val height get() = values[3]
    val radius get() = values[4]

    fun advance(
        deltaNanos: Long,
        left: Float, top: Float, width: Float, height: Float, radius: Float,
        visible: Boolean,
        followImmediately: Boolean = false,
        key: Any? = null,
    ) {
        if (!initialized && !visible) return
        val snap = !initialized || followImmediately || (visible && opacity == 0f)
        initialized = true
        val seconds = (deltaNanos / 1_000_000_000.0).coerceIn(0.0, 0.25)
        var moving = false
        for (index in values.indices) {
            val target = when (index) {
                0 -> left
                1 -> top
                2 -> width
                3 -> height
                else -> radius
            }
            // 同一对象的锚点迁移：滚动视口不会制造第二次追逐/回弹。
            if (initialized && key != null && key == anchorKey) {
                values[index] += target - anchor[index]
            }
            anchor[index] = target
            if (snap) {
                values[index] = target
                velocities[index] = 0f
                continue
            }
            val stiffness = if (index < 2) 430.0 else 500.0
            val damping = if (index < 2) 0.88 else 0.92
            val frequency = kotlin.math.sqrt(stiffness)
            val decay = damping * frequency
            val damped = frequency * kotlin.math.sqrt(1.0 - damping * damping)
            val displacement = (values[index] - target).toDouble()
            val velocity = velocities[index].toDouble()
            val envelope = kotlin.math.exp(-decay * seconds)
            val c = kotlin.math.cos(damped * seconds)
            val s = kotlin.math.sin(damped * seconds)
            values[index] = target + (envelope * (
                displacement * c + (velocity + decay * displacement) / damped * s
            )).toFloat()
            velocities[index] = (envelope * (
                velocity * c - (decay * velocity + stiffness * displacement) / damped * s
            )).toFloat()
            if (abs(values[index] - target) < 0.05f && abs(velocities[index]) < 0.5f) {
                values[index] = target
                velocities[index] = 0f
            } else moving = true
        }
        anchorKey = key
        val targetOpacity = if (visible) 1f else 0f
        opacity = if (snap && visible) 1f else {
            (targetOpacity + (opacity - targetOpacity) * kotlin.math.exp(-seconds / 0.065)).toFloat()
        }
        if (abs(opacity - targetOpacity) < 0.002f) opacity = targetOpacity
        isMoving = moving || opacity != targetOpacity
    }
}

/** 常驻渐变描边 colorizer；焦点移动只改局部矩阵与 alpha。 */
private class FocusFramePainter {
    // 深浅两套配色：深色主题用亮白渐变；浅色主题用深色系渐变，保证白底上有足够对比度。
    private val darkOuterGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
        android.graphics.Color.argb((255f * .96f).roundToInt(), 255, 255, 255),
        android.graphics.Color.argb((255f * .66f).roundToInt(), 224, 244, 255),
        android.graphics.Color.argb((255f * .23f).roundToInt(), 255, 255, 255),
        android.graphics.Color.argb((255f * .12f).roundToInt(), 255, 255, 255),
    ), null, Shader.TileMode.CLAMP)
    private val darkInnerGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
        android.graphics.Color.argb((255f * .37f).roundToInt(), 255, 255, 255),
        android.graphics.Color.TRANSPARENT,
    ), null, Shader.TileMode.CLAMP)
    // 浅色（白底）：主体用深墨色 #1D1D1F，中段掺一点品牌红调以延续渐变层次。
    private val lightOuterGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
        android.graphics.Color.argb((255f * .96f).roundToInt(), 29, 29, 31),
        android.graphics.Color.argb((255f * .72f).roundToInt(), 72, 72, 80),
        android.graphics.Color.argb((255f * .42f).roundToInt(), 29, 29, 31),
        android.graphics.Color.argb((255f * .24f).roundToInt(), 29, 29, 31),
    ), null, Shader.TileMode.CLAMP)
    private val lightInnerGradient = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(
        android.graphics.Color.argb((255f * .32f).roundToInt(), 29, 29, 31),
        android.graphics.Color.TRANSPARENT,
    ), null, Shader.TileMode.CLAMP)
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = darkOuterGradient
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        shader = darkInnerGradient
    }
    private val matrix = Matrix()
    private val values = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)

    private fun position(shader: Shader, x: Float, y: Float, dx: Float, dy: Float) {
        // 单位横向渐变按均匀缩放+旋转映射；两轴分别拉伸会改变对角取的色标位置。
        values[0] = dx; values[1] = -dy; values[2] = x
        values[3] = dy; values[4] = dx; values[5] = y
        matrix.setValues(values)
        shader.setLocalMatrix(matrix)
    }

    /** @param dark true=深色主题用亮白渐变；false=浅色主题用深墨渐变。 */
    fun draw(canvas: android.graphics.Canvas, x: Float, y: Float, w: Float, h: Float,
        radius: Float, opacity: Float, density: Float, dark: Boolean) {
        if (w <= 0f || h <= 0f) return
        val outerGradient = if (dark) darkOuterGradient else lightOuterGradient
        val innerGradient = if (dark) darkInnerGradient else lightInnerGradient
        outerPaint.shader = outerGradient
        innerPaint.shader = innerGradient
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
        outerPaint.alpha = alpha
        innerPaint.alpha = alpha
        outerPaint.strokeWidth = 2.5f * density
        innerPaint.strokeWidth = .7f * density
        position(outerGradient, x, y, w, h)
        canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, outerPaint)
        val innerWidth = (w - 4f * density).coerceAtLeast(0f)
        val innerHeight = (h - 4f * density).coerceAtLeast(0f)
        val innerRadius = (radius - 2f * density).coerceAtLeast(0f)
        // 原始端点固定在 y+1dp 与 y+.65*h，与外框内缩无关。
        val span = h * .65f - density
        if (innerWidth > 0f && innerHeight > 0f && span > 0f) {
            position(innerGradient, x, y + density, 0f, span)
            canvas.drawRoundRect(x + 2f * density, y + 2f * density,
                x + 2f * density + innerWidth, y + 2f * density + innerHeight,
                innerRadius, innerRadius, innerPaint)
        }
    }
}

/** 挂在根布局最上层：一整条 spring 跟随焦点位置绘制渐变外圈。 */
@Composable
internal fun FocusFrameOverlay(controller: FocusFrameController) {
    val motion = remember { FocusFrameMotion() }
    val frame = remember { mutableLongStateOf(0L) }
    val retained = remember { arrayOfNulls<FocusFrameTarget>(1) }
    val sampledBounds = remember { arrayOfNulls<Rect>(1) }
    val painter = remember { FocusFramePainter() }
    LaunchedEffect(controller) {
        var observed: FocusFrameTarget? = null
        var previousNanos = 0L
        while (isActive) {
            if (!motion.isMoving && controller.target == observed) {
                snapshotFlow { controller.target }.first { it != observed }
                previousNanos = 0L
            }
            withFrameNanos { now ->
                val target = controller.target
                observed = target
                val destination = target ?: retained[0]
                if (destination != null) {
                    val rect = destination.destinationBounds()
                    motion.advance(
                        deltaNanos = if (previousNanos == 0L) 0L else now - previousNanos,
                        left = rect.left, top = rect.top,
                        width = rect.width, height = rect.height, radius = destination.radiusPx,
                        visible = target != null,
                        followImmediately = destination.transform?.followImmediately == true ||
                            (target?.visibility?.value?.let { it < 0.999f } ?: false),
                        key = destination.key,
                    )
                    retained[0] = destination
                    sampledBounds[0] = rect
                }
                previousNanos = now
                frame.longValue = now
            }
        }
    }

    // 主题随深浅模式切换；mutableState 读取会订阅重组，切主题后下一帧即用新配色。
    val darkTheme = com.veacon.veilmusic.ui.theme.AppPalette.isDark
    Canvas(Modifier.fillMaxSize()) {
        frame.longValue // 帧变化只触发重绘。
        val sampled = retained[0] ?: return@Canvas
        val sampledRect = sampledBounds[0] ?: return@Canvas
        // Placement 可能在本帧时钟之后运行：绘制时取最新锚点，不再等下一帧。
        val target = controller.target?.takeIf { it.key == sampled.key } ?: sampled
        val destination = target.destinationBounds()
        val dx = destination.left - sampledRect.left
        val dy = destination.top - sampledRect.top
        val width = motion.width + destination.width - sampledRect.width
        val height = motion.height + destination.height - sampledRect.height
        val radius = motion.radius + target.radiusPx - sampled.radiusPx
        val opacity = motion.opacity * (target.visibility?.value ?: 1f)
        if (opacity < 0.01f) return@Canvas
        val transform = target.transform
        val scale = transform?.scale?.value ?: 1f
        val lift = (transform?.liftPx ?: 0f) * (transform?.liftProgress?.value ?: 0f)
        // 用该 target 自己上报的 inset（0=严格贴合；老调用 NaN 回退 4.dp）。
        val inset = if (target.insetPx.isNaN()) 4.dp.toPx() else target.insetPx
        val x = motion.left + dx - width * (scale - 1f) / 2f - inset
        val y = motion.top + dy - height * (scale - 1f) / 2f - lift - inset
        val w = (width * scale + inset * 2f).coerceAtLeast(0f)
        val h = (height * scale + inset * 2f).coerceAtLeast(0f)
        val r = (radius * scale + inset).coerceAtLeast(0f)
        painter.draw(drawContext.canvas.nativeCanvas, x, y, w, h, r, opacity, density, darkTheme)
    }
}
