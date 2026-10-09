package com.overlingo.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark theme in the desktop version's colors (blue #3B82F6 on dark gray). */
private val OverlingoColors = darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF9CA3AF),
    background = Color(0xFF111827),
    onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFFD1D5DB),
    surfaceContainer = Color(0xFF1F2937),
    surfaceContainerHigh = Color(0xFF263244),
    surfaceContainerLow = Color(0xFF172030),
    outline = Color(0xFF4B5563),
    error = Color(0xFFF87171),
)

@Composable
fun OverlingoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OverlingoColors, content = content)
}
