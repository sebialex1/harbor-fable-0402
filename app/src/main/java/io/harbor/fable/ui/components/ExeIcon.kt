package io.harbor.fable.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.harbor.fable.data.ExeIcons
import io.harbor.fable.ui.theme.FableBorder
import io.harbor.fable.ui.theme.FableSurfaceHigh
import io.harbor.fable.ui.theme.FableSurfaceRaised
import io.harbor.fable.ui.theme.FableText
import io.harbor.fable.ui.theme.HairlineStroke
import io.harbor.fable.ui.theme.Motion

/**
 * The tile for a Windows program: its own icon (extracted from the EXE when it was added) on a
 * raised grey square, or — when it has none — its initials in white on a soft grey gradient.
 * Monochrome either way; the program icon is the only colour the UI ever shows.
 *
 * Pass [bitmap] for an icon already in memory (the Add App preview), or [iconPath] for a stored
 * one, which is decoded off the main thread.
 */
@Composable
fun ExeIcon(
    name: String,
    modifier: Modifier = Modifier,
    iconPath: String? = null,
    bitmap: Bitmap? = null,
    size: Dp = RowIconSize,
) {
    val loaded by produceState<Bitmap?>(initialValue = bitmap, bitmap, iconPath) {
        value = bitmap ?: iconPath?.let { ExeIcons.load(it) }
    }
    val shape = RoundedCornerShape(size * 0.24f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .border(HairlineStroke, FableBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = loaded, animationSpec = Motion.inPlace(Motion.Quick), label = "exeIcon") { icon ->
            if (icon != null) {
                Box(
                    Modifier
                        .size(size)
                        .background(FableSurfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        // Small icons (16/32 px) scale up crisply instead of smearing.
                        filterQuality = if (icon.width < 48) FilterQuality.None else FilterQuality.Medium,
                        modifier = Modifier.size(size * 0.78f),
                    )
                }
            } else {
                Box(
                    Modifier
                        .size(size)
                        .background(Brush.verticalGradient(listOf(FableSurfaceHigh, FableSurfaceRaised))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = initialsOf(name),
                        color = FableText,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = (size.value * 0.4f).sp,
                            lineHeight = (size.value * 0.44f).sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "Hollow Knight" -> "HK", "setup.exe" -> "S", "" -> "?". */
internal fun initialsOf(name: String): String {
    val words = name.substringBeforeLast('.', name)
        .split(' ', '-', '_', '.')
        .filter { word -> word.any(Char::isLetterOrDigit) }
    val letters = when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].first(Char::isLetterOrDigit).toString()
        else -> "${words[0].first(Char::isLetterOrDigit)}${words[1].first(Char::isLetterOrDigit)}"
    }
    return letters.uppercase().ifEmpty { "?" }
}

/**
 * The stored icon of a program, decoded off the main thread; null while loading or when it has
 * none. Used where the icon is a material rather than a tile (the play button's blurred filler).
 */
@Composable
fun rememberExeBitmap(iconPath: String?): Bitmap? {
    val loaded by produceState<Bitmap?>(initialValue = null, iconPath) {
        value = iconPath?.let { ExeIcons.load(it) }
    }
    return loaded
}

