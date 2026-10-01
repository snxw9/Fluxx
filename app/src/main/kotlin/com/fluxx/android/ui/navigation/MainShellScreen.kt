package com.fluxx.android.ui.navigation

import android.os.Build
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxx.android.ProjectMetadata
import com.fluxx.android.render.ProjectThumbnails
import com.fluxx.android.ui.home.CreateNewMark
import com.fluxx.android.ui.home.HomeScreen
import com.fluxx.android.ui.projects.ProjectOperation
import com.fluxx.android.ui.projects.ProjectsScreen
import com.fluxx.android.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

// One physics clock for release and nav taps. Creation opens the sheet directly.
private val NavSpring = spring<Float>(dampingRatio = .55f, stiffness = 300f, visibilityThreshold = .001f)

@Composable
fun MainShellScreen(
    projects: List<ProjectMetadata> = emptyList(),
    projectThumbnails: ProjectThumbnails? = null,
    onOpenProject: (File) -> Unit = {},
    onOpenCompositionSheet: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onProjectOperation: suspend (ProjectOperation, File, String) -> Result<Unit> = { _, _, _ ->
        Result.failure(IllegalStateException("Project storage is unavailable"))
    },
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberPagerState(pageCount = { 2 }),
    blurred: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragOrigin by remember(pagerState) { mutableIntStateOf(pagerState.currentPage) }
    var settlingProgress by remember { mutableStateOf<Float?>(null) }

    var navigating by remember { mutableStateOf(false) }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }


    var dockCenter by remember { mutableStateOf<Offset?>(null) }
    val currentOpenSheet by rememberUpdatedState(onOpenCompositionSheet)
    val velocityThreshold = with(density) { 400.dp.toPx() }
    val fling = remember(pagerState, velocityThreshold) {
        ShellFlingBehavior(pagerState, velocityThreshold, { dragOrigin }) { settlingProgress = it }
    }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                dragOrigin = pagerState.currentPage
                settlingProgress = null
            }
        }
    }
    suspend fun goTo(page: Int) {
        navigating = true
        try {
            pagerState.scroll {
                settlePages(pagerState, page, 0f) { settlingProgress = it }
            }
        } finally {
            settlingProgress = null
            navigating = false
        }
    }
    val pageProgress = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
    val progress = settlingProgress ?: pageProgress
    val f = progress.coerceIn(0f, 1f)
    val blur = if (blurred && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(16.dp) else Modifier
    BoxWithConstraints(modifier.fillMaxSize().then(blur).onGloballyPositioned { root = it }) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        Scaffold(
            topBar = { ShellTopBar(onUserClick = onOpenSettings) },
            bottomBar = {
                ShellBottomNav(progress, onTabSelected = { page ->
                    if (!navigating) scope.launch { goTo(page) }
                }, onCreateSlotPositioned = { coordinates ->
                    root?.takeIf { it.isAttached }?.let {
                        dockCenter = it.localPositionOf(coordinates, Offset.Zero)
                    }
                })
            }, containerColor = Color.Transparent,
            modifier = Modifier.background(BackgroundGradient)
        ) { padding ->
            HorizontalPager(state = pagerState, flingBehavior = fling, overscrollEffect = null,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { page ->
                when (page) {
                    0 -> HomeScreen(projects.firstOrNull(), projectThumbnails, onOpenProject,
                        onOpenCompositionSheet, Modifier.fillMaxSize(), createMark = { slot ->
                            CreateNewMark(onClick = currentOpenSheet, modifier = slot)
                        })
                    1 -> ProjectsScreen(projects, projectThumbnails, onOpenProject, onProjectOperation, Modifier.fillMaxSize())
                }
            }
        }
        val dock = dockCenter ?: Offset(widthPx / 2, heightPx - with(density) { 88.dp.toPx() })

        val circlePx = with(density) { 56.dp.toPx() }
        if (progress > 0f) {
            // Radius 28/32 of the cutout maintains a proportional 4dp collar at every frame.
            val notchRadius = with(density) { 32.dp.toPx() } * progress.coerceAtLeast(0f)
            val diameter = notchRadius * 2f * (28f / 32f)
            Box(Modifier.offset { IntOffset((dock.x - circlePx / 2).roundToInt(),
                (dock.y - circlePx / 2).roundToInt()) }.size(56.dp)
                .then(if (f == 1f && !pagerState.isScrollInProgress)
                    Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = currentOpenSheet)
                        .semantics { contentDescription = "Create new project" }
                else Modifier.clearAndSetSemantics {}),
                contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(with(density) { diameter.toDp() }).graphicsLayer { alpha = f }
                    .background(Highlight, CircleShape)
                    .depthHighlight(CircleShape, topHighlightAlpha = 0.15f, bottomShadowAlpha = 0.12f, upperRimAlpha = 0.25f),
                    contentAlignment = Alignment.Center) {
                    ProjectVectorIcon(ProjectGlyph.Plus, Color.Black,
                        Modifier.graphicsLayer { scaleX = progress; scaleY = progress })
                }
            }
        }
    }
}

/** Pager is bounded; the shared visual clock can overshoot so the socket and plug rebound together. */
private suspend fun ScrollScope.settlePages(state: PagerState, target: Int, velocity: Float,
    onProgress: (Float?) -> Unit) {
    val pageSize = state.layoutInfo.pageSize.toFloat()
    if (pageSize <= 0f) return
    val start = state.currentPage + state.currentPageOffsetFraction
    try {
        animate(start, target.toFloat(), initialVelocity = velocity / pageSize, animationSpec = NavSpring) { value, _ ->
            onProgress(value)
            val actual = state.currentPage + state.currentPageOffsetFraction
            scrollBy((value.coerceIn(0f, 1f) - actual) * pageSize)
        }
    } finally { onProgress(null) }
}

private class ShellFlingBehavior(private val state: PagerState, private val velocityThreshold: Float,
    private val dragOrigin: () -> Int, private val onProgress: (Float?) -> Unit) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
        val position = state.currentPage + state.currentPageOffsetFraction
        val displacement = position - dragOrigin()
        val direction = when {
            abs(initialVelocity) > velocityThreshold -> if (initialVelocity > 0f) 1 else -1
            abs(displacement) > .35f -> if (displacement > 0f) 1 else -1
            else -> 0
        }
        val target = (dragOrigin() + direction).coerceIn(0, 1)
        settlePages(state, target, initialVelocity) { value ->
            onProgress(value)
            onRemainingDistanceUpdated((target - (value ?: target.toFloat())) * state.layoutInfo.pageSize)
        }
        return 0f
    }
}

