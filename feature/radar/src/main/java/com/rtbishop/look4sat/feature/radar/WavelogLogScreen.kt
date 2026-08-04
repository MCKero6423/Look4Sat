/*
 * WavelogLogScreen.kt — 「日志」页面(更多菜单, 4.5.2 修复)。
 *
 * 表格形式展示本地存储的日志: 时间 | 频率 | 卫星 | 呼号 | 已上传(✓)。
 * 数据来自 WavelogQueue(与雷达页 Log 标签共用)。空态提示; 表格行
 * 复用左滑删除(同 LogTab 交互)。
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQueue
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency
import java.util.Calendar
import java.util.TimeZone

private val CheckGreen = Color(0xFF4CAF50)

@Composable
fun WavelogLogScreen(
    queue: WavelogQueue,
    modifier: Modifier = Modifier
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries = remember(refreshTick) { queue.all() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 表头
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            HeaderCell(stringResource(id = R.string.wavelog_col_time), 52.dp)
            HeaderCell(stringResource(id = R.string.wavelog_col_freq), 70.dp)
            HeaderCell(stringResource(id = R.string.wavelog_col_sat), 0.dp, weight = 1.2f)
            HeaderCell(stringResource(id = R.string.wavelog_col_call), 0.dp, weight = 1f)
            HeaderCell(stringResource(id = R.string.wavelog_col_uploaded), 44.dp)
        }

        if (entries.isEmpty()) {
            Text(
                text = stringResource(id = R.string.wavelog_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            entries.forEachIndexed { index, entry ->
                SwipeDeleteRow(
                    onDelete = {
                        queue.remove(entry.id)
                        refreshTick++
                    }
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Cell(formatLocalTime(entry.timeUtcMs), 74.dp)
                            Cell(formatFrequency(entry.freqTxHz), 62.dp)
                            Cell(entry.satName, 0.dp, weight = 1.2f)
                            Cell(entry.call, 0.dp, weight = 1f)
                            Box(modifier = Modifier.width(44.dp)) {
                                if (entry.uploaded) {
                                    Text(
                                        text = "✓",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CheckGreen
                                    )
                                }
                            }
                        }
                        // 表格分隔线
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, weight: Float = 0f) {
    val mod = if (weight > 0f) Modifier.weight(weight) else Modifier.width(width)
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = mod
    )
}

@Composable
private fun RowScope.Cell(text: String, width: androidx.compose.ui.unit.Dp, weight: Float = 0f) {
    val mod = if (weight > 0f) Modifier.weight(weight) else Modifier.width(width)
    Text(
        text = text,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = mod
    )
}

private fun formatLocalTime(utcMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.timeInMillis = utcMs
    return "%02d-%02d %02d:%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}
