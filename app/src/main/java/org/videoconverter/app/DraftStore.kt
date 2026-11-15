package org.videoconverter.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.system.ErrnoException
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal object DraftStore {
    private fun file(context: Context, id: String): File = File(File(context.filesDir, "drafts"), id)

    private fun activeFile(context: Context) = File(context.filesDir, "active-draft")
    fun active(context: Context): String? = runCatching { activeFile(context).readText() }
        .getOrNull()?.takeIf { it.matches(Regex("[0-9a-f-]{36}")) }

    fun setActive(context: Context, id: String) {
        activeFile(context).writeAtomically(id.encodeToByteArray())
    }

    fun save(context: Context, id: String, files: List<InputFile>) {
        if (files.isEmpty()) return
        val target = file(context, id)
        val directory = requireNotNull(target.parentFile)
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create draft storage.")
        val entries = JSONArray()
        for (file in files) {
            entries.put(JSONObject().put("uri", file.uri.toString()).put("name", file.name)
                .put("directories", JSONArray(file.directories)))
        }
        target.writeAtomically(entries.toString().encodeToByteArray())
    }

    fun load(context: Context, id: String): List<InputFile> = runCatching {
        val entries = JSONArray(AtomicFile(file(context, id)).readFully().decodeToString())
        List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val directories = entry.getJSONArray("directories")
            InputFile(Uri.parse(entry.getString("uri")), entry.getString("name"),
                List(directories.length()) { directories.getString(it) })
        }
    }.getOrDefault(emptyList())

    fun remove(context: Context, id: String) {
        AtomicFile(file(context, id)).delete()
    }
}


internal fun File.writeAtomically(bytes: ByteArray) {
    val temporary = File(parentFile, "$name.pending-write")
    try {
        temporary.outputStream().use {
            it.write(bytes)
            it.fd.sync()
        }
        try {
            Os.rename(temporary.path, path)
        } catch (error: ErrnoException) {
            throw IOException("Could not replace $name.", error)
        }
    } finally {
        temporary.delete()
    }
}
