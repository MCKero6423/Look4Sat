/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.domain.repository.MutualPassData
import com.rtbishop.look4sat.core.domain.navigation.MenuLayout
import com.rtbishop.look4sat.core.presentation.DeeplinkResolver
import com.rtbishop.look4sat.core.presentation.ElevationThresholds
import com.rtbishop.look4sat.core.presentation.LocalElevationThresholds
import com.rtbishop.look4sat.core.presentation.RadarDestination
import com.rtbishop.look4sat.core.presentation.Screen
import com.rtbishop.look4sat.core.presentation.hasEnoughHeight
import com.rtbishop.look4sat.core.presentation.hasEnoughWidth
import com.rtbishop.look4sat.feature.cw.CwDecodeScreen
import com.rtbishop.look4sat.feature.map.MapDestination
import com.rtbishop.look4sat.feature.mutual.MutualScreen
import com.rtbishop.look4sat.feature.mutual.MutualViewModel
import com.rtbishop.look4sat.feature.passes.PassesDestination
import com.rtbishop.look4sat.feature.radar.RadarDestination
import com.rtbishop.look4sat.feature.radar.WavelogLogScreen
import com.rtbishop.look4sat.feature.roaming.RoamingScreen
import com.rtbishop.look4sat.feature.satellites.SatellitesDestination
import com.rtbishop.look4sat.feature.settings.SettingsDestination
import com.rtbishop.look4sat.feature.status.SatStatusDestination

@Composable
fun NavRoot(deeplink: String? = null) {
    val rootBackStack = rememberNavBackStack(Screen.Passes)
    val deeplinkResolver = DeeplinkResolver()
    LaunchedEffect(deeplink) {
        deeplink?.let { rootBackStack.add(deeplinkResolver.resolve(it)) }
    }
    val navigateBack: () -> Unit = { rootBackStack.removeLastOrNull() }
    val navigateToRadar: () -> Unit = { rootBackStack.add(RadarDestination) }
    // Incoming screen slides in from the right, outgoing drifts left at 1/3 speed (API35+ style)
    val pushTransition = slideInHorizontally(tween(300)) { it } togetherWith
        slideOutHorizontally(tween(300)) { -it / 3 }
    // Reverse: outgoing slides out to the right, incoming drifts in from the left
    val popTransition = slideInHorizontally(tween(300)) { -it / 3 } togetherWith
        slideOutHorizontally(tween(300)) { it }
    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = rootBackStack,
        onBack = navigateBack,
        transitionSpec = { pushTransition },
        popTransitionSpec = { popTransition },
        predictivePopTransitionSpec = { popTransition },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<Screen.Passes> { MainScreen() }
            entry<RadarDestination> {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RadarDestination(navigateUp = navigateBack)
                }
            }
        }
    )
}

