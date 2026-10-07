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
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.BoxWithConstraints
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
import io.harbor.fable.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.harbor.fable.ui.icons.FableIcons

private enum class SetupStep { WELCOME, DOWNLOAD, READY }

/**
 * First-open setup. Three steps on a plain black canvas: a welcome,
 * a download step that shows the real progress of the recommended Wine, Box64, RADV Xclipse,
 * DXVK and VKD3D-Proton packages (through [io.harbor.fable.data.SetupManager]), and a ready step that hands over
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
            val requiredCount = items.size.coerceAtLeast(RecommendedKind.entries.count { it.required })
            val indeterminate = preparing && tracked.isEmpty()
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The ring scales with the screen instead of sitting as a small fixed badge.
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ringSize = (maxWidth * 0.62f).coerceIn(RingMinSize, RingMaxSize)
                    ProgressRing(
                        progress = setup.progress,
                        indeterminate = indeterminate,
                        label = "$readyCount of $requiredCount",
                        modifier = Modifier.size(ringSize),
                    )
                }
                Spacer(Modifier.height(Spacing.xl))
                // Overall progress across the full width of the column.
                OverallProgressBar(
                    progress = if (indeterminate) null else setup.progress,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = if (allDone) "Downloaded" else "Downloading",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (setup.pendingBytes > 0) "${formatBytes(setup.pendingBytes)} left" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
        AnimatedVisibility(visible = message != null, enter = fadeIn(Motion.enter()), exit = fadeOut(Motion.exit())) {
            Column {
                Spacer(Modifier.height(Spacing.md))
                NoticeCard(
                    icon = FableIcons.Error,
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
                        done && missing -> FableButton(
                            text = "Continue Anyway",
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

/** Overall progress as a thin white ring with the percentage in the middle. Real progress only. */
@Composable
private fun ProgressRing(progress: Float, indeterminate: Boolean, label: String, modifier: Modifier = Modifier) {
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
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
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
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val RingMinSize = 168.dp
private val RingMaxSize = 240.dp

/** Full-width overall progress: a 4dp white bar on the dark track. Null runs indeterminate. */
@Composable
private fun OverallProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    LineProgressBar(
        progress = progress,
        modifier = modifier.clip(RoundedCornerShape(percent = 50)),
        color = FableText,
        trackColor = FableTrack,
        thickness = 4.dp,
        rounded = false,
    )
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
                .widthIn(max = 560.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
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
