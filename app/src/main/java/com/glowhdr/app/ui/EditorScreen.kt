package com.glowhdr.app.ui

import android.widget.ImageView
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glowhdr.app.hdr.HdrParams
import com.glowhdr.app.hdr.ImageProcessor
import com.glowhdr.app.hdr.ResolutionMode
import com.glowhdr.app.vm.AppViewModel
import kotlin.math.roundToInt

@Composable
fun EditorScreen(vm: AppViewModel) {
    // 直接调起系统图库（ACTION_PICK）：
    // 部分机型（如小米安全访问）的照片选择器不返回真实文件名
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        vm.setImage(uri)
    }
    val launchPick = {
        picker.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
    }

    var showSavePreset by remember { mutableStateOf(false) }
    var showManagePresets by remember { mutableStateOf(false) }

    val editorUri = vm.editorUri
    val isExporting = vm.isExporting
    val params by vm.params
    val hdrPreview = vm.hdrPreview

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("GlowHDR", style = MaterialTheme.typography.titleLarge)
                if (vm.isExistingHdr) {
                    Text(
                        "Ultra HDR",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            IconButton(onClick = {
                launchPick()
            }) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "选择图片")
            }
            IconButton(onClick = { showSavePreset = true }) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = "保存预设")
            }
            IconButton(onClick = { showManagePresets = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = "管理预设")
            }
            Button(
                onClick = { vm.exportCurrent() },
                enabled = editorUri != null && !isExporting,
                modifier = Modifier.padding(end = 4.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (isExporting) "导出中" else "导出")
            }
        }

        PresetChipsRow(vm)

        // 预览区
        Box(
            Modifier.weight(1.05f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val base = vm.previewBase
            val gainBmp = vm.previewGainmap
            if (base == null) {
                EmptyEditor(onPick = {
                    launchPick()
                })
            } else {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { iv ->
                        base.setGainmap(
                            if (hdrPreview && gainBmp != null) {
                                ImageProcessor.buildGainmapObject(gainBmp, params)
                            } else {
                                null
                            }
                        )
                        iv.setImageBitmap(base)
                        iv.invalidate()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (isExporting) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }

        // HDR / SDR 对比切换：放在预览图下方，不遮挡图片
        if (vm.previewBase != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = hdrPreview,
                    onClick = { vm.hdrPreview = true },
                    label = { Text("HDR 效果") },
                )
                FilterChip(
                    selected = !hdrPreview,
                    onClick = { vm.hdrPreview = false },
                    label = { Text("SDR 原样") },
                )
            }
        }

        // 参数面板
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "参数", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { vm.setParams(HdrParams.DEFAULT) },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("重置") }
            }

            SectionLabel("基础调整")
            LabeledSlider("亮度（曝光）", params.exposure, -2f, 2f, { "%+.2f EV".format(it) }) {
                vm.setParams(params.copy(exposure = it))
            }
            LabeledSlider("对比度", params.contrast, -1f, 1f, { "%+.2f".format(it) }) {
                vm.setParams(params.copy(contrast = it))
            }
            LabeledSlider("伽马", params.gamma, 0.2f, 3f, { "%.2f".format(it) }) {
                vm.setParams(params.copy(gamma = it))
            }
            LabeledSlider("饱和度", params.saturation, 0f, 2f, { "× %.2f".format(it) }) {
                vm.setParams(params.copy(saturation = it))
            }
            LabeledSlider("色温", params.temperature, -1f, 1f, ::tempLabel) {
                vm.setParams(params.copy(temperature = it))
            }

            SectionLabel("HDR 增益")
            if (vm.isExistingHdr) {
                Text(
                    "已识别 Ultra HDR：沿用原图增益分布，可调整峰值亮度与增益伽马",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LabeledSlider("峰值亮度", params.headroom, 1f, 8f, { "× %.1f".format(it) }) {
                vm.setParams(params.copy(headroom = it))
            }
            if (!vm.isExistingHdr) {
                LabeledSlider("高光权重", params.highlightWeight, 0f, 1f, { "%.2f".format(it) }) {
                    vm.setParams(params.copy(highlightWeight = it))
                }
            }
            LabeledSlider("增益伽马", params.gainGamma, 0.5f, 2f, { "%.2f".format(it) }) {
                vm.setParams(params.copy(gainGamma = it))
            }

            SectionLabel("导出设置")
            LabeledSlider("JPEG 质量", params.quality.toFloat(), 60f, 100f,
                { it.roundToInt().toString() }) {
                vm.setParams(params.copy(quality = it.roundToInt()))
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResolutionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = vm.resolution == mode,
                        onClick = { vm.resolution = mode },
                        label = { Text(mode.label) },
                    )
                }
            }
        }
    }

    if (showSavePreset) {
        SavePresetDialog(
            onDismiss = { showSavePreset = false },
            onConfirm = { name ->
                vm.savePreset(name)
                showSavePreset = false
            },
        )
    }
    if (showManagePresets) {
        ManagePresetsDialog(
            presets = vm.presets.toList(),
            onDelete = { vm.deletePreset(it) },
            onDismiss = { showManagePresets = false },
        )
    }
}

private fun tempLabel(v: Float): String = when {
    v > 0.02f -> "暖 %.2f".format(v)
    v < -0.02f -> "冷 %.2f".format(v)
    else -> "中性"
}

/** 编辑页空状态：引导选图 */
@Composable
private fun EmptyEditor(onPick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.AddAPhoto,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "选择图片开始，SDR 转换或 Ultra HDR 编辑",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "转换为 Ultra HDR，实时预览，支持批量生成",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = onPick) { Text("选择图片") }
    }
}
