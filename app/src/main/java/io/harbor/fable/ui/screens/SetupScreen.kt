package io.harbor.fable.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.harbor.fable.R
import io.harbor.fable.app.FableApp
import io.harbor.fable.data.RecommendedItem
import io.harbor.fable.data.RecommendedKind
import io.harbor.fable.data.RecommendedStatus
import io.harbor.fable.data.SetupState
import io.harbor.fable.data.formatBytes
import io.harbor.fable.nativebridge.DeviceGpuInfo
import io.harbor.fable.nativebridge.DeviceProbe
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableSuccess
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SetupStep { WELCOME, DOWNLOAD, READY }

/**
 * First-open setup. Three steps on one liquid-glass canvas: a welcome that names the device,
 * a download step that shows the real progress of the recommended Wine, Box64, RADV Xclipse and
 * DXVK packages (through [io.harbor.fable.data.SetupManager]), and a ready step that hands over
 * to the app. [onFinished] is called when the user continues or skips; the caller persists it.
 */
@Composable
fun SetupScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { FableApp.from(context) }
    val setupManager = app.setupManager
    val setup by setupManager.state.collectAsStateWithLifecycle()
    val preparing by setupManager.installing.collectAsStateWithLifecycle()
    val fableUi = LocalFableUi.current
    val deviceInfo = remember { DeviceProbe.read() }

    var step by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    var lastMessage by remember { mutableStateOf<String?>(null) }

    // Downloads run as a foreground service; on Android 13+ its notification needs permission.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun startDownloads() {
        fableUi.scope.launch {
            val result = setupManager.installRecommended()
            lastMessage = when {
                result.busy -> null
                result.failed.isNotEmpty() || (!result.catalogReachable && result.unavailable.isNotEmpty()) -> result.message
                else -> null
            }
        }
    }

    SetupContent(
        step = step,
        setup = setup,
        preparing = preparing,
        message = lastMessage,
        deviceInfo = deviceInfo,
        onStart = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            step = SetupStep.DOWNLOAD
            startDownloads()
        },
        onRetry = {
            lastMessage = null
            startDownloads()
        },
        onContinue = { step = SetupStep.READY },
        onSkip = onFinished,
        onFinish = onFinished,
    )
}

@Composable
private fun SetupContent(
    step: SetupStep,
    setup: SetupState,
    preparing: Boolean,
    message: String?,
    deviceInfo: DeviceGpuInfo,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(FableBg),
    ) {
        LiquidBackdrop(intensity = if (step == SetupStep.READY) 1.15f else 1f)

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val sign = if (forward) 1 else -1
                (
                    fadeIn(Motion.enter(Motion.Slow, delay = 60)) +
                        slideInHorizontally(Motion.enter(Motion.Entrance)) { sign * it / 6 }
                    ) togetherWith (
                    fadeOut(Motion.exit(Motion.Quick)) +
                        slideOutHorizontally(Motion.exit(Motion.Standard)) { -sign * it / 10 }
                    )
            },
            label = "setupStep",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            when (current) {
                SetupStep.WELCOME -> WelcomeStep(deviceInfo = deviceInfo, onStart = onStart, onSkip = onSkip)
                SetupStep.DOWNLOAD -> DownloadStep(
                    setup = setup,
                    preparing = preparing,
                    message = message,
                    onRetry = onRetry,
                    onContinue = onContinue,
                    onSkip = onSkip,
                )
                SetupStep.READY -> ReadyStep(setup = setup, onFinish = onFinish)
            }
        }
    }
}

// --- Step 1: welcome --------------------------------------------------------------------------

