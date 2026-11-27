package io.novadeck.control.ui.screens

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore

private data class SecurityPosture(
    val encrypted: Boolean,
    val rootFlags: Int,
    val selinuxEnforce: Int, // 1=Enforcing, 0=Permissive, -1=Restricted
    val adbEnabled: Boolean,
    val wirelessAdbEnabled: Boolean,
    val devEnabled: Boolean,
    val lockSecure: Boolean,
    val vpnActive: Boolean,
    val proxyConfigured: String,
    val userCaCount: Int,
    val securityPatch: String,
    val score: Int
)

private data class SocketEntry(
    val proto: String,
    val local: String,
    val remote: String,
    val state: String,
    val uid: String
)

private data class PermAppEntry(
    val label: String,
    val packageName: String
)

private data class SensitivePermReport(
    val cameraApps: List<PermAppEntry>,
    val micApps: List<PermAppEntry>,
    val locationApps: List<PermAppEntry>,
    val smsApps: List<PermAppEntry>,
    val overlayApps: List<PermAppEntry>,
    val installApps: List<PermAppEntry>
)

@Composable
fun PrivacyAuditScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var activeTab by remember { mutableIntStateOf(0) } // 0=Integrity, 1=Sockets, 2=Permissions
    var posture by remember { mutableStateOf<SecurityPosture?>(null) }
    var permReport by remember { mutableStateOf<SensitivePermReport?>(null) }
    var socketsRestricted by remember { mutableStateOf(false) }
    var sockets by remember { mutableStateOf<List<SocketEntry>>(emptyList()) }
    var selectedCategory by remember { mutableIntStateOf(0) }

    fun reloadSockets() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { NativeBridge.safeReadActiveSockets() }
            if (raw == "RESTRICTED") {
                socketsRestricted = true
                sockets = emptyList()
            } else {
                socketsRestricted = false
                sockets = raw.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val p = line.split('\t')
                        if (p.size >= 5) SocketEntry(p[0], p[1], p[2], p[3], p[4]) else null
                    }
                    .toList()
            }
        }
    }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            Pair(auditPosture(context), auditSensitivePermissions(context))
        }
        posture = result.first
        permReport = result.second
        reloadSockets()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            DeckChip(stringResource(R.string.priv_tab_integrity), activeTab == 0, { activeTab = 0 })
            DeckChip(stringResource(R.string.priv_tab_ports), activeTab == 1, { activeTab = 1 })
            DeckChip(stringResource(R.string.priv_tab_perms), activeTab == 2, { activeTab = 2 })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (activeTab) {
                0 -> {
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
                                stringResource(R.string.priv_check_enc),
                                if (p.encrypted) stringResource(R.string.priv_val_encrypted) else stringResource(R.string.priv_val_unencrypted),
                                if (p.encrypted) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                            )
                            KeyValueRow(
                                stringResource(R.string.priv_check_root),
                                if (p.rootFlags == 0) stringResource(R.string.priv_val_clean) else "${stringResource(R.string.priv_val_detected)} (0x${p.rootFlags.toString(16)})",
                                if (p.rootFlags == 0) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                            )
                            KeyValueRow(
                                "SELINUX",
                                when (p.selinuxEnforce) {
                                    1 -> "ENFORCING"
                                    0 -> "PERMISSIVE"
                                    else -> "PROTECTED"
                                },
                                when (p.selinuxEnforce) {
                                    0 -> BrutalistColors.StatusFault
                                    else -> BrutalistColors.StatusNominal
                                }
                            )
                            KeyValueRow(
                                stringResource(R.string.priv_check_adb),
                                if (p.adbEnabled) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                                if (p.adbEnabled) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                            )
                            KeyValueRow(
                                "WIRELESS ADB",
                                if (p.wirelessAdbEnabled) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                                if (p.wirelessAdbEnabled) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                            )
                            KeyValueRow(
                                stringResource(R.string.priv_check_dev),
                                if (p.devEnabled) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                                if (p.devEnabled) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                            )
                            KeyValueRow(
                                stringResource(R.string.priv_check_lock),
                                if (p.lockSecure) stringResource(R.string.priv_val_enabled) else stringResource(R.string.priv_val_disabled),
                                if (p.lockSecure) BrutalistColors.StatusNominal else BrutalistColors.StatusFault
                            )
                            KeyValueRow("VPN TUNNEL", if (p.vpnActive) "ACTIVE" else "NONE")
                            KeyValueRow("HTTP PROXY", p.proxyConfigured)
                            KeyValueRow(
                                "USER CA CERTS",
                                "${p.userCaCount}",
                                if (p.userCaCount > 0) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal
                            )
                            KeyValueRow(stringResource(R.string.priv_check_patch), p.securityPatch)
                        }
                    }
                }

                1 -> {
                    DeckPanel(
                        title = stringResource(R.string.priv_tag_ports),
                        ledColor = if (socketsRestricted) BrutalistColors.StatusCaution else BrutalistColors.StatusNominal,
                        headerAction = {
                            DeckChip(text = "↻", selected = false, onClick = { reloadSockets() })
                        }
                    ) {
                        if (socketsRestricted) {
                            Text(text = stringResource(R.string.priv_ports_restricted), style = DeckType.Body)
                        } else if (sockets.isEmpty()) {
                            Text(text = "0 active sockets listed.", style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (s in sockets) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                            .padding(7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                            Text("${s.proto} · ${s.local} -> ${s.remote}", style = DeckType.Caption, color = BrutalistColors.BoneInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("UID: ${s.uid}", style = DeckType.Caption, color = BrutalistColors.TungstenMute)
                                        }
                                        Text(
                                            text = s.state,
                                            style = DeckType.Caption,
                                            color = if (s.state == "LISTEN") BrutalistColors.SignalAccent else BrutalistColors.StatusNominal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    val rep = permReport
                    DeckPanel(
                        title = stringResource(R.string.priv_tag_perms),
                        ledColor = BrutalistColors.SignalAccent
                    ) {
                        if (rep == null) {
                            Text(text = stringResource(R.string.apps_loading), style = DeckType.Body)
                        } else {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DeckChip("${stringResource(R.string.priv_perm_camera)} (${rep.cameraApps.size})", selectedCategory == 0, { selectedCategory = 0 }, modifier = Modifier.weight(1f))
                                DeckChip("${stringResource(R.string.priv_perm_mic)} (${rep.micApps.size})", selectedCategory == 1, { selectedCategory = 1 }, modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DeckChip("${stringResource(R.string.priv_perm_loc)} (${rep.locationApps.size})", selectedCategory == 2, { selectedCategory = 2 }, modifier = Modifier.weight(1f))
                                DeckChip("${stringResource(R.string.priv_perm_sms)} (${rep.smsApps.size})", selectedCategory == 3, { selectedCategory = 3 }, modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                DeckChip("${stringResource(R.string.priv_perm_overlay)} (${rep.overlayApps.size})", selectedCategory == 4, { selectedCategory = 4 }, modifier = Modifier.weight(1f))
                                DeckChip("${stringResource(R.string.priv_perm_install)} (${rep.installApps.size})", selectedCategory == 5, { selectedCategory = 5 }, modifier = Modifier.weight(1f))
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val list = when (selectedCategory) {
                                0 -> rep.cameraApps
                                1 -> rep.micApps
                                2 -> rep.locationApps
                                3 -> rep.smsApps
                                4 -> rep.overlayApps
                                else -> rep.installApps
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
                                                Text(app.label, style = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(app.packageName, style = DeckType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
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

    val sysBuf = DoubleArray(6)
    val sysMask = NativeBridge.safeReadSystemStats(sysBuf)
    val selinux = if ((sysMask and 2) != 0) sysBuf[5].toInt() else -1

    val adbEnabled = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 0
    } catch (_: Throwable) {
        false
    }
    val wirelessAdb = try {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 0
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

    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val vpnActive = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    val proxyHost = System.getProperty("http.proxyHost")
    val proxyPort = System.getProperty("http.proxyPort")
    val proxyConfigured = if (!proxyHost.isNullOrBlank()) "$proxyHost:${proxyPort ?: ""}" else "DIRECT"

    var userCa = 0
    try {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null, null)
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            if (aliases.nextElement().startsWith("user:")) userCa++
        }
    } catch (_: Throwable) {
    }

    val patch = Build.VERSION.SECURITY_PATCH.ifBlank { "Unknown" }

    var score = 100
    if (!encrypted) score -= 25
    if (rootFlags != 0) score -= 30
    if (selinux == 0) score -= 20
    if (adbEnabled) score -= 10
    if (wirelessAdb) score -= 8
    if (devEnabled) score -= 5
    if (!lockSecure) score -= 18
    if (userCa > 0) score -= 8

    return SecurityPosture(
        encrypted = encrypted,
        rootFlags = rootFlags,
        selinuxEnforce = selinux,
        adbEnabled = adbEnabled,
        wirelessAdbEnabled = wirelessAdb,
        devEnabled = devEnabled,
        lockSecure = lockSecure,
        vpnActive = vpnActive,
        proxyConfigured = proxyConfigured,
        userCaCount = userCa,
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
    val overlay = mutableListOf<PermAppEntry>()
    val install = mutableListOf<PermAppEntry>()

    for (pi in pkgs) {
        val ai = pi.applicationInfo ?: continue
        if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue

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
        var hasOverlay = false
        var hasInstall = false

        val count = minOf(reqs.size, flags.size)
        for (i in 0 until count) {
            val granted = (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            when (reqs[i]) {
                Manifest.permission.CAMERA -> if (granted) hasCam = true
                Manifest.permission.RECORD_AUDIO -> if (granted) hasMic = true
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> if (granted) hasLoc = true
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CALL_LOG -> if (granted) hasSms = true
                Manifest.permission.SYSTEM_ALERT_WINDOW -> hasOverlay = true
                Manifest.permission.REQUEST_INSTALL_PACKAGES -> hasInstall = true
            }
        }

        val entry = PermAppEntry(label, pi.packageName)
        if (hasCam) cam.add(entry)
        if (hasMic) mic.add(entry)
        if (hasLoc) loc.add(entry)
        if (hasSms) sms.add(entry)
        if (hasOverlay) overlay.add(entry)
        if (hasInstall) install.add(entry)
    }

    return SensitivePermReport(
        cameraApps = cam.sortedBy { it.label.lowercase() },
        micApps = mic.sortedBy { it.label.lowercase() },
        locationApps = loc.sortedBy { it.label.lowercase() },
        smsApps = sms.sortedBy { it.label.lowercase() },
        overlayApps = overlay.sortedBy { it.label.lowercase() },
        installApps = install.sortedBy { it.label.lowercase() }
    )
}
