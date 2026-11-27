package io.novadeck.control

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.core.AppUpdater
import io.novadeck.control.core.LocaleController
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.UpdateStatus
import io.novadeck.control.ui.components.SettingsOverlay
import io.novadeck.control.ui.components.TechnicalGridBackground
import io.novadeck.control.ui.components.UpdateModalDialog
import io.novadeck.control.ui.navigation.DeckRoute
import io.novadeck.control.ui.screens.AppManagerScreen
import io.novadeck.control.ui.screens.BenchmarkScreen
import io.novadeck.control.ui.screens.HardwareTesterScreen
import io.novadeck.control.ui.screens.HomeScreen
import io.novadeck.control.ui.screens.LiveMonitorScreen
import io.novadeck.control.ui.screens.PrivacyAuditScreen
import io.novadeck.control.ui.screens.StorageCheckerScreen
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import io.novadeck.control.ui.theme.NovaDeckTheme

class MainActivity : AppCompatActivity() {

    private val appInstance: NovaDeckApp
        get() = application as NovaDeckApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NovaDeckTheme {
                NovaDeckShell(
                    telemetry = appInstance.telemetryEngine,
                    perfController = appInstance.performanceController
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appInstance.performanceController.refresh()
        appInstance.telemetryEngine.start()
    }

    override fun onStop() {
        super.onStop()
        appInstance.telemetryEngine.stop()
    }
}

@Composable
private fun NovaDeckShell(
    telemetry: TelemetryEngine,
    perfController: PerformanceTierController
) {
    var activeRoute by remember { mutableStateOf(DeckRoute.HOME) }
    var monitorInitialTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showUpdater by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrutalistColors.ObsidianBg)
    ) {
        TechnicalGridBackground(isUltraLite = perfController.isUltraLite)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TopDeckBar(
                perfController = perfController,
                onOpenUpdater = { showUpdater = true },
                onOpenSettings = { showSettings = true }
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrutalistColors.HairlineBorder)
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeRoute) {
                    DeckRoute.HOME -> HomeScreen(
                        telemetry = telemetry,
                        perfController = perfController,
                        onNavigate = { route -> activeRoute = route },
                        onOpenMonitorTab = { tab ->
                            monitorInitialTab = tab
                            activeRoute = DeckRoute.MONITOR
                        }
                    )
                    DeckRoute.MONITOR -> LiveMonitorScreen(
                        telemetry = telemetry,
                        perfController = perfController,
                        initialTab = monitorInitialTab
                    )
                    DeckRoute.STORAGE -> StorageCheckerScreen(telemetry = telemetry)
                    DeckRoute.APPS -> AppManagerScreen()
                    DeckRoute.HARDWARE -> HardwareTesterScreen(perfController = perfController)
                    DeckRoute.PRIVACY -> PrivacyAuditScreen()
                    DeckRoute.BENCHMARK -> BenchmarkScreen(
                        telemetry = telemetry,
                        perfController = perfController
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrutalistColors.HairlineBorder)
            )

            BottomDeckNav(
                activeRoute = activeRoute,
                onSelect = { activeRoute = it }
            )
        }

        if (showSettings) {
            SettingsOverlay(
                perfController = perfController,
                onOpenUpdater = { showUpdater = true },
                onDismiss = { showSettings = false }
            )
        }

        if (showUpdater) {
            UpdateModalDialog(onDismiss = { showUpdater = false })
        }
    }
}

@Composable
private fun TopDeckBar(
    perfController: PerformanceTierController,
    onOpenUpdater: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val currentLang = LocaleController.currentLanguageTag()
    val hasUpdate = AppUpdater.status == UpdateStatus.UPDATE_AVAILABLE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrutalistColors.CarbonSurface)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(BrutalistColors.SignalAccent)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "NOVADECK",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.6.sp,
                    color = BrutalistColors.BoneInk
                )
                Text(
                    text = if (perfController.isUltraLite) {
                        stringResource(R.string.tier_badge_lite)
                    } else {
                        stringResource(R.string.tier_badge_full)
                    },
                    style = DeckType.Caption,
                    color = BrutalistColors.TungstenMute
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Compact version + OTA modal trigger
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (hasUpdate) BrutalistColors.SignalAccent else BrutalistColors.ObsidianBg)
                    .border(
                        1.dp,
                        if (hasUpdate) BrutalistColors.SignalAccent else BrutalistColors.HairlineBorder,
                        RoundedCornerShape(2.dp)
                    )
                    .clickable(onClick = onOpenUpdater)
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME.removeSuffix("-debug")} ↻",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = if (hasUpdate) BrutalistColors.ObsidianBg else BrutalistColors.TungstenMute
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrutalistColors.SteelElevated)
                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                    .clickable {
                        LocaleController.setLanguage(if (currentLang == "ru") "en" else "ru")
                    }
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (currentLang == "ru") "RU" else "EN",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = BrutalistColors.BoneInk
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(BrutalistColors.SteelElevated)
                    .border(1.dp, BrutalistColors.ActiveBorder, RoundedCornerShape(2.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_settings),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = BrutalistColors.SignalAccent
                )
            }
        }
    }
}

@Composable
private fun BottomDeckNav(
    activeRoute: DeckRoute,
    onSelect: (DeckRoute) -> Unit
) {
    val navScroll = rememberScrollState()
    val items = listOf(
        DeckRoute.HOME to stringResource(R.string.nav_home),
        DeckRoute.MONITOR to stringResource(R.string.nav_monitor),
        DeckRoute.STORAGE to stringResource(R.string.nav_storage),
        DeckRoute.APPS to stringResource(R.string.nav_apps),
        DeckRoute.HARDWARE to stringResource(R.string.nav_hardware),
        DeckRoute.PRIVACY to stringResource(R.string.nav_privacy),
        DeckRoute.BENCHMARK to stringResource(R.string.nav_bench)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrutalistColors.CarbonSurface)
            .horizontalScroll(navScroll)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { idx, (route, label) ->
            val selected = route == activeRoute
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) BrutalistColors.SteelElevated else BrutalistColors.ObsidianBg)
                    .border(
                        1.dp,
                        if (selected) BrutalistColors.SignalAccent else BrutalistColors.HairlineBorder,
                        RoundedCornerShape(2.dp)
                    )
                    .clickable { onSelect(route) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "0$idx",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = if (selected) BrutalistColors.SignalAccent else BrutalistColors.GraphiteFaint
                    )
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        color = if (selected) BrutalistColors.BoneInk else BrutalistColors.TungstenMute
                    )
                }
            }
        }
    }
}
