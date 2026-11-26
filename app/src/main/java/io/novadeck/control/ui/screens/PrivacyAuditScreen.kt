package io.novadeck.control.ui.screens

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.NativeBridge
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.KeyValueRow
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SecurityPosture(
    val encrypted: Boolean,
    val rootFlags: Int,
    val adbEnabled: Boolean,
    val devEnabled: Boolean,
    val lockSecure: Boolean,
    val securityPatch: String,
    val score: Int
)

private data class PermAppEntry(
    val label: String,
    val packageName: String
)

private data class SensitivePermReport(
    val cameraApps: List<PermAppEntry>,
    val micApps: List<PermAppEntry>,
    val locationApps: List<PermAppEntry>,
    val smsApps: List<PermAppEntry>
)

@Composable
fun PrivacyAuditScreen() {
    val context = LocalContext.current
    val scroll = rememberScrollState()

    var posture by remember { mutableStateOf<SecurityPosture?>(null) }
    var permReport by remember { mutableStateOf<SensitivePermReport?>(null) }
    var selectedCategory by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            Pair(auditPosture(context), auditSensitivePermissions(context))
        }
        posture = result.first
        permReport = result.second
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val p = posture
        if (p != null) {
            val scoreColor = when {
                p.score >= 85 -> BrutalistColors.StatusNominal
                p.score >= 60 -> BrutalistColors.StatusCaution
                else -> BrutalistColors.StatusFault
            }
            DeckPanel(
                title = stringResource(R.string.priv_tag_posture),
                ledColor = scoreColor,
                headerAction = {
                    Box(
                        modifier = Modifier
                            .background(scoreColor.copy(alpha = 0.16f), RoundedCornerShape(2.dp))
                            .border(1.dp, scoreColor, RoundedCornerShape(2.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${stringResource(R.string.priv_score_label)}: ${p.score}%",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = scoreColor
                        )
                    }
                }
            ) {
                KeyValueRow(
                    label = stringResource(R.string.priv_check_enc),
                    value = if (p.encrypted) stringResource(R.string.priv_val_encrypted) else stringResource(R.string.priv_val_unencrypted),
                    valueColor = if (p.encrypted) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                )
                KeyValueRow(
                    label = stringResource(R.string.priv_check_root),
                    value = if (p.rootFlags == 0) stringResource(R.string.priv_val_clean) else "${stringResource(R.string.priv_val_detected)} (0x${p.rootFlags.toString(16)})",
                    valueColor = if (p.rootFlags == 0) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                )
                KeyValueRow(
                    label = stringResource(R.string.priv_check_adb),
                    value = if (p.adbEnabled) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                    valueColor = if (p.adbEnabled) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                )
                KeyValueRow(
                    label = stringResource(R.string.priv_check_dev),
                    value = if (p.devEnabled) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                    valueColor = if (p.devEnabled) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                )
                KeyValueRow(
                    label = stringResource(R.string.priv_check_lock),
                    value = if (p.lockSecure) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                    valueColor = if (p.lockSecure) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                )
                KeyValueRow(
                    label = stringResource(R.string.priv_check_patch),
                    value = p.securityPatch
                )
            }
        }

        val rep = permReport
        DeckPanel(
            title = stringResource(R.string.priv_tag_perms),
            ledColor = BrutalistColors.SignalAccent
        ) {
            if (rep == null) {
                Text(text = stringResource(R.string.apps_loading), style = DeckType.Body)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DeckChip(
                        text = "${stringResource(R.string.priv_perm_camera)} (${rep.cameraApps.size})",
                        selected = selectedCategory == 0,
                        onClick = { selectedCategory = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    DeckChip(
                        text = "${stringResource(R.string.priv_perm_mic)} (${rep.micApps.size})",
                        selected = selectedCategory == 1,
                        onClick = { selectedCategory = 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DeckChip(
                        text = "${stringResource(R.string.priv_perm_loc)} (${rep.locationApps.size})",
                        selected = selectedCategory == 2,
                        onClick = { selectedCategory = 2 },
                        modifier = Modifier.weight(1f)
                    )
                    DeckChip(
                        text = "${stringResource(R.string.priv_perm_sms)} (${rep.smsApps.size})",
                        selected = selectedCategory == 3,
                        onClick = { selectedCategory = 3 },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                val list = when (selectedCategory) {
                    0 -> rep.cameraApps
                    1 -> rep.micApps
                    2 -> rep.locationApps
                    else -> rep.smsApps
                }
                if (list.isEmpty()) {
                    Text(text = stringResource(R.string.priv_no_apps), style = DeckType.Body)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (app in list) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = app.label,
                                        style = DeckType.Label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = DeckType.Caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                DeckButton(
                                    text = stringResource(R.string.priv_btn_revoke),
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${app.packageName}")
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

private fun auditPosture(context: Context): SecurityPosture {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    val encStatus = dpm?.storageEncryptionStatus ?: DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED
    val encrypted = encStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE ||
        encStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
        encStatus == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY

    var rootFlags = NativeBridge.safeCheckRootIndicators()
    if (Build.TAGS?.contains("test-keys") == true) rootFlags = rootFlags or 8

    val adbEnabled = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
    } catch (_: Throwable) {
        false
    }
    val devEnabled = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0
    } catch (_: Throwable) {
        false
    }

    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    val lockSecure = km?.isDeviceSecure ?: false
    val patch = Build.VERSION.SECURITY_PATCH.ifBlank { "Unknown" }

    var score = 100
    if (!encrypted) score -= 25
    if (rootFlags != 0) score -= 30
    if (adbEnabled) score -= 12
    if (devEnabled) score -= 5
    if (!lockSecure) score -= 20

    return SecurityPosture(
        encrypted = encrypted,
        rootFlags = rootFlags,
        adbEnabled = adbEnabled,
        devEnabled = devEnabled,
        lockSecure = lockSecure,
        securityPatch = patch,
        score = score.coerceIn(0, 100)
    )
}

private fun auditSensitivePermissions(context: Context): SensitivePermReport {
    val pm = context.packageManager
    val pkgs = try {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    } catch (_: Throwable) {
        emptyList()
    }

    val cam = mutableListOf<PermAppEntry>()
    val mic = mutableListOf<PermAppEntry>()
    val loc = mutableListOf<PermAppEntry>()
    val sms = mutableListOf<PermAppEntry>()

    for (pi in pkgs) {
        val ai = pi.applicationInfo ?: continue
        // Focus on user-installed apps (or updated system apps) so users can manage real privacy risks
        val isUser = (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        if (!isUser) continue

        val reqs = pi.requestedPermissions ?: continue
        val flags = pi.requestedPermissionsFlags ?: continue
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            pi.packageName
        }

        var hasCam = false
        var hasMic = false
        var hasLoc = false
        var hasSms = false

        val count = minOf(reqs.size, flags.size)
        for (i in 0 until count) {
            val granted = (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            if (!granted) continue
            when (reqs[i]) {
                Manifest.permission.CAMERA -> hasCam = true
                Manifest.permission.RECORD_AUDIO -> hasMic = true
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> hasLoc = true
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CALL_LOG -> hasSms = true
            }
        }

        val entry = PermAppEntry(label, pi.packageName)
        if (hasCam) cam.add(entry)
        if (hasMic) mic.add(entry)
        if (hasLoc) loc.add(entry)
        if (hasSms) sms.add(entry)
    }

    return SensitivePermReport(
        cameraApps = cam.sortedBy { it.label.lowercase() },
        micApps = mic.sortedBy { it.label.lowercase() },
        locationApps = loc.sortedBy { it.label.lowercase() },
        smsApps = sms.sortedBy { it.label.lowercase() }
    )
}
