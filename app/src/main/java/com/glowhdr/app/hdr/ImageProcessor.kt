package com.glowhdr.app.hdr

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

class ProcessResult(val base: Bitmap, val gainmap: Bitmap?)

/**
 * 单一 CPU 处理管线：预览与导出使用完全相同的实现，保证所见即所得。
 *
 * 增益图编码与 libultrahdr（Ultra HDR 规范 / ISO 21496-1 应用语义）一致：
 *   boost = headroom ^ (stored ^ (1 / gainGamma))，其中 stored 为增益图像素（0..1）。
 * 因此编码端 stored = (log2(ratio) / log2(headroom)) ^ gainGamma。
 */
object ImageProcessor {

    /** 亮度/对比度/伽马合并为一张 256 级查找表 */
    fun buildToneLut(p: HdrParams): IntArray {
        val lut = IntArray(256)
        val exposureGain = 2f.pow(p.exposure)
        val contrast = 1f + p.contrast
        val invGamma = 1f / p.gamma.coerceAtLeast(0.05f)
        for (i in 0..255) {
            var v = (i / 255f) * exposureGain
            v = (v - 0.5f) * contrast + 0.5f
            v = v.coerceIn(0f, 1f)
            v = v.pow(invGamma)
            lut[i] = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** 增益图查找表：输入基础图像亮度（0..255），输出编码后的增益字节 */
    fun buildGainLut(p: HdrParams): FloatArray {
        val lut = FloatArray(256)
        val headroom = p.headroom.coerceIn(1f, 16f)
        val logMax = log2(headroom)
        val gamma = p.gainGamma.coerceIn(0.1f, 4f)
        for (i in 0..255) {
            val l = i / 255f
            val boost = p.highlightWeight * l * l + (1f - p.highlightWeight)
            val ratio = 1f + (headroom - 1f) * boost
            val norm = if (logMax <= 0f) 0f else (log2(ratio) / logMax).coerceIn(0f, 1f)
            lut[i] = norm.pow(gamma)
        }
        return lut
    }

    /**
     * 生成处理后的基础图与增益图。generateGainmap = false 时不生成增益图
     * （用于已是 Ultra HDR 的图片：沿用原图增益分布，只处理基础图）。
     * 增益图为原图 1/2 分辨率（Ultra HDR 规范允许）。
     * px 数组原地复用：增益值写回时写索引始终 <= 读索引。
     */
    fun process(
        src: Bitmap,
        p: HdrParams,
        recycleSource: Boolean = false,
        generateGainmap: Boolean = true,
    ): ProcessResult {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (recycleSource) src.recycle()

        val lut = buildToneLut(p)
        val temp = p.temperature.coerceIn(-1f, 1f)
        val rMul = 1f + temp * 0.18f
        val bMul = 1f - temp * 0.18f
        val sat = p.saturation.coerceIn(0f, 2f)
        val useTemp = temp != 0f
        val useSat = sat != 1f

        for (i in px.indices) {
            val c = px[i]
            var r = lut[(c ushr 16) and 0xFF].toFloat()
            var g = lut[(c ushr 8) and 0xFF].toFloat()
            var b = lut[c and 0xFF].toFloat()
            if (useTemp) {
                r *= rMul
                b *= bMul
            }
            if (useSat) {
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                r = lum + (r - lum) * sat
                g = lum + (g - lum) * sat
                b = lum + (b - lum) * sat
            }
            px[i] = (255 shl 24) or (clampByte(r) shl 16) or (clampByte(g) shl 8) or clampByte(b)
        }

        val base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        base.setPixels(px, 0, w, 0, 0, w, h)

        if (!generateGainmap) return ProcessResult(base, null)

        val gw = max(1, w / 2)
        val gh = max(1, h / 2)
        val gainLut = buildGainLut(p)
        var gi = 0
        for (row in 0 until gh) {
            val rowBase = row * 2 * w
            for (col in 0 until gw) {
                val c = px[rowBase + col * 2]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val l = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                val e = (gainLut[(l * 255f).toInt().coerceIn(0, 255)] * 255f + 0.5f)
                    .toInt().coerceIn(0, 255)
                px[gi++] = (255 shl 24) or (e shl 16) or (e shl 8) or e
            }
        }

        val gainmapBitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        gainmapBitmap.setPixels(px, 0, gw, 0, 0, gw, gh)
        return ProcessResult(base, gainmapBitmap)
    }

    /**
     * 构建系统预览用的 Gainmap 对象（附加到 Bitmap 上由系统硬件合成）。
     * 注意：android.graphics.Gainmap 的 gamma 与文件内 XMP 的 gamma 互为倒数
     * （系统着色器按 pow(G, gamma) 应用，文件语义为 pow(stored, 1/gamma)）。
     */
    fun buildGainmapObject(gainmapBitmap: Bitmap, p: HdrParams): Gainmap {
        val headroom = p.headroom.coerceIn(1f, 16f)
        val gamma = 1f / p.gainGamma.coerceIn(0.1f, 4f)
        return Gainmap(gainmapBitmap).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(headroom, headroom, headroom)
            setGamma(gamma, gamma, gamma)
            setMinDisplayRatioForHdrTransition(1f)
            setDisplayRatioForFullHdr(headroom)
        }
    }

    private fun clampByte(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> v.toInt()
    }
}

/** 大图安全解码：按最长边与总像素上限做下采样，并按 EXIF 方向摆正 */
object ImageLoader {

