package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ShubhankarDarkColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = ObsidianBlack,
    primaryContainer = NeonCyanContainer,
    onPrimaryContainer = NeonCyan,
    secondary = ElectricVioletBright,
    onSecondary = ObsidianBlack,
    secondaryContainer = ElectricVioletContainer,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = CyberMagenta,
    onTertiary = Color.White,
    tertiaryContainer = CyberMagentaContainer,
    onTertiaryContainer = TextPrimaryLight,
    background = ObsidianBlack,
    onBackground = TextPrimaryLight,
    surface = DeepVoidPurple,
    onSurface = TextPrimaryLight,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextSecondaryMuted,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = DeepVoidPurple,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = ElevatedCardPurple,
    surfaceContainerHighest = HighestSurfacePurple,
    outline = OutlineViolet,
    outlineVariant = Color(0xFF2B204A),
    error = CrimsonAlert,
    onError = Color.White,
    errorContainer = CrimsonContainer,
    onErrorContainer = TextPrimaryLight
)

val ShubhankarShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ShubhankarCloudTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ShubhankarDarkColorScheme,
        typography = ShubhankarTypography,
        shapes = ShubhankarShapes,
        content = content
    )
}