@Composable
private fun WelcomeStep(deviceInfo: DeviceGpuInfo, onStart: () -> Unit, onSkip: () -> Unit) {
    SetupColumn {
        Spacer(Modifier.weight(1f))
        Staggered(index = 0) { GlassOrb() }
        Spacer(Modifier.height(Spacing.xxxl))
        Staggered(index = 1) {
            Text(
                text = "Fable",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = MaterialTheme.typography.headlineLarge.fontFamily,
                    fontWeight = MaterialTheme.typography.headlineLarge.fontWeight,
                    letterSpacing = (-1).sp,
                ),
                color = FableText,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Staggered(index = 2) {
            Text(
                text = "Windows apps and games on your Xclipse phone. Wine runs through Box64 with a Mesa RADV driver built for Samsung GPUs.",
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
        Staggered(index = 3) { DeviceChip(deviceInfo) }
        Spacer(Modifier.weight(1.2f))
        Staggered(index = 4) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GlassButton(
                    text = "Set up Fable",
                    icon = Icons.Outlined.RocketLaunch,
                    primary = true,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                TextAction(text = "Skip for now", onClick = onSkip)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The app mark floating in a lit glass sphere. */
@Composable
private fun GlassOrb() {
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.pop()) }
    Box(contentAlignment = Alignment.Center) {
        // Halo.
        Box(
            Modifier
                .size(196.dp)
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                .softBlur(28.dp)
                .background(
                    Brush.radialGradient(listOf(FableAccent.copy(alpha = 0.55f), Color.Transparent)),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(136.dp)
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.10f)),
                    ),
                )
                .drawBehind {
                    // Specular highlight near the top-left rim.
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                            center = Offset(size.width * 0.32f, size.height * 0.26f),
                            radius = size.width * 0.38f,
                        ),
                    )
                    // Inner shadow at the bottom for depth.
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                            center = Offset(size.width * 0.5f, size.height * 0.45f),
                            radius = size.width * 0.55f,
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(150.dp),
            )
        }
    }
}

@Composable
private fun DeviceChip(deviceInfo: DeviceGpuInfo) {
    val gpu = deviceInfo.gpu.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
    val xclipse = gpu?.contains("xclipse", ignoreCase = true) == true ||
        deviceInfo.vendor.contains("s5e", ignoreCase = true) || deviceInfo.vendor.contains("exynos", ignoreCase = true)
    val label = listOfNotNull(deviceInfo.device.ifBlank { null }, gpu?.let { formatGpu(it) }).joinToString(" · ")
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = if (xclipse) Icons.Outlined.Verified else Icons.Outlined.Smartphone,
            contentDescription = null,
            tint = if (xclipse) FableSuccess else FableTextDim,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (label.isNotBlank()) label else "Unknown device",
            style = MaterialTheme.typography.labelMedium,
            color = FableText,
        )
    }
}

private fun formatGpu(raw: String): String = when {
    raw.contains("xclipse", ignoreCase = true) -> "Xclipse ${raw.filter { it.isDigit() }}".trim()
    else -> raw
}

// --- Step 2: download -------------------------------------------------------------------------

