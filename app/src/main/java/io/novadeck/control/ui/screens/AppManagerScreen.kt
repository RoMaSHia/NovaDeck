package io.novadeck.control.ui.screens

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.novadeck.control.R
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val isSystem: Boolean
)

@Composable
fun AppManagerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var allApps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var showSystem by remember { mutableStateOf(false) }
    var sortBySize by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val selectedPackages = remember { mutableStateListOf<String>() }
    val uninstallQueue = remember { mutableStateListOf<String>() }
    var uninstallStep by remember { mutableIntStateOf(0) }

    val uninstallLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (uninstallQueue.isNotEmpty()) {
            uninstallStep++
        } else {
            refreshTrigger++
        }
    }

    LaunchedEffect(uninstallStep) {
        if (uninstallStep > 0 && uninstallQueue.isNotEmpty()) {
            val nextPkg = uninstallQueue.removeAt(0)
            selectedPackages.remove(nextPkg)
            uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$nextPkg")))
        }
    }

    LaunchedEffect(refreshTrigger) {
        loading = true
        allApps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
        loading = false
    }

    val filtered = remember(allApps, showSystem, sortBySize, query) {
        val q = query.trim().lowercase()
        val list = allApps.filter { app ->
            (app.isSystem == showSystem) &&
                (q.isEmpty() || app.label.lowercase().contains(q) || app.packageName.lowercase().contains(q))
        }
        if (sortBySize) list.sortedByDescending { it.apkSizeBytes }
        else list.sortedBy { it.label.lowercase() }
    }

    val totalFilteredSize = remember(filtered) { filtered.sumOf { it.apkSizeBytes } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DeckPanel(
            title = stringResource(R.string.apps_tag_controls),
            ledColor = BrutalistColors.SignalAccent,
            headerAction = {
                Text(
                    text = stringResource(R.string.apps_count_fmt, filtered.size, formatBytesCompact(totalFilteredSize)),
                    style = DeckType.Caption,
                    color = BrutalistColors.BoneInk
                )
            }
        ) {
            // Search input box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                    .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.apps_search_hint),
                        style = DeckType.DataMono,
                        color = BrutalistColors.GraphiteFaint
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = DeckType.DataMono,
                    cursorBrush = SolidColor(BrutalistColors.SignalAccent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DeckChip(
                    text = stringResource(R.string.apps_filter_user),
                    selected = !showSystem,
                    onClick = { showSystem = false; selectedPackages.clear() },
                    modifier = Modifier.weight(1f)
                )
                DeckChip(
                    text = stringResource(R.string.apps_filter_system),
                    selected = showSystem,
                    onClick = { showSystem = true; selectedPackages.clear() },
                    modifier = Modifier.weight(1f)
                )
                DeckChip(
                    text = stringResource(R.string.apps_sort_size),
                    selected = sortBySize,
                    onClick = { sortBySize = true },
                    accent = BrutalistColors.TitaniumTrace
                )
                DeckChip(
                    text = stringResource(R.string.apps_sort_name),
                    selected = !sortBySize,
                    onClick = { sortBySize = false },
                    accent = BrutalistColors.TitaniumTrace
                )
            }

            if (selectedPackages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                DeckButton(
                    text = stringResource(R.string.apps_batch_uninstall, selectedPackages.size),
                    onClick = {
                        uninstallQueue.clear()
                        uninstallQueue.addAll(selectedPackages)
                        uninstallStep++
                    },
                    danger = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (feedbackBanner != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = feedbackBanner ?: "",
                    style = DeckType.Caption,
                    color = BrutalistColors.StatusNominal
                )
            }
        }

        if (loading) {
            DeckPanel {
                Text(text = stringResource(R.string.apps_loading), style = DeckType.Body)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isSelected = selectedPackages.contains(app.packageName)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BrutalistColors.CarbonSurface, RoundedCornerShape(2.dp))
                            .border(
                                1.dp,
                                if (isSelected) BrutalistColors.SignalAccent else BrutalistColors.HairlineBorder,
                                RoundedCornerShape(2.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                if (!app.isSystem && app.packageName != context.packageName) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(
                                                if (isSelected) BrutalistColors.SignalAccent else BrutalistColors.ObsidianBg,
                                                RoundedCornerShape(2.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isSelected) BrutalistColors.SignalAccent else BrutalistColors.ActiveBorder,
                                                RoundedCornerShape(2.dp)
                                            )
                                            .clickable {
                                                if (isSelected) selectedPackages.remove(app.packageName)
                                                else selectedPackages.add(app.packageName)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Text(
                                                text = "X",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = BrutalistColors.ObsidianBg
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Column {
                                    Text(
                                        text = app.label,
                                        style = DeckType.Label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${app.packageName} · v${app.versionName}",
                                        style = DeckType.Caption,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                text = formatBytesCompact(app.apkSizeBytes),
                                style = DeckType.DataMono,
                                color = BrutalistColors.SignalAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DeckButton(
                                text = stringResource(R.string.apps_btn_backup),
                                onClick = {
                                    scope.launch {
                                        val res = withContext(Dispatchers.IO) { exportApkToDownloads(context, app) }
                                        feedbackBanner = res
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            DeckButton(
                                text = stringResource(R.string.apps_btn_cache),
                                onClick = {
                                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${app.packageName}")
                                    }
                                    context.startActivity(i)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (!app.isSystem && app.packageName != context.packageName) {
                                DeckButton(
                                    text = stringResource(R.string.apps_btn_uninstall),
                                    onClick = {
                                        uninstallLauncher.launch(
                                            Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                                        )
                                    },
                                    danger = true
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun loadInstalledApps(context: Context): List<InstalledAppEntry> {
    val pm = context.packageManager
    val pkgs = try {
        pm.getInstalledPackages(0)
    } catch (_: Throwable) {
        emptyList()
    }
    val out = ArrayList<InstalledAppEntry>(pkgs.size)
    for (pi in pkgs) {
        val ai = pi.applicationInfo ?: continue
        val srcPath = ai.sourceDir ?: continue
        val f = File(srcPath)
        var sz = if (f.exists()) f.length() else 0L
        val splits = ai.splitSourceDirs
        if (splits != null) {
            for (sp in splits) {
                val sf = File(sp)
                if (sf.exists()) sz += sf.length()
            }
        }
        val label = try {
            pm.getApplicationLabel(ai).toString()
        } catch (_: Throwable) {
            pi.packageName
        }
        val isSys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        out.add(
            InstalledAppEntry(
                label = label,
                packageName = pi.packageName,
                versionName = pi.versionName ?: "1.0",
                apkPath = srcPath,
                apkSizeBytes = sz,
                isSystem = isSys
            )
        )
    }
    return out
}

private fun exportApkToDownloads(context: Context, app: InstalledAppEntry): String {
    val safeName = app.label.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { app.packageName }
    val safeVer = app.versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val outFileName = "${safeName}_v${safeVer}.apk"
    val srcFile = File(app.apkPath)
    if (!srcFile.exists() || !srcFile.canRead()) {
        return context.getString(R.string.apps_backup_fail, "APK unreadable")
    }

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, outFileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NovaDeck_Backups")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return context.getString(R.string.apps_backup_fail, "MediaStore error")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(srcFile).use { input -> input.copyTo(out, 65536) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NovaDeck_Backups"
            )
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, outFileName)
            FileInputStream(srcFile).use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out, 65536) }
            }
        }
        context.getString(R.string.apps_backup_ok, outFileName)
    } catch (t: Throwable) {
        context.getString(R.string.apps_backup_fail, t.message ?: "I/O error")
    }
}
