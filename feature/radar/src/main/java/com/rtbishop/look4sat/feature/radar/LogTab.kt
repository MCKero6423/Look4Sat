/*
 * LogTab.kt - "Log" tab of the radar page (4.5.2 WaveLog logging).
 *
 * Layout: transponder list (tap to select, watch frequency while typing) -> callsign input, Enter saves locally ->
 * local records list below (time/freq/callsign). Records support custom swipe-to-delete:
 * yellow trash icon shows while swiping; past 75% it becomes an undo key; not undone within 5 s means deleted.
 *
 * Enter only saves locally (WavelogQueue); upload is triggered by the 10-min cycle or manually (avoids mis-copied calls).
 */
package com.rtbishop.look4sat.feature.radar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.DopplerFrequencyCalculator
import com.rtbishop.look4sat.core.domain.qrz.QrzGrid
import com.rtbishop.look4sat.core.domain.wavelog.CallsignEntry
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQso
import com.rtbishop.look4sat.core.domain.wavelog.WavelogQueue
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.formatFrequency
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import kotlin.math.roundToInt

private val WaveLogYellow = Color(0xFFFFC107)

/** Repeat "done" events for the same callsign inside this window are treated as one QSO. */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTab(
    transceivers: List<SatRadio>,
    orbitalPos: OrbitalPos?,
    satelliteName: String,
    satelliteCatnum: Int,
    queue: WavelogQueue,
    wavelogConfigured: Boolean,
    showToast: (String) -> Unit,
    txBaseFrequencyHz: Long? = null,
    aosTimeMs: Long = 0L,
    onLookupGrid: (String, (QrzGrid) -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val entries = remember(refreshTick) { queue.all() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (!wavelogConfigured) {
            Text(
                text = stringResource(id = R.string.wavelog_need_config),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Transponder picker (dropdown): input area appears below once selected
        // Selected item derived live from transceivers by uuid (Doppler freq updates every second, display refreshes with it)
        var menuExpanded by remember { mutableStateOf(false) }
        val selectedRadio = transceivers.firstOrNull { it.uuid == selectedUuid }
        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { menuExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedRadio?.info ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(id = R.string.wavelog_select_transponder)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                transceivers.forEach { radio ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = radio.info,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            selectedUuid = radio.uuid
                            menuExpanded = false
                        }
                    )
                }
            }
        }

        // After selecting a transponder: input area (live freq + callsign + mode)
        selectedRadio?.let { radio ->
            ExpandedLogInput(
                radio = radio,
                orbitalPos = orbitalPos,
                satelliteName = satelliteName,
                satelliteCatnum = satelliteCatnum,
                queue = queue,
                showToast = showToast,
                txBaseFrequencyHz = txBaseFrequencyHz,
                aosTimeMs = aosTimeMs,
                onLookupGrid = onLookupGrid,
                onSaved = { refreshTick++ }
            )
        }

        // Local records list (time / freq / callsign)
        Text(
            text = stringResource(id = R.string.wavelog_saved),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entries.isEmpty()) {
            Text(
                text = stringResource(id = R.string.wavelog_empty),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            // Grouped by pass session (sessionId = satName-AOS timestamp), thick line between groups
            entries.groupBy { it.sessionId.ifBlank { "un" } }.forEach { (sessionId, groupEntries) ->
                // Group title + divider
                HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (sessionId == "un") stringResource(id = R.string.wavelog_ungrouped)
                    else sessionId.substringBeforeLast('-'),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                groupEntries.forEach { entry ->
                // key() ties the row's swipe/countdown state to the QSO id. Without it
                // Compose reuses state by position, so a list reorder mid-countdown
                // (a new QSO is inserted at index 0) moves the pending deletion onto a
                // different record and deletes the wrong one.
                key(entry.id) {
                SwipeDeleteRow(
                    onDelete = {
                        queue.remove(entry.id)
                        refreshTick++
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = formatLocalTime(entry.timeUtcMs),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            text = formatFrequency(entry.freqTxHz),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(76.dp)
                        )
                        Text(
                            text = entry.call,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                } // key(entry.id)
                }
            }
        }
    }
}

/** Expanded transponder input: live freq + callsign (Enter saves locally) + mode override */
@Composable
private fun ExpandedLogInput(
    radio: SatRadio,
    orbitalPos: OrbitalPos?,
    satelliteName: String,
    satelliteCatnum: Int,
    queue: WavelogQueue,
    showToast: (String) -> Unit,
    txBaseFrequencyHz: Long? = null,
    aosTimeMs: Long = 0L,
    onLookupGrid: (String, (QrzGrid) -> Unit) -> Unit,
    onSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var callsign by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(radio.uplinkMode ?: "FM") }
    // Calls logged during this pass, so a repeat can be mentioned without being blocked: the same
    // station on a later pass is a legitimate new contact. This replaces a 300ms window that
    // swallowed what it guessed were accidental double submissions - a guess that could discard
    // a real second contact instead.
    var workedThisSession by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val savedMsg = stringResource(id = R.string.wavelog_saved)
    val gridWarnMsg = stringResource(id = R.string.log_warn_grid)
    val workedWarnMsg = stringResource(id = R.string.log_warn_worked)
    val tooShortMsg = stringResource(id = R.string.log_call_too_short)
    val illegalMsg = stringResource(id = R.string.log_call_illegal)
    val notACallMsg = stringResource(id = R.string.log_call_not_a_call)
    val tooLongMsg = stringResource(id = R.string.log_call_too_long)
    val gridSignedOutMsg = stringResource(id = R.string.log_grid_signed_out)
    val gridUnreachableMsg = stringResource(id = R.string.log_grid_unreachable)

    fun rejectionMessage(reason: CallsignEntry.Reason): String = when (reason) {
        CallsignEntry.Reason.EMPTY, CallsignEntry.Reason.TOO_SHORT -> tooShortMsg
        CallsignEntry.Reason.TOO_LONG -> tooLongMsg
        CallsignEntry.Reason.ILLEGAL_CHARACTERS -> illegalMsg
        CallsignEntry.Reason.NOT_A_CALLSIGN -> notACallMsg
    }

    fun submit() {
        // Every outcome says something. The previous `if (call.length < 3) return` discarded the
        // entry in silence, so pressing done mid-pass appeared to do nothing, and none of the
        // logging software surveyed drops a submission that way.
        val verdict = CallsignEntry.check(callsign, workedThisSession)
        if (verdict is CallsignEntry.Verdict.Rejected) {
            showToast(rejectionMessage(verdict.reason))
            return
        }
        val accepted = verdict as CallsignEntry.Verdict.Acceptable
        val call = accepted.callsign
        // Freq taken directly from the transponder bar (radio Doppler-corrected each second; value at the Enter moment)
        val tx = radio.uplinkLow ?: radio.downlinkLow ?: 0L
        val rx = radio.downlinkLow ?: radio.uplinkLow ?: 0L
        val qsoId = UUID.randomUUID().toString()
        queue.add(
            WavelogQso(
                id = qsoId,
                timeUtcMs = System.currentTimeMillis(),
                call = call,
                mode = mode.trim().ifBlank { "FM" }.uppercase(),
                freqTxHz = tx,
                freqRxHz = rx,
                satName = satelliteName,
                catnum = satelliteCatnum,
                sessionId = buildSessionId(satelliteName, aosTimeMs)
            )
        )
        callsign = ""
        workedThisSession = workedThisSession + call
        onSaved()
        showToast(
            when (accepted.warning) {
                CallsignEntry.Warning.LOOKS_LIKE_A_GRID -> gridWarnMsg
                CallsignEntry.Warning.ALREADY_WORKED -> workedWarnMsg.format(call)
                null -> savedMsg
            }
        )
        // Grid backfill. The view model owns the cookie and the request; this used to read
        // SharedPreferences through LocalContext right here, inside composition. Failures now say
        // something: an expired cookie was indistinguishable from a station with no grid filed.
        onLookupGrid(call) { outcome ->
            when (outcome) {
                is QrzGrid.Found -> {
                    queue.updateGridsquare(qsoId, outcome.locator)
                    onSaved()
                }
                // Nothing to fetch and nothing wrong: the station has no locator on file.
                QrzGrid.NotOnFile -> Unit
                QrzGrid.SignedOut -> showToast(gridSignedOutMsg)
                is QrzGrid.Unreachable -> showToast(gridUnreachableMsg)
            }
        }
    }

    // Freq display matches the transponder bar: TX row + RX row (radio from per-second Doppler recompute, live)
    val txForDisplay = radio.uplinkLow ?: radio.downlinkLow ?: 0L
    val rxForDisplay = radio.downlinkLow ?: radio.uplinkLow ?: 0L
    val upLow = radio.uplinkLow
    val upHigh = radio.uplinkHigh
    val downLow = radio.downlinkLow
    val downHigh = radio.downlinkHigh
    val isLinearRange = upLow != null && upHigh != null && upLow != upHigh
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // TX row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "TX: ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatFrequency(txForDisplay)} MHz",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (isLinearRange) {
                Text(
                    text = "  (${formatFrequency(upLow)} – ${formatFrequency(upHigh)})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // RX row (same as transponder bar RX)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "RX: ",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${formatFrequency(rxForDisplay)} MHz",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (downLow != null && downHigh != null && downLow != downHigh) {
                Text(
                    text = "  (${formatFrequency(downLow)} – ${formatFrequency(downHigh)})",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it.take(12) },
            label = { Text(stringResource(id = R.string.wavelog_call_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = mode,
            onValueChange = { mode = it.take(8) },
            label = { Text(stringResource(id = R.string.wavelog_mode_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Custom swipe-to-delete row (user-requested interaction):
 * swipe left -> yellow trash on the right; past 75% -> becomes undo key (pending delete, not immediate);
 * tap undo within the 5 s countdown to restore; otherwise auto-deleted.
 */
@Composable
internal fun SwipeDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var pending by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(5) }
    var rowWidth by remember { mutableIntStateOf(0) }
    val threshold = rowWidth * 0.75f

    // Smooth spring back / slide in (animation)
    val animatedOffset by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = if (pending) tween(200) else spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "swipeOffset"
    )

    // Countdown: auto-delete 5 s after entering pending state
    LaunchedEffect(pending) {
        if (pending) {
            countdown = 5
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
            if (pending) {
                onDelete()
                pending = false
                offsetX = 0f
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = it.width }
    ) {
        // Background layer (right-side icons: trash / undo + countdown)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(WaveLogYellow.copy(alpha = 0.25f)),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "$countdown",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = WaveLogYellow
                    )
                    Text(
                        text = stringResource(id = R.string.wavelog_delete_undo),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = WaveLogYellow,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(WaveLogYellow.copy(alpha = 0.15f))
                            .clickable {
                                pending = false
                                offsetX = 0f
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                // Trash icon (always visible while swiping, yellow)
                Text(
                    text = "🗑",
                    fontSize = 20.sp,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
        }
        // Content layer (follows finger): background sits inside the offset (moves with content)!
        // Normally covers the trash; swiping moves content+background away -> yellow trash / undo countdown revealed
        Box(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            if (pending) {
                                pending = false
                                offsetX = 0f
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceIn(-rowWidth.toFloat(), 0f)
                        },
                        onDragEnd = {
                            if (offsetX <= -threshold) {
                                pending = true
                                countdown = 5
                            } else {
                                offsetX = 0f
                            }
                        },
                        onDragCancel = { offsetX = 0f }
                    )
                }
        ) {
            content()
        }
    }
}

private fun formatLocalTime(utcMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.timeInMillis = utcMs
    return "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

/** Pass session ID: satName-AOS timestamp (UTC yyyyMMdd-HHmm); falls back to Enter time without AOS */
private fun buildSessionId(satName: String, aosTimeMs: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.timeInMillis = if (aosTimeMs > 0) aosTimeMs else System.currentTimeMillis()
    val stamp = "%04d%02d%02d-%02d%02d".format(
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
    )
    return "$satName-$stamp"
}
