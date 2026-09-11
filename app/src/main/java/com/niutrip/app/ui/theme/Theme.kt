package com.niutrip.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val colors = lightColorScheme(
    primary = Green500, onPrimary = Color.White, primaryContainer = Green50,
    onPrimaryContainer = Green700, background = Background, onBackground = Ink,
    surface = Color.White, onSurface = Ink, outline = Line, error = Danger,
)

@Composable fun NiuTripTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography.copy(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        bodyMedium = TextStyle(fontSize = 14.sp), labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    ), content = content)
}
