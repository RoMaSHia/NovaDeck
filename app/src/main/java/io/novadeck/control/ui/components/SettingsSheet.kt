package io.novadeck.control.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.novadeck.control.BuildConfig
import io.novadeck.control.R
import io.novadeck.control.core.AppUpdater
import io.novadeck.control.core.LocaleController
import io.novadeck.control.core.PerformanceModePreference
import io.novadeck.control.core.PerformanceTierController
import io.novadeck.control.core.UpdateStatus
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType

@Composable
fun SettingsOverlay(
    perfController: PerformanceTierController,
    onDismiss: () -> Unit
) {
    val currentLang = LocaleController.currentLanguageTag()
    val currentPref = perfController.preference

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrutalistColors.ObsidianBg.copy(alpha = 0.86f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        DeckPanel(
            title = stringResource(R.string.settings_title),
            ledColor = BrutalistColors.SignalAccent,
            borderColor = BrutalistColors.ActiveBorder,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            headerAction = {
                DeckChip(
                    text = stringResource(R.string.action_close),
                    selected = false,
                    onClick = onDismiss
                )
            }
        ) {
            Text(
                text = stringResource(R.string.settings_lang_label),
                style = DeckType.IndexTag
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DeckChip(
                    text = stringResource(R.string.settings_lang_en),
                    selected = currentLang == "en",
                    onClick = { LocaleController.setLanguage("en") },
                    modifier = Modifier.weight(1f)
                )
                DeckChip(
                    text = stringResource(R.string.settings_lang_ru),
                    selected = currentLang == "ru",
                    onClick = { LocaleController.setLanguage("ru") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.settings_perf_label),
                style = DeckType.IndexTag
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DeckChip(
                    text = stringResource(R.string.settings_perf_auto),
                    selected = currentPref == PerformanceModePreference.AUTO,
                    onClick = { perfController.setModePreference(PerformanceModePreference.AUTO) },
                    modifier = Modifier.fillMaxWidth()
                )
                DeckChip(
                    text = stringResource(R.string.settings_perf_full),
                    selected = currentPref == PerformanceModePreference.DECK_FULL,
                    onClick = { perfController.setModePreference(PerformanceModePreference.DECK_FULL) },
                    modifier = Modifier.fillMaxWidth()
                )
                DeckChip(
                    text = stringResource(R.string.settings_perf_lite),
                    selected = currentPref == PerformanceModePreference.ULTRA_LITE,
                    onClick = { perfController.setModePreference(PerformanceModePreference.ULTRA_LITE) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.settings_perf_desc),
                style = DeckType.Body
            )

            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                    .padding(8.dp)
            ) {
                KeyValueRow(
                    label = "RAM DETECTED",
                    value = formatBytesCompact(perfController.totalRamBytes)
                )
                KeyValueRow(
                    label = "LOW-RAM / POWER SAVE",
                    value = "${perfController.isLowRamHardware} / ${perfController.isPowerSaveActive}"
                )
                KeyValueRow(
                    label = "ACTIVE TIER",
                    value = perfController.effectiveTier.name,
                    valueColor = BrutalistColors.SignalAccent
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            OtaUpdaterControls()

            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrutalistColors.SteelElevated, RoundedCornerShape(2.dp))
                    .border(1.dp, BrutalistColors.StatusNominal.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.settings_offline_badge),
                    style = DeckType.Caption,
                    color = BrutalistColors.StatusNominal
                )
            }
        }
    }
}

@Composable
fun OtaUpdaterControls() {
    val context = LocalContext.current
    val status = AppUpdater.status
    val progress = AppUpdater.downloadProgress

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.update_tag),
                style = DeckType.IndexTag
            )
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = DeckType.Caption,
                color = BrutalistColors.SignalAccent
            )
        }

        Text(
            text = stringResource(R.string.update_desc),
            style = DeckType.Caption,
            color = BrutalistColors.TungstenMute
        )

        when (status) {
            UpdateStatus.IDLE -> Unit
            UpdateStatus.CHECKING -> Text(
                text = stringResource(R.string.update_status_checking),
                style = DeckType.DataMono,
                color = BrutalistColors.TitaniumTrace
            )
            UpdateStatus.UP_TO_DATE -> Text(
                text = stringResource(R.string.update_status_latest, AppUpdater.remoteVersionName),
                style = DeckType.DataMono,
                color = BrutalistColors.StatusNominal
            )
            UpdateStatus.UPDATE_AVAILABLE -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.update_status_avail, AppUpdater.remoteVersionName),
                    style = DeckType.DataMono,
                    color = BrutalistColors.SignalAccent
                )
                if (AppUpdater.changelog.isNotBlank()) {
                    Text(
                        text = AppUpdater.changelog,
                        style = DeckType.Caption,
                        color = BrutalistColors.BoneInk
                    )
                }
            }
            UpdateStatus.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.update_status_downloading, progress),
                    style = DeckType.DataMono,
                    color = BrutalistColors.SignalAccent
                )
                SegmentedBarMeter(
                    fraction = progress / 100f,
                    segments = 20,
                    height = 8.dp
                )
            }
            UpdateStatus.PERMISSION_REQUIRED -> Text(
                text = stringResource(R.string.update_status_perm),
                style = DeckType.DataMono,
                color = BrutalistColors.StatusCaution
            )
            UpdateStatus.READY_TO_INSTALL -> Text(
                text = stringResource(R.string.update_status_ready),
                style = DeckType.DataMono,
                color = BrutalistColors.StatusNominal
            )
            UpdateStatus.ERROR -> Text(
                text = AppUpdater.errorMessage,
                style = DeckType.Caption,
                color = BrutalistColors.StatusFault
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DeckButton(
                text = stringResource(R.string.update_btn_check),
                onClick = { AppUpdater.checkForUpdate(context) },
                enabled = status != UpdateStatus.CHECKING && status != UpdateStatus.DOWNLOADING,
                modifier = Modifier.weight(1f)
            )
            when (status) {
                UpdateStatus.PERMISSION_REQUIRED,
                UpdateStatus.READY_TO_INSTALL -> {
                    DeckButton(
                        text = stringResource(R.string.update_btn_install_now),
                        onClick = { AppUpdater.installApkInPlace(context) },
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                UpdateStatus.UPDATE_AVAILABLE -> {
                    DeckButton(
                        text = stringResource(R.string.update_btn_download),
                        onClick = { AppUpdater.downloadAndInstall(context) },
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                else -> {
                    DeckButton(
                        text = stringResource(R.string.update_btn_reinstall),
                        onClick = { AppUpdater.downloadAndInstall(context) },
                        primary = status == UpdateStatus.UP_TO_DATE,
                        enabled = status != UpdateStatus.DOWNLOADING,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
