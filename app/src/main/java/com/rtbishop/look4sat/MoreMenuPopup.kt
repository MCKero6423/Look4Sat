/*
 * MoreMenuPopup.kt - bottom-nav "More" second-level menu popup panel (4.5.1).
 *
 * A slim right-aligned strip above the bottom bar, vertical menu items (icon+text+arrow),
 * current page highlighted; tap outside to close, tap an item to navigate. No dimming scrim, so the
 * page stays readable. Open/close animation is driven by the caller (MainScreen's AnimatedVisibility).
 */
package com.rtbishop.look4sat

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
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
            // Transparent catcher: taps outside the card dismiss the menu without
            // dimming the page behind it.
            .clickable(onClick = onDismiss)
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .widthIn(max = 232.dp)
                // Swallow taps on the card so they do not reach the dismiss
                // catcher underneath.
                .clickable(enabled = false) {},
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
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
