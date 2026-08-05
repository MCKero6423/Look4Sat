/*
 * CwDecodeScreen.kt - Look4Sat CW decode page (Compose shell).
 *
 * Ported from Morse Expert 1.15 com.ve3nea.morse_expert.MainActivity (converted to a plain controller class):
 * - AndroidView embeds the original activity_main.xml (ConstraintLayout root, with decodedTextView/scaleView/
 *   statusTextView/verticalLayout/waterfallView);
 * - Lifecycle onCreate/onResume/onPause/onDestroy fully delegated to the controller, same order as the original Activity
 *   (onCreate must run before onResume; onDispose runs onPause then onDestroy);
 * - Mic permission (RECORD_AUDIO) is managed on the Compose side; onPermissionGranted() (= v()) is called once granted,
 *   an initialized flag guarantees it runs only once (v() news an a() which overwrites f11036E);
 * - The original options_menu Clear/Pause/Save/Record/Settings became a top button row (icons from the module's original drawables).
 *
 * Note: setImmersive(window, false) inside the controller's onCreate restores the host window to
 * decorFitsSystemWindows(true) (ported original behavior; affects the whole host Activity).
 */
package com.rtbishop.look4sat.feature.cw

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.widget.ConstraintLayout
import com.ve3nea.morse_expert.MainActivity

@Composable
fun CwDecodeScreen(navigateUp: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = remember(context) {
        context as? Activity ?: error("CwDecodeScreen must be hosted in an Activity")
    }
    // Ported controller (plain class, not an Activity); new instance per page entry
    val controller = remember { MainActivity() }
    // Inflate the original layout early so AndroidView and the controller's onCreate share one root view
    val rootView = remember(context) {
        LayoutInflater.from(context).inflate(R.layout.activity_main, null) as ConstraintLayout
    }

    var permissionGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    // v() news an a() which overwrites f11036E; must run exactly once
    var initialized by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Lifecycle: onCreate before onResume; onDispose runs onPause then onDestroy
    DisposableEffect(Unit) {
        controller.onCreate(activity, rootView)
        controller.onResume()
        onDispose {
            controller.onPause()
            controller.onDestroy()
        }
    }

    // Start the decode core after permission granted; both "already granted on entry" and "granted via dialog" go here
    LaunchedEffect(permissionGranted) {
        if (permissionGranted && !initialized) {
            controller.onPermissionGranted() // = v(): 创建音频采集 + 解码核心
            // v() only creates the core; the page is already resumed, so run onResume again to start recording immediately
            // (AudioRecord is freshly created so startRecording won't double-start; GLSurfaceView.onResume is idempotent)
            controller.onResume()
            initialized = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        permanentlyDenied = !granted &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    }

    // Original tap_back_again_to_close: single Back shows a hint; press again within 2 s to exit
    BackHandler {
        if (!controller.handleBackPress()) navigateUp()
    }

    if (showSettings) {
        CwSettingsDialog(controller = controller, onDismiss = { showSettings = false })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // Original options_menu: Pause/Clear/Save (original icons) + Record/Settings (text buttons)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = { controller.togglePause() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_pause_24),
                    contentDescription = stringResource(R.string.pause)
                )
            }
            IconButton(onClick = { controller.clearDecoded() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_delete_24),
                    contentDescription = stringResource(R.string.clear)
                )
            }
            IconButton(onClick = { controller.saveText() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_save_24),
                    contentDescription = stringResource(R.string.save_text)
                )
            }
            // recordSignals needs the decode core (f11037F); disabled until initialized
            TextButton(
                onClick = { if (initialized) controller.recordSignals() },
                enabled = initialized
            ) {
                Text(stringResource(R.string.record_signals))
            }
            TextButton(onClick = { showSettings = true }) {
                Text(stringResource(R.string.settings))
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { rootView },
                modifier = Modifier.fillMaxSize()
            )
            if (!permissionGranted) {
                // Mic permission not granted: overlay + hint text + request button
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.cw_mic_permission),
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }) {
                            Text(stringResource(R.string.cw_grant_permission))
                        }
                        if (permanentlyDenied) {
                            // "Never ask again" checked: guide user to system settings
                            TextButton(onClick = {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${activity.packageName}")
                                    )
                                )
                            }) {
                                Text(stringResource(R.string.cw_open_settings))
                            }
                        }
                    }
                }
            }
        }
    }
}
