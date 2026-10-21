package com.glowhdr.app.data

import android.content.Context
import com.glowhdr.app.hdr.Preset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 预设持久化：filesDir/presets.json */
class PresetStore(context: Context) {

    private val file = File(context.filesDir, "presets.json")

    fun load(): List<Preset> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Preset.fromJson(it) }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(presets: List<Preset>) {
        val arr = JSONArray()
        presets.forEach { arr.put(JSONObject(it.toJson())) }
        file.writeText(arr.toString())
    }
}