@Composable
fun MainScreen() {
    val backStack = rememberNavBackStack(Screen.Passes)
    val currentKey = backStack.lastOrNull()
    val navigateBack: () -> Unit = { backStack.removeLastOrNull() }
    val fadeTransition = fadeIn(animationSpec = tween(350)) togetherWith fadeOut(animationSpec = tween(350))
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val trackingState by container.radioTrackingService.state.collectAsStateWithLifecycle()
    val otherSettings by container.settingsRepo.otherSettings.collectAsStateWithLifecycle()
    // Menu layout is resolved in core:domain so the bar and the settings editor
    // cannot disagree, and so Settings can never be pushed out of both menus.
    val allNavItems = listOf(
        Screen.Satellites, Screen.Passes, Screen.Radar, Screen.Mutual, Screen.Roaming,
        Screen.CwDecode, Screen.WavelogLog, Screen.AmSat, Screen.Map, Screen.Settings
    )
    val menuLayout = remember(
        otherSettings.screenOrder, otherSettings.subMenuOrder, otherSettings.hiddenScreens
    ) {
        MenuLayout.resolve(
            allScreenIds = allNavItems.map { it.screenId },
            screenOrder = otherSettings.screenOrder,
            subMenuOrder = otherSettings.subMenuOrder,
            hiddenScreenIds = otherSettings.hiddenScreens
        )
    }
    val mainNavItems = remember(menuLayout) {
        menuLayout.mainIds.mapNotNull { id -> allNavItems.find { it.screenId == id } }
    }
    val moreNavItems = remember(menuLayout) {
        menuLayout.moreIds.mapNotNull { id -> allNavItems.find { it.screenId == id } }
    }
    var moreExpanded by remember { mutableStateOf(false) }
    // Intercept Back while the More menu is open: close the menu first
    BackHandler(enabled = moreExpanded) { moreExpanded = false }
    // Activity-scoped so the mutual query results survive navigation to Radar and back
    val mutualViewModel: MutualViewModel = viewModel(
        viewModelStoreOwner = context as ViewModelStoreOwner,
        factory = MutualViewModel.factory(container)
    )

    CompositionLocalProvider(
        LocalElevationThresholds provides ElevationThresholds(
            low = otherSettings.lowElevation,
            high = otherSettings.highElevation
        )
    ) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                mainNavItems.forEach { screen ->
                    // Screen subclasses are data objects, so identity is enough and
                    // newly added pages highlight without touching this call site.
                    val isSelected = currentKey == screen
                    item(
                        icon = {
                            Icon(
                                painter = painterResource(screen.iconResId),
                                contentDescription = stringResource(screen.titleResId)
                            )
                        },
                        label = { Text(stringResource(screen.titleResId)) },
                        selected = isSelected,
                        onClick = {
                            if (isSelected) return@item
                            moreExpanded = false
                            while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                            if (screen !is Screen.Passes) backStack.add(screen)
                        }
                    )
                }
                // More-menu button (fixed slot 6; shown only when the sub-menu is non-empty)
                if (moreNavItems.isNotEmpty()) {
                    item(
                        icon = {
                            Icon(
                                painterResource(com.rtbishop.look4sat.R.drawable.ic_more),
                                stringResource(com.rtbishop.look4sat.core.presentation.R.string.nav_more)
                            )
                        },
                        label = { Text(stringResource(com.rtbishop.look4sat.core.presentation.R.string.nav_more)) },
                        selected = moreExpanded,
                        onClick = { moreExpanded = !moreExpanded }
                    )
                }
            },
            navigationSuiteColors = NavigationSuiteDefaults.colors(
                navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            layoutType = when {
                !hasEnoughHeight() && hasEnoughWidth() -> NavigationSuiteType.NavigationRail
                !hasEnoughWidth() -> NavigationSuiteType.ShortNavigationBarCompact
                else -> NavigationSuiteType.ShortNavigationBarMedium
            }
        ) {
            Box {
                Column(modifier = Modifier.fillMaxSize()) {
                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onBack = navigateBack,
                        transitionSpec = { fadeTransition },
                        popTransitionSpec = { fadeTransition },
                        predictivePopTransitionSpec = { fadeTransition },
                        entryDecorators = listOf(
                            // Required for saving Compose state per entry
                            rememberSaveableStateHolderNavEntryDecorator(),
                            // Required for ViewModel scoping per entry
                            rememberViewModelStoreNavEntryDecorator()
                        ),
                        entryProvider = entryProvider {
                            entry<Screen.Satellites> {
                                SatellitesDestination(navigateUp = navigateBack)
                            }
                            entry<Screen.Passes> {
                                PassesDestination { catNum, aosTime ->
                                    container.setMutualPassData(MutualPassData())
                                    container.satelliteRepo.selectPass(catNum, aosTime)
                                    backStack.add(Screen.Radar)
                                }
                            }
                            entry<Screen.Radar> {
                                RadarDestination(navigateUp = navigateBack)
                            }
                            entry<Screen.Map> {
                                MapDestination()
                            }
                            entry<Screen.Mutual> {
                                MutualScreen(
                                    viewModel = mutualViewModel,
                                    navigateUp = navigateBack,
                                    navigateToRadar = { catNum, aosTime, pass ->
                                        container.setMutualPassData(pass ?: MutualPassData())
                                        container.satelliteRepo.selectPass(catNum, aosTime)
                                        backStack.add(Screen.Radar)
                                    }
                                )
                            }
                            entry<Screen.Roaming> {
                                RoamingScreen()
                            }
                            entry<Screen.CwDecode> {
                                CwDecodeScreen()
                            }
                            entry<Screen.AmSat> {
                                SatStatusDestination()
                            }
                            entry<Screen.WavelogLog> {
                                WavelogLogScreen(queue = container.wavelogQueue)
                            }
                            entry<Screen.Settings> {
                                SettingsDestination()
                            }
                        }
                    )
                    // Radio tracking status banner
                    if (trackingState.isActive) {
                        val infiniteTransition = rememberInfiniteTransition(label = "trackingPulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 1f, targetValue = 0.4f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ), label = "pulseAlpha"
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable {
                                    val pass = trackingState.currentPass
                                    if (pass != null) {
                                        container.setMutualPassData(MutualPassData())
                                        container.satelliteRepo.selectPass(pass.catNum, pass.aosTime)
                                        backStack.add(Screen.Radar)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50).copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(com.rtbishop.look4sat.core.presentation.R.string.tracking_status, trackingState.currentPass?.name ?: ""),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            val txOk = if (trackingState.txConnected) "TX" else ""
                            val rxOk = if (trackingState.rxConnected) "RX" else ""
                            Text(
                                text = listOf(txOk, rxOk).filter { it.isNotBlank() }.joinToString("/"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                // More-menu popup panel (slim strip anchored to the bottom-right corner)
                AnimatedVisibility(
                    visible = moreExpanded,
                    modifier = Modifier.fillMaxSize(),
                    enter = scaleIn(
                        animationSpec = tween(150),
                        transformOrigin = TransformOrigin(1f, 1f)
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = scaleOut(
                        animationSpec = tween(120),
                        transformOrigin = TransformOrigin(1f, 1f)
                    ) + fadeOut(animationSpec = tween(120))
                ) {
                    MoreMenuPopup(
                        items = moreNavItems,
                        currentKey = currentKey,
                        onDismiss = { moreExpanded = false },
                        onSelect = { screen ->
                            moreExpanded = false
                            while (backStack.size > 1) backStack.removeAt(backStack.size - 1)
                            if (screen !is Screen.Passes) backStack.add(screen)
                        }
                    )
                }
            }
        }
    }
}
