package com.aasa.eldercare.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Elder-friendly risk badge, used on the home screen above the parsed
 * response card. Maps a [RiskCopy] (already humanized in the
 * ViewModel) into a tinted pill plus a short explanation line.
 *
 * The badge itself is intentionally compact – the real focus on the
 * home screen is the assistant response.
 */
@Composable
fun RiskBadge(
    copy: RiskCopy,
    modifier: Modifier = Modifier
) {
    val (container, content) = badgeColors(copy.level)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BadgePill(
            text = copy.label,
            container = container,
            content = content
        )
        Text(
            text = copy.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BadgePill(text: String, container: Color, content: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun badgeColors(level: RiskCopy.RiskLevel): Pair<Color, Color> = when (level) {
    RiskCopy.RiskLevel.LOW -> MaterialTheme.colorScheme.secondaryContainer to
        MaterialTheme.colorScheme.onSecondaryContainer
    RiskCopy.RiskLevel.MEDIUM -> MaterialTheme.colorScheme.tertiaryContainer to
        MaterialTheme.colorScheme.onTertiaryContainer
    RiskCopy.RiskLevel.HIGH -> MaterialTheme.colorScheme.errorContainer to
        MaterialTheme.colorScheme.onErrorContainer
}
