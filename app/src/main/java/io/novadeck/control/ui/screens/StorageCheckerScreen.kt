package io.novadeck.control.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.novadeck.control.R
import io.novadeck.control.core.NativeBridge
import io.novadeck.control.core.TelemetryEngine
import io.novadeck.control.core.formatBytesCompact
import io.novadeck.control.ui.components.DeckButton
import io.novadeck.control.ui.components.DeckChip
import io.novadeck.control.ui.components.DeckPanel
import io.novadeck.control.ui.components.SegmentedBarMeter
import io.novadeck.control.ui.theme.BrutalistColors
import io.novadeck.control.ui.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CategoryStat(val index: Int, val count: Long, val bytes: Long)
private data class HeavyFileItem(val size: Long, val name: String, val path: String)
private data class DuplicateGroupItem(val fileSize: Long, val paths: List<String>)
private data class StorageScanReport(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val elapsedMs: Long,
    val categories: List<CategoryStat>,
    val heavyFiles: List<HeavyFileItem>,
    val duplicates: List<DuplicateGroupItem>,
    val tempPaths: List<String>
)

@Composable
fun StorageCheckerScreen(telemetry: TelemetryEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var scanning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<StorageScanReport?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) } // 0: Heavy, 1: Dups, 2: Temp

    val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else true

    @Suppress("DEPRECATION")
    fun startScan() {
        if (scanning) return
        scanning = true
        statusBanner = null
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"
                val raw = NativeBridge.safeScanStorage(root, 4)
                val baseReport = parseNativeReport(raw)
                augmentWithMediaStoreAndCache(context, baseReport)
            }
            report = parsed
            scanning = false
        }
    }

    fun cleanResidualTemp(paths: List<String>) {
        if (paths.isEmpty()) return
        scope.launch {
            val freed = withContext(Dispatchers.IO) {
                NativeBridge.safeCleanTempFiles(paths.toTypedArray())
            }
            statusBanner = context.getString(R.string.stor_cleaned_msg, formatBytesCompact(freed))
            startScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 01 // PARTITION & C++ ENGINE
        DeckPanel(
            title = stringResource(R.string.stor_tag_overview),
            ledColor = BrutalistColors.SignalAccent
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${telemetry.storageUsedPct.toInt()}% USED",
                    style = DeckType.MetricMono,
                    color = BrutalistColors.SignalAccent
                )
                Text(
                    text = "${formatBytesCompact(telemetry.storageFreeBytes)} FREE / ${formatBytesCompact(telemetry.storageTotalBytes)}",
                    style = DeckType.DataMono
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            SegmentedBarMeter(fraction = telemetry.storageUsedPct / 100f, segments = 24, height = 10.dp)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DeckButton(
                    text = if (scanning) stringResource(R.string.stor_btn_scanning) else stringResource(R.string.stor_btn_scan),
                    onClick = { startScan() },
                    primary = true,
                    enabled = !scanning,
                    modifier = Modifier.weight(1f)
                )
                val tempPaths = report?.tempPaths ?: emptyList()
                if (tempPaths.isNotEmpty()) {
                    DeckButton(
                        text = stringResource(R.string.stor_btn_clean),
                        onClick = { cleanResidualTemp(tempPaths) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = stringResource(R.string.stor_perm_hint), style = DeckType.Caption)
                Spacer(modifier = Modifier.height(6.dp))
                DeckButton(
                    text = stringResource(R.string.stor_btn_grant_full),
                    onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Throwable) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (statusBanner != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusBanner ?: "",
                    style = DeckType.DataMono,
                    color = BrutalistColors.StatusNominal
                )
            }
        }

        val currentReport = report
        if (currentReport == null) {
            DeckPanel(title = stringResource(R.string.stor_tag_cats)) {
                Text(text = stringResource(R.string.stor_empty_scan), style = DeckType.Body)
            }
        } else {
            // 02 // CATEGORY BREAKDOWN
            DeckPanel(
                title = stringResource(R.string.stor_tag_cats),
                ledColor = BrutalistColors.TitaniumTrace,
                headerAction = {
                    Text(
                        text = stringResource(
                            R.string.stor_scan_stats,
                            currentReport.files,
                            currentReport.dirs,
                            currentReport.elapsedMs
                        ),
                        style = DeckType.Caption,
                        color = BrutalistColors.BoneInk
                    )
                }
            ) {
                val maxCatBytes = currentReport.categories.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                val labels = listOf(
                    stringResource(R.string.stor_cat_photo),
                    stringResource(R.string.stor_cat_video),
                    stringResource(R.string.stor_cat_audio),
                    stringResource(R.string.stor_cat_doc),
                    stringResource(R.string.stor_cat_apk),
                    stringResource(R.string.stor_cat_cache),
                    stringResource(R.string.stor_cat_other)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (cat in currentReport.categories) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${labels.getOrElse(cat.index) { "OTHER" }} (${cat.count})",
                                    style = DeckType.Caption,
                                    color = BrutalistColors.BoneInk
                                )
                                Text(
                                    text = formatBytesCompact(cat.bytes),
                                    style = DeckType.DataMono,
                                    color = if (cat.index == 5) BrutalistColors.SignalAccent else BrutalistColors.TitaniumTrace
                                )
                            }
                            SegmentedBarMeter(
                                fraction = cat.bytes.toFloat() / maxCatBytes.toFloat(),
                                segments = 20,
                                height = 6.dp,
                                activeColor = if (cat.index == 5) BrutalistColors.SignalAccent else BrutalistColors.TitaniumTrace
                            )
                        }
                    }
                }
            }

            // 03 // HEAVY FILES & DUPLICATES
            DeckPanel(
                title = stringResource(R.string.stor_tag_details),
                ledColor = BrutalistColors.SignalAccent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DeckChip(
                        text = stringResource(R.string.stor_tab_heavy),
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    DeckChip(
                        text = "${stringResource(R.string.stor_tab_dups)} (${currentReport.duplicates.size})",
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                    DeckChip(
                        text = "${stringResource(R.string.stor_tab_temp)} (${currentReport.tempPaths.size})",
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                when (activeTab) {
                    0 -> {
                        if (currentReport.heavyFiles.isEmpty()) {
                            Text(text = stringResource(R.string.stor_empty_scan), style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (item in currentReport.heavyFiles) {
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
                                                text = item.name,
                                                style = DeckType.DataMono,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.path,
                                                style = DeckType.Caption,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = formatBytesCompact(item.size),
                                            style = DeckType.DataMono,
                                            color = BrutalistColors.SignalAccent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        if (currentReport.duplicates.isEmpty()) {
                            Text(text = stringResource(R.string.stor_no_dups), style = DeckType.Body)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (dup in currentReport.duplicates) {
                                    val wasted = dup.fileSize * (dup.paths.size - 1).coerceAtLeast(0)
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = stringResource(R.string.stor_dup_group, dup.paths.size, formatBytesCompact(wasted)),
                                                style = DeckType.Caption,
                                                color = BrutalistColors.SignalAccent
                                            )
                                            Text(
                                                text = formatBytesCompact(dup.fileSize),
                                                style = DeckType.Caption
                                            )
                                        }
                                        for (p in dup.paths) {
                                            Text(
                                                text = p,
                                                style = DeckType.Caption,
                                                color = BrutalistColors.TungstenMute,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (dup.paths.size > 1) {
                                            DeckButton(
                                                text = stringResource(R.string.stor_btn_clean),
                                                onClick = { cleanResidualTemp(dup.paths.drop(1)) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        if (currentReport.tempPaths.isEmpty()) {
                            Text(text = "0 residual cache files queued.", style = DeckType.Body)
                        } else {
                            DeckButton(
                                text = "${stringResource(R.string.stor_btn_clean)} (${currentReport.tempPaths.size})",
                                onClick = { cleanResidualTemp(currentReport.tempPaths) },
                                primary = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (tp in currentReport.tempPaths.take(40)) {
                                    Text(
                                        text = tp,
                                        style = DeckType.Caption,
                                        color = BrutalistColors.TungstenMute,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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

private fun parseNativeReport(raw: String): StorageScanReport {
    var files = 0L
    var dirs = 0L
    var bytes = 0L
    var elapsedMs = 0L
    val cats = MutableList(7) { CategoryStat(it, 0L, 0L) }
    val heavy = mutableListOf<HeavyFileItem>()
    val dups = mutableListOf<DuplicateGroupItem>()
    val temps = mutableListOf<String>()

    for (line in raw.lineSequence()) {
        if (line.isBlank()) continue
        val parts = line.split('\t')
        when (parts.firstOrNull()) {
            "SUM" -> if (parts.size >= 6) {
                files = parts[1].toLongOrNull() ?: 0L
                dirs = parts[2].toLongOrNull() ?: 0L
                bytes = parts[3].toLongOrNull() ?: 0L
                elapsedMs = parts[5].toLongOrNull() ?: 0L
            }
            "CAT" -> if (parts.size >= 4) {
                val idx = parts[1].toIntOrNull() ?: continue
                if (idx in 0..6) {
                    cats[idx] = CategoryStat(
                        index = idx,
                        count = parts[2].toLongOrNull() ?: 0L,
                        bytes = parts[3].toLongOrNull() ?: 0L
                    )
                }
            }
            "BIG" -> if (parts.size >= 4) {
                val sz = parts[1].toLongOrNull() ?: 0L
                val name = parts[2]
                val path = parts[3]
                heavy.add(HeavyFileItem(sz, name, path))
                val lower = path.lowercase()
                if (lower.endsWith(".tmp") || lower.endsWith(".log") || lower.endsWith(".cache") ||
                    lower.endsWith(".bak") || lower.contains("/cache/") || lower.contains("/.thumbnails/")
                ) {
                    temps.add(path)
                }
            }
            "DUP" -> if (parts.size >= 4) {
                val sz = parts[1].toLongOrNull() ?: 0L
                val paths = parts.drop(3)
                if (paths.size >= 2) dups.add(DuplicateGroupItem(sz, paths))
            }
        }
    }

    return StorageScanReport(files, dirs, bytes, elapsedMs, cats, heavy, dups, temps)
}

@Suppress("DEPRECATION")
private fun augmentWithMediaStoreAndCache(
    context: android.content.Context,
    base: StorageScanReport
): StorageScanReport {
    val temps = base.tempPaths.toMutableList()
    // Also discover residual cache files inside app cache / external cache
    val cacheDirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
    var appCacheBytes = 0L
    var appCacheCount = 0L
    for (dir in cacheDirs) {
        dir.walkTopDown().maxDepth(4).forEach { f ->
            if (f.isFile && !f.name.startsWith(".novadeck_io_bench")) {
                appCacheBytes += f.length()
                appCacheCount++
                temps.add(f.absolutePath)
            }
        }
    }

    // If Scoped Storage blocked external POSIX traversal (files == 0), query MediaStore safely
    if (base.files > 0L) {
        val updatedCats = base.categories.toMutableList()
        if (appCacheCount > 0L) {
            val prev = updatedCats[5]
            updatedCats[5] = prev.copy(count = prev.count + appCacheCount, bytes = prev.bytes + appCacheBytes)
        }
        return base.copy(categories = updatedCats, tempPaths = temps.distinct())
    }

    val counts = LongArray(7)
    val sizes = LongArray(7)
    val heavy = base.heavyFiles.toMutableList()
    try {
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DATA
        )
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            null,
            null,
            "${MediaStore.Files.FileColumns.SIZE} DESC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val typeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            while (cursor.moveToNext()) {
                val sz = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                if (sz <= 0L) continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "file" else "file"
                val mtype = if (typeIdx >= 0) cursor.getInt(typeIdx) else 0
                val path = if (dataIdx >= 0) cursor.getString(dataIdx) ?: name else name
                val lower = name.lowercase()
                val cat = when {
                    mtype == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> 0
                    mtype == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> 1
                    mtype == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> 2
                    lower.endsWith(".apk") -> 4
                    lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") -> 3
                    lower.endsWith(".tmp") || lower.endsWith(".log") || lower.endsWith(".cache") -> 5
                    else -> 6
                }
                counts[cat]++
                sizes[cat] += sz
                if (heavy.size < 25) heavy.add(HeavyFileItem(sz, name, path))
            }
        }
    } catch (_: Throwable) {
        // Safe ignore if storage permission not granted
    }
    counts[5] += appCacheCount
    sizes[5] += appCacheBytes

    val mergedCats = (0..6).map { CategoryStat(it, counts[it], sizes[it]) }
    return base.copy(
        files = counts.sum(),
        bytes = sizes.sum(),
        categories = mergedCats,
        heavyFiles = heavy,
        tempPaths = temps.distinct()
    )
}
