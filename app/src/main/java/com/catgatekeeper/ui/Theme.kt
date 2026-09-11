package com.catgatekeeper.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CatColors = lightColorScheme(
    primary = Color(0xFF3D3A46),
    onPrimary = Color(0xFFF5E9C9),
    secondary = Color(0xFFE58C8A),
    onSecondary = Color(0xFF22202A),
    background = Color(0xFFFBF7EF),
    onBackground = Color(0xFF22202A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF22202A),
    surfaceVariant = Color(0xFFF1ECE2),
    onSurfaceVariant = Color(0xFF57525F),
    error = Color(0xFFB3261E),
)

@Composable
fun CatGatekeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CatColors, content = content)
}
