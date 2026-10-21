package com.glowhdr.app.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glowhdr.app.data.PresetStore
import com.glowhdr.app.hdr.HdrParams
import com.glowhdr.app.hdr.ImageLoader
import com.glowhdr.app.hdr.ImageProcessor
import com.glowhdr.app.hdr.Preset
import com.glowhdr.app.hdr.ResolutionMode
import com.glowhdr.app.hdr.UltraHdrExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BatchState { PENDING, RUNNING, DONE, FAILED }

data class BatchItem(
    val uri: Uri,
    val name: String,
    val state: BatchState,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val PREVIEW_MAX_SIDE = 1080
        private const val PREVIEW_MAX_PIXELS = 8_000_000L
        private const val THUMB_MAX_SIDE = 160
    }

    private val presetStore = PresetStore(app)
    private val cr get() = getApplication<Application>().contentResolver

    /** 当前编辑参数（预览、单张导出、批量默认共用） */
    val params = mutableStateOf(HdrParams.DEFAULT)
    val presets = mutableStateListOf<Preset>()

    var editorUri by mutableStateOf<Uri?>(null)
        private set
    var previewSource by mutableStateOf<Bitmap?>(null)
        private set
    var previewBase by mutableStateOf<Bitmap?>(null)
        private set
    var previewGainmap by mutableStateOf<Bitmap?>(null)
        private set

    /** 源图本身已是 Ultra HDR（自带增益图）：沿用原图增益分布，仅编辑增益元数据 */
    var isExistingHdr by mutableStateOf(false)
        private set
    /** 源图自带的增益图内容（预览用） */
    var importedGainContents by mutableStateOf<Bitmap?>(null)
        private set

    /** 预览开关：true = HDR 效果（附加增益图），false = SDR 原样 */
    var hdrPreview by mutableStateOf(true)
    var resolution by mutableStateOf(ResolutionMode.ORIGINAL)
    var isExporting by mutableStateOf(false)
        private set

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    val batchItems = mutableStateListOf<BatchItem>()
    var batchRunning by mutableStateOf(false)
        private set
    /** 批量参数来源：null = 当前编辑参数，否则为预设名 */
    var batchUsePreset by mutableStateOf<String?>(null)
    val batchThumbs = mutableStateMapOf<Uri, Bitmap>()

    private var loadJob: Job? = null
    private var previewJob: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val previewDispatcher = Dispatchers.Default.limitedParallelism(1)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = presetStore.load()
            withContext(Dispatchers.Main) { presets.addAll(loaded) }
        }
    }

    fun toast(message: String) {
        messages.tryEmit(message)
    }

    fun setImage(uri: Uri) {
        editorUri = uri
        loadJob?.cancel()
        previewJob?.cancel()
        previewSource = null
        previewBase = null
        previewGainmap = null
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val bmp = ImageLoader.decodeScaled(cr, uri, PREVIEW_MAX_SIDE, PREVIEW_MAX_PIXELS)
            if (!isActive) return@launch
            if (bmp == null) {
                toast("图片解码失败")
                return@launch
            }
            previewSource = bmp
            val gm = bmp.gainmap
            if (gm != null) {
                isExistingHdr = true
                importedGainContents = gm.gainmapContents
                // 从文件元数据回填增益参数（框架层 gamma 与文件内 gamma 互为倒数）
                val headroom = gm.ratioMax[0].coerceIn(1f, 8f)
                val fileGamma = (1f / gm.gamma[0].coerceAtLeast(0.05f)).coerceIn(0.1f, 4f)
                params.value = HdrParams(
                    headroom = headroom,
                    gainGamma = fileGamma,
                    quality = params.value.quality,
                )
                toast("已识别 Ultra HDR，可编辑增益参数")
            } else {
                isExistingHdr = false
                importedGainContents = null
            }
            refreshPreview()
        }
    }

    fun setParams(p: HdrParams) {
        params.value = p
        refreshPreview()
    }

    private fun refreshPreview() {
        val src = previewSource ?: return
        val p = params.value
        previewJob?.cancel()
        previewJob = viewModelScope.launch(previewDispatcher) {
            delay(30) // 防抖，拖动滑块时避免过频重算
            val r = ImageProcessor.process(src, p, generateGainmap = !isExistingHdr)
            if (!isActive) return@launch
            previewBase = r.base
            previewGainmap = r.gainmap ?: importedGainContents
        }
    }

    fun applyPreset(preset: Preset) = setParams(preset.params)

    fun savePreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        presets.removeAll { it.name == trimmed }
        presets.add(Preset(trimmed, params.value))
        persistPresets()
        toast("预设「$trimmed」已保存")
    }

    fun deletePreset(name: String) {
        presets.removeAll { it.name == name }
        if (batchUsePreset == name) batchUsePreset = null
        persistPresets()
    }

    private fun persistPresets() {
        val snapshot = presets.toList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { presetStore.save(snapshot) }
        }
    }

    fun exportCurrent() {
        val uri = editorUri ?: return
        if (isExporting) return
        isExporting = true
        viewModelScope.launch {
            UltraHdrExporter.export(getApplication(), uri, params.value, resolution)
                .fold(
                    onSuccess = { toast("已保存：$it") },
                    onFailure = { toast("导出失败：${it.message ?: "未知错误"}") },
                )
            isExporting = false
        }
    }

    /** 追加加入批量列表：再次选择图片时只新增未包含的图（按 uri 去重），缩略图仅为新图加载 */
    fun addBatch(uris: List<Uri>) {
        if (batchRunning) return
        val existing = batchItems.mapTo(HashSet()) { it.uri }
        val fresh = uris.filter { it !in existing }
        if (fresh.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val newItems = fresh.map {
                BatchItem(it, UltraHdrExporter.rawName(cr, it), BatchState.PENDING)
            }
            batchItems.addAll(newItems)
            for (item in newItems) {
                val bmp = ImageLoader.decodeScaled(cr, item.uri, THUMB_MAX_SIDE, 4_000_000L)
                if (bmp != null) batchThumbs[item.uri] = bmp
            }
        }
    }

    fun removeBatchItem(uri: Uri) {
        if (batchRunning) return
        batchItems.removeAll { it.uri == uri }
        batchThumbs.remove(uri)
    }

    fun clearBatch() {
        if (batchRunning) return
        batchItems.clear()
        batchThumbs.clear()
    }

    fun startBatch() {
        if (batchRunning || batchItems.isEmpty()) return
        val p = batchUsePreset
            ?.let { name -> presets.find { it.name == name }?.params }
            ?: params.value
        // 只处理未完成的项：追加新图后再次批量，不会重复生成已导出的图
        val items = batchItems.filter { it.state != BatchState.DONE }
        if (items.isEmpty()) return
        batchRunning = true
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0
            var fail = 0
            for (item in items) {
                updateItem(item.uri) { it.copy(state = BatchState.RUNNING, message = null) }
                val result = UltraHdrExporter.export(getApplication(), item.uri, p, resolution)
                if (result.isSuccess) {
                    ok++
                    updateItem(item.uri) {
                        it.copy(state = BatchState.DONE, message = result.getOrNull())
                    }
                } else {
                    fail++
                    updateItem(item.uri) {
                        it.copy(
                            state = BatchState.FAILED,
                            message = result.exceptionOrNull()?.message ?: "失败",
                        )
                    }
                }
            }
            withContext(Dispatchers.Main) { batchRunning = false }
            toast("批量完成：成功 $ok 张，失败 $fail 张")
        }
    }

    private fun updateItem(uri: Uri, transform: (BatchItem) -> BatchItem) {
        val idx = batchItems.indexOfFirst { it.uri == uri }
        if (idx >= 0) batchItems[idx] = transform(batchItems[idx])
    }
}
