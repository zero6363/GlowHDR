package com.glowhdr.app.hdr

import org.json.JSONObject

/**
 * Ultra HDR 生成参数。所有参数都会参与实时预览与最终导出，所见即所得。
 *
 * 亮度/对比度/伽马/饱和度/色温作用于 SDR 基础图像；
 * 峰值亮度/高光权重/增益伽马决定 HDR 增益图（gain map）的形态。
 */
data class HdrParams(
    /** 曝光（亮度），单位 EV，范围 -2..2 */
    val exposure: Float = 0f,
    /** 对比度，范围 -1..1 */
    val contrast: Float = 0f,
    /** 伽马，范围 0.2..3（<1 提亮，>1 压暗） */
    val gamma: Float = 1f,
    /** 饱和度倍率，范围 0..2 */
    val saturation: Float = 1f,
    /** 色温，-1 冷 .. 1 暖 */
    val temperature: Float = 0f,
    /** HDR 峰值亮度倍数（显示设备最大亮度 / SDR 白），范围 1..8 */
    val headroom: Float = 4f,
    /** 高光权重：1 = 增益集中在高光，0 = 全画面均匀增益 */
    val highlightWeight: Float = 1f,
    /** 增益伽马：增益图亮度映射曲线，范围 0.5..2 */
    val gainGamma: Float = 1f,
    /** JPEG 输出质量，范围 60..100 */
    val quality: Int = 95,
) {

    fun toJson(): String = JSONObject().apply {
        put("exposure", exposure.toDouble())
        put("contrast", contrast.toDouble())
        put("gamma", gamma.toDouble())
        put("saturation", saturation.toDouble())
        put("temperature", temperature.toDouble())
        put("headroom", headroom.toDouble())
        put("highlightWeight", highlightWeight.toDouble())
        put("gainGamma", gainGamma.toDouble())
        put("quality", quality)
    }.toString()

    companion object {
        val DEFAULT = HdrParams()

        fun fromJson(text: String): HdrParams = try {
            val o = JSONObject(text)
            HdrParams(
                exposure = o.optDouble("exposure", 0.0).toFloat().coerceIn(-2f, 2f),
                contrast = o.optDouble("contrast", 0.0).toFloat().coerceIn(-1f, 1f),
                gamma = o.optDouble("gamma", 1.0).toFloat().coerceIn(0.2f, 3f),
                saturation = o.optDouble("saturation", 1.0).toFloat().coerceIn(0f, 2f),
                temperature = o.optDouble("temperature", 0.0).toFloat().coerceIn(-1f, 1f),
                headroom = o.optDouble("headroom", 4.0).toFloat().coerceIn(1f, 8f),
                highlightWeight = o.optDouble("highlightWeight", 1.0).toFloat().coerceIn(0f, 1f),
                gainGamma = o.optDouble("gainGamma", 1.0).toFloat().coerceIn(0.5f, 2f),
                quality = o.optInt("quality", 95).coerceIn(60, 100),
            )
        } catch (_: Exception) {
            DEFAULT
        }
    }
}

/** 命名预设：一组可复用的参数 */
data class Preset(val name: String, val params: HdrParams) {

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("params", JSONObject(params.toJson()))
    }.toString()

    fun summary(): String {
        val p = params
        val parts = mutableListOf<String>()
        parts += "曝光 %+.2f".format(p.exposure)
        if (p.contrast != 0f) parts += "对比度 %+.2f".format(p.contrast)
        if (p.gamma != 1f) parts += "伽马 %.2f".format(p.gamma)
        if (p.saturation != 1f) parts += "饱和度 %.2f".format(p.saturation)
        if (p.temperature != 0f) parts += "色温 %+.2f".format(p.temperature)
        parts += "HDR ×%.1f".format(p.headroom)
        if (p.highlightWeight != 1f) parts += "高光权重 %.2f".format(p.highlightWeight)
        if (p.gainGamma != 1f) parts += "增益伽马 %.2f".format(p.gainGamma)
        return parts.joinToString(" · ")
    }

    companion object {
        fun fromJson(o: JSONObject): Preset? = try {
            val name = o.optString("name").ifBlank { return null }
            val paramsJson = o.optJSONObject("params")?.toString() ?: return null
            Preset(name, HdrParams.fromJson(paramsJson))
        } catch (_: Exception) {
            null
        }
    }
}

/** 导出分辨率档位 */
enum class ResolutionMode(val label: String, val maxSide: Int, val maxPixels: Long) {
    ORIGINAL("原图", Int.MAX_VALUE, 24_000_000L),
    M4096("4096", 4096, 48_000_000L),
    M2048("2048", 2048, 48_000_000L),
}
