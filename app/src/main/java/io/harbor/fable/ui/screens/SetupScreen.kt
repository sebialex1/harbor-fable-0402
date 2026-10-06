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
import androidx.compose.ui.graphics.ColorFilter
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
import io.harbor.fable.ui.components.*
import io.harbor.fable.ui.theme.FableAccent
import io.harbor.fable.ui.theme.FableAccentLight
import io.harbor.fable.ui.theme.FableBg
import io.harbor.fable.ui.theme.FableError
import io.harbor.fable.ui.theme.FableTextFaint
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.FableTrack
import io.harbor.fable.ui.theme.FableTextDim
import io.harbor.fable.ui.theme.FableWarn
import io.harbor.fable.ui.theme.Motion
import io.harbor.fable.ui.theme.PillRadius
import io.harbor.fable.ui.theme.RowPaddingHorizontal
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SetupStep { WELCOME, DOWNLOAD, READY }

/**
 * First-open setup. Three steps on a plain black canvas: a welcome,
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
                SetupStep.WELCOME -> WelcomeStep(onStart = onStart, onSkip = onSkip)
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
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    SetupColumn {
        Spacer(Modifier.weight(1f))
        Staggered(index = 0) { AppMark() }
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
                text = "Windows apps and games on Android",
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
        Spacer(Modifier.weight(1.2f))
        Staggered(index = 4) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                FableButton(
                    text = "Set Up",
                    primary = true,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                TextAction(text = "Not Now", onClick = onSkip)
            }
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/** The app mark, plain white on black. It settles into place once, without a halo. */
@Composable
private fun AppMark(size: androidx.compose.ui.unit.Dp = 120.dp) {
    val appear = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Motion.settle()) }
    Image(
        painter = painterResource(R.drawable.ic_launcher_monochrome),
        contentDescription = null,
        colorFilter = ColorFilter.tint(FableText),
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = appear.value; scaleY = appear.value },
    )
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
    // Only the required packages take part in setup; optional ones (FEX) are picked per container.
    val items = setup.required
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
                text = if (allDone) "Almost Done" else "Setting Up",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (allDone && setup.unavailable.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "Some packages have no build",
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
                    label = "$readyCount of ${items.size.coerceAtLeast(RecommendedKind.entries.count { it.required })}",
                    sublabel = if (setup.pendingBytes > 0) "${formatBytes(setup.pendingBytes)} left" else "",
                )
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
        Staggered(index = 3) {
            FableCard(Modifier.fillMaxWidth()) {
                // Optional kinds (FEX) are chosen per container later, not during first-run setup.
                val rows = RecommendedKind.entries.filter { it.required }
                rows.forEachIndexed { index, kind ->
                    if (index > 0) CardDivider(afterIcon = true)
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
                    title = "Setup failed",
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
                        done -> FableButton(
                            text = if (missing) "Continue Anyway" else "Continue",
                            primary = true,
                            onClick = onContinue,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        failed -> FableButton(
                            text = "Try Again",
                            primary = true,
                            onClick = onRetry,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        else -> Spacer(Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                TextAction(text = if (allDone) "Finish Later" else "Skip", onClick = onSkip)
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
    val statusText = when {
        installed -> ""
        downloading && progress >= 1f && kind == RecommendedKind.DRIVER -> "Installing"
        downloading -> "${(progress * 100).toInt()}%"
        preparing -> "Preparing"
        status == RecommendedStatus.AVAILABLE -> if (sizeBytes > 0) formatBytes(sizeBytes) else "Waiting"
        status == RecommendedStatus.UNAVAILABLE -> "No build"
        else -> ""
    }
    Box {
        ListRow(
            title = kind.label,
            icon = kindIcon(kind),
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
                    StateText(text)
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
    RecommendedKind.FEX -> Icons.Outlined.SwapHoriz
}

/** Overall progress as a thin white ring with the percentage in the middle. Real progress only. */
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
    Box(Modifier.size(148.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = FableTrack,
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
                drawArc(
                    color = FableText,
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
            Text(listOf(label, sublabel).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Step 3: ready ----------------------------------------------------------------------------

@Composable
private fun ReadyStep(setup: SetupState, onFinish: () -> Unit) {
    val missing = setup.required.filter { it.status != RecommendedStatus.INSTALLED }.map { it.kind.label }
    SetupColumn {
        Spacer(Modifier.weight(1f))
        Staggered(index = 0) { AppMark(size = 96.dp) }
        Spacer(Modifier.height(Spacing.xxxl))
        Staggered(index = 1) {
            Text(
                text = "Ready",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Staggered(index = 2) {
            Text(
                text = if (missing.isEmpty()) "" else "Get ${missing.joinToString(", ")} later in Assets.",
                style = MaterialTheme.typography.bodyLarge,
                color = FableTextDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
            )
        }
        Spacer(Modifier.weight(1.2f))
        Staggered(index = 3) {
            FableButton(
                text = "Open Fable",
                primary = true,
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
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
