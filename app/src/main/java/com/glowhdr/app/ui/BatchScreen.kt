package com.glowhdr.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glowhdr.app.hdr.ResolutionMode
import com.glowhdr.app.vm.AppViewModel
import com.glowhdr.app.vm.BatchState

@Composable
fun BatchScreen(vm: AppViewModel) {
    // 直接调起系统图库（ACTION_PICK + 多选标志），返回真实文件名
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) vm.addBatch(uris)
    }
    val launchPickMultiple = {
        multiPicker.launch(
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        )
    }

    val batchRunning = vm.batchRunning
    val batchList = vm.batchItems

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "批量生成",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            IconButton(onClick = {
                launchPickMultiple()
            }) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "选择多张图片")
            }
            IconButton(
                onClick = { vm.clearBatch() },
                enabled = !batchRunning && batchList.isNotEmpty(),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "清空列表")
            }
        }

        if (batchList.isEmpty()) {
            EmptyBatch(onPick = {
                launchPickMultiple()
            })
            return@Column
        }

        val done = batchList.count { it.state == BatchState.DONE || it.state == BatchState.FAILED }
        val remaining = batchList.count { it.state != BatchState.DONE }
        Text(
            "已选 ${batchList.size} 张，已完成 $done/${batchList.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LinearProgressIndicator(
            progress = {
                if (batchList.isEmpty()) 0f else done.toFloat() / batchList.size
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(batchList, key = { it.uri }) { item ->
                BatchRow(vm = vm, item = item, running = batchRunning)
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "参数来源",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = vm.batchUsePreset == null,
                    onClick = { vm.batchUsePreset = null },
                )
                Text("当前编辑参数")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = vm.batchUsePreset != null,
                    onClick = { if (vm.presets.isNotEmpty()) {
                        vm.batchUsePreset = vm.presets.first().name
                    } },
                    enabled = vm.presets.isNotEmpty(),
                )
                PresetDropdown(vm)
            }

            Text(
                "输出分辨率",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResolutionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = vm.resolution == mode,
                        onClick = { vm.resolution = mode },
                        label = { Text(mode.label) },
                    )
                }
            }

            Button(
                onClick = { vm.startBatch() },
                enabled = !batchRunning && remaining > 0,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) {
                Text(
                    when {
                        batchRunning -> "正在批量生成…"
                        remaining < batchList.size -> "继续批量生成（剩余 $remaining 张）"
                        else -> "开始批量生成（${batchList.size} 张）"
                    }
                )
            }
        }
    }
}

@Composable
private fun BatchRow(vm: AppViewModel, item: com.glowhdr.app.vm.BatchItem, running: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumb = vm.batchThumbs[item.uri]
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Photo,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (item.state) {
                    BatchState.PENDING -> "等待处理"
                    BatchState.RUNNING -> "正在生成…"
                    BatchState.DONE -> "已保存：${item.message ?: ""}"
                    BatchState.FAILED -> "失败：${item.message ?: "未知错误"}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (item.state) {
                    BatchState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    BatchState.RUNNING -> MaterialTheme.colorScheme.primary
                    BatchState.DONE -> Color(0xFF2E7D32)
                    BatchState.FAILED -> MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (item.state) {
            BatchState.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp).padding(end = 28.dp)
            )
            BatchState.DONE -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF2E7D32),
                modifier = Modifier.padding(end = 28.dp),
            )
            BatchState.FAILED -> Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 28.dp),
            )
            BatchState.PENDING -> {}
        }
        IconButton(onClick = { vm.removeBatchItem(item.uri) }, enabled = !running) {
            Icon(Icons.Filled.Close, contentDescription = "移除")
        }
    }
}

@Composable
private fun EmptyBatch(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.AddPhotoAlternate,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "批量选择图片",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "使用当前参数或某个预设，一次生成多张 Ultra HDR",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Button(onClick = onPick) { Text("选择图片") }
    }
}