@Composable
private fun DownloadStep(
    setup: SetupState,
    preparing: Boolean,
    message: String?,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val items = setup.items
    val tracked = items.filter { it.status != RecommendedStatus.UNAVAILABLE }
    val readyCount = items.count { it.status == RecommendedStatus.INSTALLED }
    val allDone = items.isNotEmpty() && !preparing && !setup.needsSetup
    val everythingReady = allDone && setup.unavailable.isEmpty()

    // When everything landed, move on by itself after a beat so the user sees the full ring.
    LaunchedEffect(everythingReady) {
        if (everythingReady) {
            delay(900)
            onContinue()
        }
    }

    SetupColumn {
        Spacer(Modifier.weight(0.6f))
        Staggered(index = 0) {
            Text(
                text = if (allDone) "Almost there" else "Setting things up",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.xs))
        Staggered(index = 1) {
            Text(
                text = when {
                    preparing && readyCount == 0 -> "Checking the latest releases…"
                    setup.isDownloading -> "Downloading the recommended runtime"
                    allDone -> if (setup.unavailable.isEmpty()) "Everything is in place" else "Some packages have no build yet"
                    else -> "Downloads continue in the background"
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.xxl))
        Staggered(index = 2) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = setup.progress,
                    indeterminate = preparing && tracked.isEmpty(),
                    label = "$readyCount of ${items.size.coerceAtLeast(RecommendedKind.entries.size)}",
                    sublabel = if (setup.pendingBytes > 0) "${formatBytes(setup.pendingBytes)} left" else "ready",
                )
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
        Staggered(index = 3) {
            GlassCard(Modifier.fillMaxWidth()) {
                val rows = RecommendedKind.entries
                rows.forEachIndexed { index, kind ->
                    if (index > 0) CardDivider()
                    val item = items.firstOrNull { it.kind == kind }
                    SetupItemRow(kind = kind, item = item, preparing = preparing)
                }
            }
        }
        AnimatedVisibility(visible = message != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Column {
                Spacer(Modifier.height(Spacing.md))
                NoticeCard(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Setup hit a snag",
                    lines = listOfNotNull(message),
                    tint = FableError,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Staggered(index = 4) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = Triple(allDone, message != null, setup.unavailable.isNotEmpty()),
                    transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
                    label = "setupAction",
                ) { (done, failed, missing) ->
                    when {
                        done -> GlassButton(
                            text = if (missing) "Continue anyway" else "Continue",
                            icon = Icons.AutoMirrored.Outlined.ArrowForward,
                            primary = true,
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        failed -> GlassButton(
                            text = "Try again",
                            icon = Icons.Outlined.Refresh,
                            primary = true,
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Text(
                            text = "You can leave the app; downloads keep going and show in the notification shade.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                TextAction(text = if (allDone) "Finish later" else "Skip and finish later", onClick = onSkip)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun SetupItemRow(kind: RecommendedKind, item: RecommendedItem?, preparing: Boolean) {
    val status = item?.status
    val installed = status == RecommendedStatus.INSTALLED
    val downloading = status == RecommendedStatus.DOWNLOADING
    val progress = item?.progress ?: 0f
    val sizeBytes = item?.sizeBytes ?: 0L
    val description = when (kind) {
        RecommendedKind.WINE -> "Windows compatibility layer"
        RecommendedKind.BOX64 -> "Runs x86_64 code on ARM64"
        RecommendedKind.DRIVER -> "Mesa Vulkan driver for Xclipse"
        RecommendedKind.DXVK -> "DirectX 9–11 on Vulkan"
    }
    val statusText = when {
        installed -> "Ready"
        downloading && progress >= 1f && kind == RecommendedKind.DRIVER -> "Installing"
        downloading -> "${(progress * 100).toInt()}%"
        preparing -> "Preparing"
        status == RecommendedStatus.AVAILABLE -> if (sizeBytes > 0) formatBytes(sizeBytes) else "Waiting"
        status == RecommendedStatus.UNAVAILABLE -> "No build"
        else -> ""
    }
    val tint = when {
        installed -> FableSuccess
        status == RecommendedStatus.UNAVAILABLE && !preparing -> FableWarn
        else -> FableAccent
    }
    Box {
        ListRow(
            title = kind.label,
            subtitle = description,
            icon = kindIcon(kind),
            iconTint = tint,
            showChevron = false,
            trailing = {
                AnimatedContent(
                    targetState = statusText,
                    transitionSpec = {
                        (fadeIn(Motion.enter(Motion.Quick)) + scaleIn(Motion.enter(Motion.Quick), initialScale = 0.85f)) togetherWith
                            fadeOut(Motion.exit(Motion.Fast))
                    },
                    label = "setupItemStatus",
                ) { text ->
                    when {
                        installed -> Pill(text = text, color = FableSuccess, icon = Icons.Outlined.Check)
                        status == RecommendedStatus.UNAVAILABLE && !preparing -> Pill(text = text, color = FableWarn)
                        else -> Pill(text = text, color = if (downloading) FableAccent else FableTextDim)
                    }
                }
            },
        )
        AnimatedVisibility(
            visible = downloading || (preparing && !installed),
            enter = fadeIn(Motion.enter(Motion.Quick)),
            exit = fadeOut(Motion.exit(Motion.Standard)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = RowPaddingHorizontal),
        ) {
            ThinProgressBar(progress = if (downloading && progress < 1f) progress else null)
        }
    }
}

private fun kindIcon(kind: RecommendedKind) = when (kind) {
    RecommendedKind.WINE -> Icons.Outlined.WineBar
    RecommendedKind.BOX64 -> Icons.Outlined.Terminal
    RecommendedKind.DRIVER -> Icons.Outlined.Memory
    RecommendedKind.DXVK -> Icons.Outlined.Layers
}

/** Overall progress as a glowing ring with the count in the middle. Real progress only. */
@Composable
private fun ProgressRing(progress: Float, indeterminate: Boolean, label: String, sublabel: String) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.settle(), label = "ringProgress")
    val spin = remember { Animatable(0f) }
    LaunchedEffect(indeterminate) {
        if (indeterminate) {
            while (true) {
                spin.snapTo(0f)
                spin.animateTo(360f, androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing))
            }
        }
    }
    Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val sweep = if (indeterminate) 80f else 360f * animated
            val start = if (indeterminate) spin.value - 90f else -90f
            if (sweep > 0f) {
                // Soft glow under the arc.
                drawArc(
                    color = FableAccent.copy(alpha = 0.35f),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke * 1.9f, cap = StrokeCap.Round),
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to FableAccentLight,
                        0.5f to FableAccent,
                        1f to FableAccentLight,
                    ),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (indeterminate) "…" else "${(animated * 100).toInt()}%",
                style = MaterialTheme.typography.headlineLarge,
                color = FableText,
            )
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(sublabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// --- Step 3: ready ----------------------------------------------------------------------------

@Composable
private fun ReadyStep(setup: SetupState, onFinish: () -> Unit) {
    val ready = setup.items.filter { it.status == RecommendedStatus.INSTALLED }.map { it.kind.label }
    val missing = setup.items.filter { it.status != RecommendedStatus.INSTALLED }.map { it.kind.label }
    SetupColumn {
        Spacer(Modifier.weight(1f))
        Staggered(index = 0) { SuccessMark() }
        Spacer(Modifier.height(Spacing.xxxl))
        Staggered(index = 1) {
            Text(
                text = if (missing.isEmpty()) "You're all set" else "Ready to go",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Staggered(index = 2) {
            Text(
                text = buildString {
                    if (ready.isNotEmpty()) append(ready.joinToString(", ")).append(if (ready.size > 1) " are ready." else " is ready.")
                    if (missing.isNotEmpty()) {
                        if (isNotEmpty()) append(' ')
                        append(missing.joinToString(", ")).append(" can be added later from the Assets tab.")
                    }
                    if (isEmpty()) append("Add an app from the Home tab to start.")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
        Spacer(Modifier.weight(1.2f))
        Staggered(index = 3) {
            GlassButton(
                text = "Open Fable",
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                primary = true,
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun SuccessMark() {
    val scale = remember { Animatable(0.4f) }
    val check = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, Motion.pop())
        check.animateTo(1f, Motion.enter(Motion.Standard))
    }
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(176.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .softBlur(26.dp)
                .background(Brush.radialGradient(listOf(FableSuccess.copy(alpha = 0.45f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .size(120.dp)
                .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(FableSuccess.copy(alpha = 0.32f), FableSuccess.copy(alpha = 0.10f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = FableSuccess,
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        alpha = check.value
                        scaleX = 0.6f + 0.4f * check.value
                        scaleY = 0.6f + 0.4f * check.value
                    },
            )
        }
    }
}

// --- Shared ---------------------------------------------------------------------------------

@Composable
private fun SetupColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 480.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** Quiet text button for secondary choices ("Skip"). */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = FableTextDim,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    )
}