    fun decodeScaled(cr: ContentResolver, uri: Uri, maxSide: Int, maxPixels: Long): Bitmap? {
        var sample = 1
        repeat(5) {
            try {
                // 第一次只读尺寸：decodeStream 在 inJustDecodeBounds 下本来就返回 null，
                // 只需检查流是否成功打开，不能把返回值当失败依据
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val boundsStream = cr.openInputStream(uri) ?: return null
                boundsStream.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val w = bounds.outWidth
                val h = bounds.outHeight
                val largest = max(w, h)
                if (maxSide != Int.MAX_VALUE) {
                    while (largest / (sample * 2) >= maxSide) sample *= 2
                }
                while (w.toLong() * h / (sample.toLong() * sample) > maxPixels) sample *= 2

                val orientation = readOrientation(cr, uri)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = cr.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return null
                return transform(decoded, orientation, maxSide)
            } catch (_: OutOfMemoryError) {
                sample *= 2
            } catch (_: Exception) {
                // 流被关闭、授权失效等非内存类失败，直接判定解码失败
                return null
            }
        }
        return null
    }

    private fun readOrientation(cr: ContentResolver, uri: Uri): Int = try {
        cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun transform(src: Bitmap, orientation: Int, maxSide: Int): Bitmap {
        // 源图若带增益图（Ultra HDR 输入），摆正/缩放时需对增益图内容做同样变换
        val srcGm = src.gainmap
        val gSrc = srcGm?.gainmapContents
        val m = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        var gT = gSrc
        if (gT != null && !m.isIdentity) {
            gT = Bitmap.createBitmap(gT, 0, 0, gT.width, gT.height, m, true)
        }
        var out = src
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
            if (rotated != out) out.recycle()
            out = rotated
        }
        val longSide = max(out.width, out.height)
        if (maxSide != Int.MAX_VALUE && longSide > maxSide) {
            val scale = maxSide.toFloat() / longSide
            val nw = (out.width * scale).toInt().coerceAtLeast(1)
            val nh = (out.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(out, nw, nh, true)
            if (scaled != out) out.recycle()
            out = scaled
            if (gT != null) {
                val gw = (gT.width * scale).toInt().coerceAtLeast(1)
                val gh = (gT.height * scale).toInt().coerceAtLeast(1)
                val gScaled = Bitmap.createScaledBitmap(gT, gw, gh, true)
                if (gScaled != gT) gT.recycle()
                gT = gScaled
            }
        }
        if (out !== src && srcGm != null && gT != null) {
            out.setGainmap(copyGainmapMetadata(srcGm, gT))
        }
        return out
    }

    /** API 34 没有 Gainmap(Gainmap, Bitmap) 构造器，逐项复制元数据 */
    private fun copyGainmapMetadata(src: Gainmap, contents: Bitmap): Gainmap =
        Gainmap(contents).apply {
            val rmin = src.ratioMin
            setRatioMin(rmin[0], rmin[1], rmin[2])
            val rmax = src.ratioMax
            setRatioMax(rmax[0], rmax[1], rmax[2])
            val g = src.gamma
            setGamma(g[0], g[1], g[2])
            val es = src.epsilonSdr
            setEpsilonSdr(es[0], es[1], es[2])
            val eh = src.epsilonHdr
            setEpsilonHdr(eh[0], eh[1], eh[2])
            setMinDisplayRatioForHdrTransition(src.minDisplayRatioForHdrTransition)
            setDisplayRatioForFullHdr(src.displayRatioForFullHdr)
        }
}
