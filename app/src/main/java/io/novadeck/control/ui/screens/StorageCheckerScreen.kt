package io.novadeck.control.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private data class FolderStat(val name: String, val bytes: Long, val files: Long)
private data class HeavyFileItem(val size: Long, val name: String, val path: String)
private data class DuplicateGroupItem(val fileSize: Long, val paths: List<String>)
private data class StorageScanReport(
    val files: Long,
    val dirs: Long,
    val bytes: Long,
    val elapsedMs: Long,
    val categories: List<CategoryStat>,
    val folders: List<FolderStat>,
    val heavyFiles: List<HeavyFileItem>,
    val duplicates: List<DuplicateGroupItem>,
    val tempPaths: List<String>,
    val appCachePaths: List<String>,
    val thumbPaths: List<String>
)

private data class TreemapBlock(
    val label: String,
    val bytes: Long,
    val sub: String,
    val color: Color
)

@Composable
fun StorageCheckerScreen(telemetry: TelemetryEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var scanning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<StorageScanReport?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableIntStateOf(0) } // 0: Folders, 1: Heavy, 2: Dups, 3: Optimizer
    var selectedTreemapLabel by remember { mutableStateOf<String?>(null) }

    // Super Optimizer selection flags: 0=AppCache, 1=TempLogs, 2=Thumbnails, 3=Duplicates
    val optSelected = remember { mutableStateListOf(true, true, true, false) }

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

    fun cleanPaths(paths: List<String>) {
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
                val allTemp = report?.let { it.tempPaths + it.appCachePaths }?.distinct() ?: emptyList()
                if (allTemp.isNotEmpty()) {
                    DeckButton(
                        text = stringResource(R.string.stor_btn_clean),
                        onClick = { cleanPaths(allTemp) },
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
            // 02 // SQUARIFIED TREEMAP & CATEGORY BREAKDOWN
            val catLabels = listOf(
                stringResource(R.string.stor_cat_photo),
                stringResource(R.string.stor_cat_video),
                stringResource(R.string.stor_cat_audio),
                stringResource(R.string.stor_cat_doc),
                stringResource(R.string.stor_cat_apk),
                stringResource(R.string.stor_cat_cache),
                stringResource(R.string.stor_cat_other)
            )
            val treemapBlocks = remember(currentReport) {
                if (currentReport.folders.isNotEmpty()) {
                    currentReport.folders.filter { it.bytes > 0L }.take(8).mapIndexed { i, f ->
                        TreemapBlock(
                            label = f.name,
                            bytes = f.bytes,
                            sub = "${f.files} files",
                            color = treemapPalette(i)
                        )
                    }
                } else {
                    currentReport.categories.filter { it.bytes > 0L }.mapIndexed { i, c ->
                        TreemapBlock(
                            label = catLabels.getOrElse(c.index) { "OTHER" },
                            bytes = c.bytes,
                            sub = "${c.count} files",
                            color = treemapPalette(i)
                        )
                    }
                }
            }

            DeckPanel(
                title = stringResource(R.string.stor_tag_treemap),
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
                if (treemapBlocks.isNotEmpty()) {
                    SquarifiedTreemapView(
                        blocks = treemapBlocks,
                        selectedLabel = selectedTreemapLabel,
                        onSelect = { selectedTreemapLabel = it.label }
                    )
                    if (selectedTreemapLabel != null) {
                        val hit = treemapBlocks.firstOrNull { it.label == selectedTreemapLabel }
                        if (hit != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "[${hit.label}] ${formatBytesCompact(hit.bytes)} · ${hit.sub}",
                                style = DeckType.Caption,
                                color = BrutalistColors.SignalAccent
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                val maxCatBytes = currentReport.categories.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (cat in currentReport.categories) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${catLabels.getOrElse(cat.index) { "OTHER" }} (${cat.count})",
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

            // 03 // FOLDERS, HEAVY FILES, DUPLICATES & SUPER OPTIMIZER
            DeckPanel(
                title = stringResource(R.string.stor_tag_details),
                ledColor = BrutalistColors.SignalAccent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    DeckChip(
                        text = "${stringResource(R.string.stor_tab_folders)} (${currentReport.folders.size})",
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 }
                    )
                    DeckChip(
                        text = stringResource(R.string.stor_tab_heavy),
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 }
                    )
                    DeckChip(
                        text = "${stringResource(R.string.stor_tab_dups)} (${currentReport.duplicates.size})",
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 }
                    )
                    DeckChip(
                        text = stringResource(R.string.stor_tab_optimizer),
                        selected = activeTab == 3,
                        onClick = { activeTab = 3 }
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                when (activeTab) {
                    0 -> {
                        if (currentReport.folders.isEmpty()) {
                            Text(text = stringResource(R.string.stor_no_folders), style = DeckType.Body)
                        } else {
                            val maxDir = currentReport.folders.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (dir in currentReport.folders) {
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
                                                text = "/${dir.name} (${dir.files})",
                                                style = DeckType.DataMono,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                                            )
                                            Text(
                                                text = formatBytesCompact(dir.bytes),
                                                style = DeckType.DataMono,
                                                color = BrutalistColors.SignalAccent
                                            )
                                        }
                                        SegmentedBarMeter(
                                            fraction = dir.bytes.toFloat() / maxDir.toFloat(),
                                            segments = 18,
                                            height = 5.dp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
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

                    2 -> {
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
                                                onClick = { cleanPaths(dup.paths.drop(1)) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        // SUPER OPTIMIZER CHECKLIST
                        val dupCopyPaths = currentReport.duplicates.flatMap { it.paths.drop(1) }
                        val optRows = listOf(
                            Triple(0, stringResource(R.string.stor_opt_cache), currentReport.appCachePaths),
                            Triple(1, stringResource(R.string.stor_opt_temp), currentReport.tempPaths),
                            Triple(2, stringResource(R.string.stor_opt_thumbs), currentReport.thumbPaths),
                            Triple(3, stringResource(R.string.stor_opt_dups), dupCopyPaths)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((idx, title, paths) in optRows) {
                                val checked = optSelected[idx]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
                                        .border(
                                            1.dp,
                                            if (checked) BrutalistColors.SignalAccent else BrutalistColors.HairlineBorder,
                                            RoundedCornerShape(2.dp)
                                        )
                                        .clickable { optSelected[idx] = !checked }
                                        .padding(9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .background(
                                                    if (checked) BrutalistColors.SignalAccent else BrutalistColors.SteelElevated,
                                                    RoundedCornerShape(2.dp)
                                                )
                                                .border(1.dp, BrutalistColors.ActiveBorder, RoundedCornerShape(2.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (checked) {
                                                Text(
                                                    text = "X",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 10.sp,
                                                    color = BrutalistColors.ObsidianBg
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = title, style = DeckType.DataMono)
                                    }
                                    Text(
                                        text = "${paths.size} items",
                                        style = DeckType.Caption,
                                        color = BrutalistColors.SignalAccent
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val queued = optRows.filter { optSelected[it.first] }.flatMap { it.third }.distinct()
                            DeckButton(
                                text = "${stringResource(R.string.stor_btn_clean_sel)} (${queued.size})",
                                onClick = { cleanPaths(queued) },
                                primary = true,
                                enabled = queued.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SquarifiedTreemapView(
    blocks: List<TreemapBlock>,
    selectedLabel: String?,
    onSelect: (TreemapBlock) -> Unit
) {
    val total = blocks.sumOf { it.bytes }.coerceAtLeast(1L).toFloat()
    // Partition cleanly into 2 balanced rows (squarified top/bottom split)
    val sorted = blocks.sortedByDescending { it.bytes }
    val splitIdx = ((sorted.size + 1) / 2).coerceIn(1, sorted.size)
    val row1 = sorted.subList(0, splitIdx)
    val row2 = sorted.subList(splitIdx, sorted.size)
    val row1Weight = (row1.sumOf { it.bytes }.toFloat() / total).coerceIn(0.25f, 0.78f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .background(BrutalistColors.ObsidianBg, RoundedCornerShape(2.dp))
            .border(1.dp, BrutalistColors.HairlineBorder, RoundedCornerShape(2.dp))
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (row2.isEmpty()) 1f else row1Weight),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (b in row1) {
                TreemapCell(
                    block = b,
                    selected = b.label == selectedLabel,
                    onClick = { onSelect(b) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(b.bytes.toFloat().coerceAtLeast(1f))
                )
            }
        }
        if (row2.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f - row1Weight),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (b in row2) {
                    TreemapCell(
                        block = b,
                        selected = b.label == selectedLabel,
                        onClick = { onSelect(b) },
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(b.bytes.toFloat().coerceAtLeast(1f))
                    )
                }
            }
        }
    }
}

@Composable
private fun TreemapCell(
    block: TreemapBlock,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(1.dp))
            .background(block.color.copy(alpha = if (selected) 0.35f else 0.18f))
            .border(
                1.dp,
                if (selected) BrutalistColors.BoneInk else block.color.copy(alpha = 0.7f),
                RoundedCornerShape(1.dp)
            )
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = block.label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            color = BrutalistColors.BoneInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = formatBytesCompact(block.bytes),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = block.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun treemapPalette(idx: Int): Color {
    val cols = listOf(
        BrutalistColors.SignalAccent,
        BrutalistColors.TitaniumTrace,
        BrutalistColors.StatusNominal,
        BrutalistColors.StatusCaution,
        Color(0xFF7CA4C4),
        Color(0xFFB5967B),
        Color(0xFF9E9EB0),
        BrutalistColors.StatusFault
    )
    return cols[idx % cols.size]
}

private fun parseNativeReport(raw: String): StorageScanReport {
    var files = 0L
    var dirs = 0L
    var bytes = 0L
    var elapsedMs = 0L
    val cats = MutableList(7) { CategoryStat(it, 0L, 0L) }
    val folders = mutableListOf<FolderStat>()
    val heavy = mutableListOf<HeavyFileItem>()
    val dups = mutableListOf<DuplicateGroupItem>()
    val temps = mutableListOf<String>()
    val thumbs = mutableListOf<String>()

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
            "DIR" -> if (parts.size >= 4) {
                val b = parts[1].toLongOrNull() ?: 0L
                val fc = parts[2].toLongOrNull() ?: 0L
                folders.add(FolderStat(parts[3], b, fc))
            }
            "BIG" -> if (parts.size >= 4) {
                val sz = parts[1].toLongOrNull() ?: 0L
                val name = parts[2]
                val path = parts[3]
                heavy.add(HeavyFileItem(sz, name, path))
                val lower = path.lowercase()
                if (lower.contains("/.thumbnails/")) {
                    thumbs.add(path)
                } else if (lower.endsWith(".tmp") || lower.endsWith(".log") ||
                    lower.endsWith(".cache") || lower.endsWith(".bak") || lower.contains("/cache/")
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

    return StorageScanReport(
        files = files,
        dirs = dirs,
        bytes = bytes,
        elapsedMs = elapsedMs,
        categories = cats,
        folders = folders,
        heavyFiles = heavy,
        duplicates = dups,
        tempPaths = temps,
        appCachePaths = emptyList(),
        thumbPaths = thumbs
    )
}

@Suppress("DEPRECATION")
private fun augmentWithMediaStoreAndCache(
    context: android.content.Context,
    base: StorageScanReport
): StorageScanReport {
    val appCaches = mutableListOf<String>()
    val cacheDirs = listOfNotNull(context.cacheDir, context.externalCacheDir)
    var appCacheBytes = 0L
    var appCacheCount = 0L
    for (dir in cacheDirs) {
        dir.walkTopDown().maxDepth(4).forEach { f ->
            if (f.isFile && !f.name.startsWith(".novadeck_io_bench") && !f.absolutePath.contains("/updates/")) {
                appCacheBytes += f.length()
                appCacheCount++
                appCaches.add(f.absolutePath)
            }
        }
    }

    if (base.files > 0L) {
        val updatedCats = base.categories.toMutableList()
        if (appCacheCount > 0L) {
            val prev = updatedCats[5]
            updatedCats[5] = prev.copy(count = prev.count + appCacheCount, bytes = prev.bytes + appCacheBytes)
        }
        return base.copy(categories = updatedCats, appCachePaths = appCaches.distinct())
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
    }
    counts[5] += appCacheCount
    sizes[5] += appCacheBytes

    val mergedCats = (0..6).map { CategoryStat(it, counts[it], sizes[it]) }
    return base.copy(
        files = counts.sum(),
        bytes = sizes.sum(),
        categories = mergedCats,
        heavyFiles = heavy,
        appCachePaths = appCaches.distinct()
    )
}
