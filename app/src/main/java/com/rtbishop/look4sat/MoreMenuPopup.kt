/*
 * MoreMenuPopup.kt - bottom-nav "More" second-level menu popup panel (4.5.1).
 *
 * Overlays the content area (above the bottom bar, right-aligned), vertical menu items (icon+text+arrow),
 * current page highlighted; tap the scrim to close, tap an item to navigate. Open/close animation is driven by the caller
 * (MainScreen's AnimatedVisibility + spring).
 */
package com.rtbishop.look4sat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.rtbishop.look4sat.core.presentation.Screen

@Composable
fun MoreMenuPopup(
    items: List<Screen>,
    currentKey: NavKey?,
    onDismiss: () -> Unit,
    onSelect: (Screen) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss)
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                items.forEach { screen ->
                    val isSelected = when (currentKey) {
                        is Screen.Satellites -> screen is Screen.Satellites
                        is Screen.Passes -> screen is Screen.Passes
                        is Screen.Radar -> screen is Screen.Radar
                        is Screen.Mutual -> screen is Screen.Mutual
                        is Screen.CwDecode -> screen is Screen.CwDecode
                        is Screen.WavelogLog -> screen is Screen.WavelogLog
                        is Screen.Map -> screen is Screen.Map
                        is Screen.Settings -> screen is Screen.Settings
                        else -> false
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(screen) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            painter = painterResource(screen.iconResId),
                            contentDescription = stringResource(screen.titleResId),
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(screen.titleResId),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "›",
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
