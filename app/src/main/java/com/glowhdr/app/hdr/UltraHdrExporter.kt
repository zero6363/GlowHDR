package com.glowhdr.app.hdr

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.log2

/**
 * 纯 Kotlin 实现的 Ultra HDR（JPEG_R）编码器。
 *
 * Android 没有公开的 JPEG_R 压缩 API（Bitmap.CompressFormat 仅支持 JPEG/PNG/WEBP），
 * 这里按 Ultra HDR Image Format 规范（与 libultrahdr 写出字节一致）手工组装：
 *
 *   主图 JPEG：SOI, APP0(JFIF), APP1(XMP: GContainer + hdrgm:Version), DQT/SOF/DHT,
 *              APP2(MPF, 紧邻 SOS 之前), SOS..EOI
 *   次图 JPEG：SOI, APP1(XMP: hdrgm 增益元数据), 增益图 JPEG（去掉自身 SOI）
 *
 * 增益应用语义（解码端）：boost = headroom ^ (stored ^ (1/gamma))，
 * 与 ImageProcessor 的编码端互为逆运算。
 */
object UltraHdrExporter {

    const val SUB_DIR = "GlowHDR"
    const val EXPORT_DISPLAY_PATH = "Pictures/$SUB_DIR"

    private const val GAINMAP_JPEG_QUALITY = 90
    private const val MPF_PAYLOAD_SIZE = 86

    /** Adobe XMP 规范的 APP1 命名空间（含结尾 NUL，共 29 字节） */
    private val XMP_NAMESPACE =
        "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)

    /** ISO 21496-1 增益图元数据的 APP2 命名空间（含结尾 NUL，共 28 字节） */
    private val ISO_NAMESPACE =
        "urn:iso:std:iso:ts:21496:-1\u0000".toByteArray(Charsets.US_ASCII)

    /** SDR/HDR 应用偏移，Ultra HDR 规范默认值 1/64 */
    private const val GAIN_OFFSET = 0.015625f

    /** 单张导出：解码 → 处理 → 编码 → 写入 MediaStore，返回相册相对路径 */
    suspend fun export(
        context: Context,
        sourceUri: Uri,
        params: HdrParams,
        resolution: ResolutionMode,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching { exportInternal(context, sourceUri, params, resolution) }
    }

    private fun exportInternal(
        context: Context,
        sourceUri: Uri,
        params: HdrParams,
        resolution: ResolutionMode,
    ): String {
        val cr = context.contentResolver
        var maxPixels = resolution.maxPixels
        while (true) {
            try {
                val src = ImageLoader.decodeScaled(cr, sourceUri, resolution.maxSide, maxPixels)
                    ?: error("无法解码图片")
                // 源图已是 Ultra HDR：沿用其增益图内容（像素分布不变），
                // 峰值亮度/增益伽马等元数据按当前滑块参数重写
                val srcGainmap = src.gainmap
                val processed = ImageProcessor.process(
                    src, params, recycleSource = true, generateGainmap = srcGainmap == null
                )
                val gainBmp = srcGainmap?.gainmapContents?.let { toRgbBitmap(it) }
                    ?: processed.gainmap
                    ?: error("增益图生成失败")
                val bytes = try {
                    encodeUltraHdr(processed.base, gainBmp, params)
                } finally {
                    processed.base.recycle()
                    processed.gainmap?.recycle()
                    if (srcGainmap != null) gainBmp.recycle()
                }
                val name = rawName(cr, sourceUri).substringBeforeLast('.')
                return saveJpeg(cr, bytes, "${name}_hdr.jpg")
            } catch (e: OutOfMemoryError) {
                if (maxPixels <= 3_000_000L) {
                    throw IllegalStateException("内存不足，图片过大", e)
                }
                maxPixels /= 2
            }
        }
    }

