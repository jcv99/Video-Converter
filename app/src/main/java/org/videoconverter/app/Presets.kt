package org.videoconverter.app

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversionPreset(
    val name: String,
    val target: AudioTarget,
    val bitrate: Int,
    val trimSilence: Boolean,
    val preserveFolders: Boolean,
    val loudness: Loudness,
    val destination: Uri?,
)

internal class PresetStore(private val preferences: SharedPreferences) {
    fun load(): List<ConversionPreset> {
        val array = runCatching { JSONArray(preferences.getString("presets", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").takeIf(String::isNotBlank) ?: return@mapNotNull null
            ConversionPreset(name, AudioTarget.fromName(entry.optString("target")), entry.optInt("bitrate", 192),
                entry.optBoolean("trimSilence"), entry.optBoolean("preserveFolders", true),
                Loudness.fromLufs(entry.optInt("loudness")),
                entry.optString("destination").takeIf(String::isNotBlank)?.let(Uri::parse))
        }
    }

    fun save(presets: List<ConversionPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject()
                .put("name", preset.name)
                .put("target", preset.target.name)
                .put("bitrate", preset.bitrate)
                .put("trimSilence", preset.trimSilence)
                .put("preserveFolders", preset.preserveFolders)
                .put("loudness", preset.loudness.lufs)
                .put("destination", preset.destination?.toString()))
        }
        preferences.edit { putString("presets", array.toString()) }
    }
}