    /**
     * 系统解码的增益图内容可能是单通道 ALPHA_8（灰度增益），JPEG 压缩不支持该格式，
     * 把 alpha 通道的增益值复制到 RGB 后转成 ARGB_8888。
     */
    private fun toRgbBitmap(contents: Bitmap): Bitmap {
        if (contents.config != Bitmap.Config.ALPHA_8) return contents
        val w = contents.width
        val h = contents.height
        val px = IntArray(w * h)
        contents.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val a = px[i] ushr 24
            px[i] = (0xFF shl 24) or (a shl 16) or (a shl 8) or a
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /**
     * 解析原始文件名。部分机型的相册选择器会把 DISPLAY_NAME 返回成纯数字（媒体库 id），
     * 因此按多级回退取第一个"看起来像真实文件名"（带扩展名）的结果。
     */
    fun rawName(cr: android.content.ContentResolver, uri: Uri): String {
        val candidates = mutableListOf<String?>()
        candidates += queryColumn(cr, uri, android.provider.OpenableColumns.DISPLAY_NAME)
        candidates += queryColumn(cr, uri, MediaStore.Images.Media.DISPLAY_NAME)
        uri.lastPathSegment?.takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } }?.let { id ->
            // 尝试用 id 反查图库（需系统放行；失败则静默跳过）
            candidates += queryColumn(
                cr,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.DISPLAY_NAME,
                id,
            )
            candidates += queryColumn(
                cr,
                MediaStore.Files.getContentUri("external"),
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                id,
            )
        }
        val valid = candidates.filterNotNull()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val chosen = valid.firstOrNull { it.contains('.') }
            ?: valid.firstOrNull()
            ?: "image"
        // 去掉扩展名（统一输出 .jpg），并清理文件名非法字符
        return chosen.substringBeforeLast('.')
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun queryColumn(
        cr: android.content.ContentResolver,
        uri: Uri,
        column: String,
        id: String? = null,
    ): String? = try {
        val selection = if (id != null) "_id=?" else null
        val args = if (id != null) arrayOf(id) else null
        cr.query(uri, arrayOf(column), selection, args, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- encoding

    private fun encodeUltraHdr(base: Bitmap, gainmap: Bitmap, params: HdrParams): ByteArray {
        val baseJpeg = compressToJpeg(base, params.quality)
        val gmJpeg = compressToJpeg(gainmap, GAINMAP_JPEG_QUALITY)

        val xmpSecondarySegment = buildXmpApp1(buildSecondaryXmp(params))
        val isoSecondarySegment = buildApp2(
            ISO_NAMESPACE,
            buildIsoMetadataPayload(params),
        )
        // 次图整体 = SOI + XMP APP1 + ISO APP2 + 增益图 JPEG（去 SOI）
        val secondarySize = 2 + xmpSecondarySegment.size + isoSecondarySegment.size +
            (gmJpeg.size - 2)

        val xmpPrimarySegment = buildXmpApp1(buildPrimaryXmp(secondarySize))

        val out = ByteArrayOutputStream(baseJpeg.size + gmJpeg.size + 512)
        out.write(0xFF); out.write(0xD8) // SOI

        // JFIF APP0 必须位于最前，先复制。
        // 段总长 = 2 字节标记 + 长度字段（长度值含自身），不能只按长度值前进
        var pos = 2
        if (baseJpeg.size >= 6 && baseJpeg[2] == 0xFF.toByte() && baseJpeg[3] == 0xE0.toByte()) {
            val segLen = u16(baseJpeg, 4)
            val segTotal = 2 + segLen
            if (2 + segTotal <= baseJpeg.size) {
                out.write(baseJpeg, 2, segTotal)
                pos = 2 + segTotal
            }
        }

        out.write(xmpPrimarySegment, 0, xmpPrimarySegment.size)

        // ISO 21496-1 主图侧只写版本声明段（与 libultrahdr 一致）
        val isoPrimarySegment = buildApp2(ISO_NAMESPACE, byteArrayOf(0, 0, 0, 0))
        out.write(isoPrimarySegment, 0, isoPrimarySegment.size)

        // 复制余下的头段（DQT/SOF/DHT...），在 SOS 之前插入 MPF，再原样复制熵编码数据到 EOI
        var mpfWritten = false
        while (pos + 3 < baseJpeg.size && baseJpeg[pos] == 0xFF.toByte()) {
            val marker = baseJpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xFF) { pos++; continue }             // 填充字节
            if (marker == 0xD8) { pos += 2; continue }          // SOI
            if (marker == 0xD9) break                           // EOI（异常，放弃）
            if (marker in 0xD0..0xD7) { pos += 2; continue }    // RST
            val segLen = u16(baseJpeg, pos + 2)
            if (pos + 2 + segLen > baseJpeg.size) break
            if (marker == 0xDA) {                               // SOS：先插 MPF
                val posBeforeMpf = out.size()
                val restLen = baseJpeg.size - pos               // SOS..EOI
                val primarySize = posBeforeMpf + 4 + MPF_PAYLOAD_SIZE + restLen
                // MPF 偏移基准 = MPF 大端字节序字段起始位置（即段标记位置 + 8）
                val secondaryOffset = primarySize - posBeforeMpf - 8
                out.write(0xFF); out.write(0xE2)
                out.write(0x00); out.write(0x58)                // 段长 = 2 + 86
                out.write(buildMpfPayload(primarySize, secondarySize, secondaryOffset))
                out.write(baseJpeg, pos, restLen)
                mpfWritten = true
                break
            }
            out.write(baseJpeg, pos, 2 + segLen)
            pos += 2 + segLen
        }
        if (!mpfWritten) error("无法解析基础 JPEG 流")

        // 次图（增益图）：XMP + ISO 21496-1 元数据 + 增益 JPEG（去 SOI）
        out.write(0xFF); out.write(0xD8)
        out.write(xmpSecondarySegment, 0, xmpSecondarySegment.size)
        out.write(isoSecondarySegment, 0, isoSecondarySegment.size)
        out.write(gmJpeg, 2, gmJpeg.size - 2)
        return out.toByteArray()
    }

    private fun compressToJpeg(bmp: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { out ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                error("JPEG 编码失败")
            }
        }.toByteArray()

    /** MPF（Multi-Picture Format）APP2 载荷，86 字节，大端。与 libultrahdr generateMpf 字节一致 */
    private fun buildMpfPayload(
        primarySize: Int,
        secondarySize: Int,
        secondaryOffset: Int,
    ): ByteArray {
        val b = ByteArray(MPF_PAYLOAD_SIZE)
        var i = 0
        fun u16(v: Int) {
            b[i++] = (v ushr 8).toByte(); b[i++] = v.toByte()
        }
        fun u32(v: Int) {
            b[i++] = (v ushr 24).toByte(); b[i++] = (v ushr 16).toByte()
            b[i++] = (v ushr 8).toByte(); b[i++] = v.toByte()
        }
        fun raw(vararg vs: Int) {
            for (v in vs) b[i++] = v.toByte()
        }
        raw(0x4D, 0x50, 0x46, 0x00)              // "MPF\0"
        raw(0x4D, 0x4D, 0x00, 0x2A)              // 大端 "MM\0*"
        u32(8)                                    // Index IFD 偏移
        u16(3)                                    // 标签数
        u16(0xB000); u16(7); u32(4)               // MPFVersion, Undefined, "0100"
        raw(0x30, 0x31, 0x30, 0x30)
        u16(0xB001); u16(4); u32(1); u32(2)       // NumberOfImages = 2
        u16(0xB002); u16(7); u32(32); u32(50)     // MPEntry, Undefined, 32 字节
        // 偏移 50 = 相对 Endianness 字段（等效于 libultrahdr 公式 46-4+4+4），指向 MP 条目
        u32(0)                                    // Attribute IFD 偏移 = 0
        u32(0x00030000)                           // 主图：JPEG | Primary
        u32(primarySize); u32(0); u16(0); u16(0)
        u32(0x00000000)                           // 次图：JPEG
        u32(secondarySize); u32(secondaryOffset); u16(0); u16(0)
        return b
    }

    private fun buildXmpApp1(packet: String): ByteArray {
        val payload = packet.toByteArray(Charsets.UTF_8)
        val len = 2 + XMP_NAMESPACE.size + payload.size
        val out = ByteArray(2 + len)
        out[0] = 0xFF.toByte(); out[1] = 0xE1.toByte()
        out[2] = (len ushr 8).toByte(); out[3] = len.toByte()
        XMP_NAMESPACE.copyInto(out, 4)
        payload.copyInto(out, 4 + XMP_NAMESPACE.size)
        return out
    }

    /** 通用 APP2 段：命名空间 + 可选载荷 */
    private fun buildApp2(namespace: ByteArray, payload: ByteArray?): ByteArray {
        val len = 2 + namespace.size + (payload?.size ?: 0)
        val out = java.io.ByteArrayOutputStream(2 + len)
        out.write(0xFF); out.write(0xE2)
        out.write((len ushr 8) and 0xFF); out.write(len and 0xFF)
        out.write(namespace, 0, namespace.size)
        payload?.let { out.write(it, 0, it.size) }
        return out.toByteArray()
    }

    /**
     * ISO 21496-1 增益图元数据载荷（单通道，全大端）。
     * 所有值存为有理数；GainMapMin/Max/头room 存 log2 空间值，与 XMP 语义一致：
     * boost = headroom ^ (stored ^ (1/gamma))。
     */
    private fun buildIsoMetadataPayload(p: HdrParams): ByteArray {
        val b = java.io.ByteArrayOutputStream(64)
        fun u8(v: Int) = b.write(v and 0xFF)
        fun u16(v: Int) {
            b.write((v ushr 8) and 0xFF); b.write(v and 0xFF)
        }
        fun u32(v: Long) {
            b.write(((v ushr 24) and 0xFF).toInt())
            b.write(((v ushr 16) and 0xFF).toInt())
            b.write(((v ushr 8) and 0xFF).toInt())
            b.write((v and 0xFF).toInt())
        }
        // 有理数转换：放大 1e6 后约分（补码即大端原码，有符号无符号共用 u32）
        fun frac(v: Float, signed: Boolean) {
            val scaled = kotlin.math.round(v.toDouble() * 1_000_000.0).toLong()
            if (scaled == 0L) {
                u32(0); u32(1); return
            }
            val g = gcd(kotlin.math.abs(scaled), 1_000_000L)
            val n = scaled / g
            val d = 1_000_000L / g
            u32(if (signed) n else maxOf(n, 0L))
            u32(d)
        }

        val headroom = p.headroom.coerceIn(1f, 16f)
        val log2Max = log2(headroom)
        val gamma = p.gainGamma.coerceIn(0.1f, 4f)

        u16(0)                  // minimum_version
        u16(0)                  // writer_version
        u8(0)                   // flags: 单通道，不使用 base 色彩空间
        frac(0f, false)         // baseHdrHeadroom = log2(1)
        frac(log2Max, false)    // alternateHdrHeadroom = log2(headroom)
        frac(0f, true)          // gainMapMin = log2(1)
        frac(log2Max, true)     // gainMapMax = log2(headroom)
        frac(gamma, false)      // gamma
        frac(GAIN_OFFSET, true) // baseOffset = 1/64
        frac(GAIN_OFFSET, true) // alternateOffset = 1/64
        return b.toByteArray()
    }

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /**
     * 主图 XMP：GContainer 目录声明 Primary 与 GainMap 两个子项及次图长度，
     * 并携带 hdrgm:Version（解析端要求必须存在）。
     */
    private fun buildPrimaryXmp(secondaryLength: Int): String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description" +
            " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"" +
            " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"" +
            " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"" +
            " hdrgm:Version=\"1.0\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\">" +
            "<Item:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/>" +
            "</rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\">" +
            "<Item:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\"" +
            " Item:Length=\"$secondaryLength\"/>" +
            "</rdf:li>" +
            "</rdf:Seq></Container:Directory>" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"

    /** 次图 XMP：增益元数据。GainMapMin/Max 与 HDRCapacity 为 log2 空间值 */
    private fun buildSecondaryXmp(p: HdrParams): String {
        val headroom = p.headroom.coerceIn(1f, 16f)
        val gamma = p.gainGamma.coerceIn(0.1f, 4f)
        val log2Max = fmt(log2(headroom))
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"" +
            " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"" +
            " hdrgm:Version=\"1.0\"" +
            " hdrgm:GainMapMin=\"0\"" +
            " hdrgm:GainMapMax=\"$log2Max\"" +
            " hdrgm:Gamma=\"${fmt(gamma)}\"" +
            " hdrgm:OffsetSDR=\"${fmt(GAIN_OFFSET)}\"" +
            " hdrgm:OffsetHDR=\"${fmt(GAIN_OFFSET)}\"" +
            " hdrgm:HDRCapacityMin=\"0\"" +
            " hdrgm:HDRCapacityMax=\"$log2Max\"" +
            " hdrgm:BaseRenditionIsHDR=\"False\"/>" +
            "</rdf:RDF></x:xmpmeta>"
    }

    /** 浮点格式化：整数不带小数点，其余最多 6 位 */
    private fun fmt(v: Float): String {
        val r = kotlin.math.round(v * 1e6f) / 1e6f
        return if (r == kotlin.math.round(r)) r.toInt().toString() else r.toString()
    }

    private fun u16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

    // ---------------------------------------------------------------- saving

    private fun saveJpeg(
        cr: android.content.ContentResolver,
        bytes: ByteArray,
        displayName: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$SUB_DIR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        ) ?: error("无法创建输出文件")
        try {
            cr.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: error("无法写入输出文件")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        return "$EXPORT_DISPLAY_PATH/$displayName"
    }
}
