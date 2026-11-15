package org.videoconverter.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal enum class Status { QUEUED, CONVERTING, SAVED, FAILED, INTERRUPTED }

internal enum class AudioTarget(val label: String, val extension: String, val mime: String, val nativeId: Int) {
    MP3("MP3", "mp3", "audio/mpeg", 0),
    AAC("AAC (M4A)", "m4a", "audio/mp4", 1),
    ALAC("ALAC (M4A)", "m4a", "audio/mp4", 2),
    OPUS("Opus (Ogg)", "ogg", "audio/ogg", 3),
    VORBIS("Vorbis (Ogg)", "ogg", "audio/ogg", 4),
    FLAC("FLAC", "flac", "audio/flac", 5),
    WAV("PCM 16-bit (WAV)", "wav", "audio/wav", 6);

    companion object {
        fun fromName(name: String?): AudioTarget = entries.firstOrNull { it.name == name } ?: MP3
    }
}

internal enum class Loudness(val lufs: Int, val label: String) {
    OFF(0, "Off"), LOUD(-14, "−14 LUFS"), STANDARD(-16, "−16 LUFS"), QUIET(-23, "−23 LUFS");

    companion object {
        fun fromLufs(value: Int) = entries.firstOrNull { it.lufs == value } ?: OFF
    }
}

internal data class InputFile(val uri: Uri, val name: String, val directories: List<String> = emptyList(), val staged: String? = null)
internal data class OutputLocation(
    val parent: Uri?, val path: String?, val name: String, val allocatedName: String,
)

internal data class OutputWrite(val location: OutputLocation, val size: Long, val digest: String, val ready: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("parent", location.parent?.toString())
        put("path", location.path)
        put("name", location.name)
        put("allocatedName", location.allocatedName)
        put("size", size)
        put("digest", digest)
        put("ready", ready)
    }

    companion object {
        fun fromJson(json: JSONObject): OutputWrite = OutputWrite(
            OutputLocation(json.optString("parent").takeIf(String::isNotEmpty)?.let(Uri::parse),
                json.optString("path").takeIf(String::isNotEmpty), json.getString("name"), json.getString("allocatedName")),
            json.getLong("size"), json.getString("digest"), json.optBoolean("ready"),
        )
    }
}

internal data class OutputCleanup(val uri: Uri?, val write: OutputWrite?)

internal data class Item(
    val input: InputFile,
    val status: Status = Status.QUEUED,
    val detail: String = "",
    val output: Uri? = null,
    val pending: Uri? = null,
    val write: OutputWrite? = null,
    val inputUnavailable: Boolean = false,
)
internal data class Batch(
    val items: List<Item> = emptyList(),
    val active: Boolean = false,
    val target: AudioTarget = AudioTarget.MP3,
    val bitrate: Int = 192,
    val trimSilence: Boolean = false,
    val legacyVideo: Boolean = false,
    val destination: Uri? = null,
    val downloadPath: String? = null,
    val preserveFolders: Boolean = true,
    val loudness: Loudness = Loudness.OFF,
    val draftId: String? = null,
    val cleanup: List<OutputCleanup> = emptyList(),
) {
    val formatLabel get() = if (legacyVideo) "MP4" else target.label
    val mime get() = if (legacyVideo) "video/mp4" else target.mime
    val retryableItems get() = if (legacyVideo) emptyList() else
        items.filter { it.status == Status.FAILED || it.status == Status.INTERRUPTED }
    val activeCount get() = items.count { it.status == Status.CONVERTING }
    val completedCount get() = items.count { it.status == Status.SAVED || it.status == Status.FAILED }
}

// The native converter reports -1 while the duration of a file is unknown.
// Unknown work never counts as a percentage, and the batch stays indeterminate
// until a worker reports a known percentage or an item reaches a terminal state.
internal fun batchProgress(total: Int, completed: Int, active: Int, reported: Collection<Int>): Int {
    if (total <= 0) return -1
    val known = reported.filter { it >= 0 }
    if (active > 0 && completed == 0 && known.isEmpty()) return -1
    return ((completed * 100L + known.sumOf { it.toLong() }) / total).toInt().coerceIn(0, 100)
}

internal object BatchStore {    private lateinit var journal: AtomicFile
    private val state = MutableStateFlow(Batch())
    private var recoveryNeeded = false
    private var journalUnreadable = false
    val recoveryError = MutableStateFlow<String?>(null)
    val batch = state.asStateFlow()
    val progress = MutableStateFlow(-1)

    fun spoolDirectory(context: Context) = File(context.filesDir, "conversion-inputs")

    @Synchronized
    fun pruneSpool(context: Context) {
        val retained = state.value.items.mapNotNull { it.input.staged }.toSet()
        spoolDirectory(context).listFiles()?.forEach { if (it.name !in retained) it.delete() }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (::journal.isInitialized) {
            if (!journalUnreadable) {
                if (recoveryNeeded) recover(context)
                return
            }
        }
        journal = AtomicFile(File(context.filesDir, "batch.json"))
        val previous = runCatching {
            val contents = journal.readFully().decodeToString()
            val metadata = if (contents.trimStart().startsWith("[")) null else JSONObject(contents)
            val array = metadata?.getJSONArray("items") ?: JSONArray(contents)
            val items = List(array.length()) { position ->
                val json = array.getJSONObject(position)
                Item(
                    InputFile(Uri.parse(json.getString("input")), json.getString("name"),
                        json.optJSONArray("directories")?.let { dirs ->
                            List(dirs.length()) { dirs.getString(it) }
                        }.orEmpty(), json.optString("staged").takeIf(String::isNotEmpty)),
                    Status.valueOf(json.getString("status")),
                    json.optString("detail"),
                    json.optString("output").takeIf(String::isNotEmpty)?.let(Uri::parse),
                    json.optString("pending").takeIf(String::isNotEmpty)?.let(Uri::parse),
                    json.optJSONObject("write")?.let(OutputWrite::fromJson),
                    json.optBoolean("inputUnavailable"),
                )
            }
            Batch(items, target = AudioTarget.fromName(metadata?.optString("target")),
                bitrate = metadata?.optInt("bitrate", 192) ?: 192,
                trimSilence = metadata?.optBoolean("trimSilence") ?: false,
                legacyVideo = metadata?.let { it.optBoolean("legacyVideo") ||
                    (!it.isNull("videoPreset") && it.optString("videoPreset").isNotBlank()) } == true,
                destination = metadata?.optString("destination")?.takeIf(String::isNotEmpty)?.let(Uri::parse),
                downloadPath = metadata?.optString("downloadPath")?.takeIf(String::isNotEmpty),
                preserveFolders = metadata?.optBoolean("preserveFolders", true) ?: true,
                loudness = Loudness.fromLufs(metadata?.optInt("loudness") ?: 0),
                draftId = metadata?.optString("draftId")?.takeIf(String::isNotEmpty),
                cleanup = metadata?.optJSONArray("cleanup")?.let { entries ->
                    List(entries.length()) { index ->
                        val entry = entries.getJSONObject(index)
                        OutputCleanup(entry.optString("uri").takeIf(String::isNotEmpty)?.let(Uri::parse),
                            entry.optJSONObject("write")?.let(OutputWrite::fromJson))
                    }
                }.orEmpty())
        }.getOrElse { error ->
            if (error !is java.io.FileNotFoundException || journal.baseFile.exists()) {
                journalUnreadable = true
                recoveryError.value = "Could not read the conversion journal. Check internal storage."
                return
            }
            Batch()
        }
        journalUnreadable = false
        recoveryError.value = null
        state.value = previous
        recoveryNeeded = previous.items.any {
            it.status == Status.CONVERTING || it.status == Status.QUEUED || it.pending != null || it.write != null
        } || previous.cleanup.isNotEmpty()
        if (recoveryNeeded) recover(context)
        pruneSpool(context)
        File(context.cacheDir, "conversion").deleteRecursively()
        File(context.cacheDir, "video-scratch").deleteRecursively()
    }

    @Synchronized
    fun prepare(context: Context) {
        initialize(context)
        if (!journalUnreadable && !recoveryNeeded && (state.value.cleanup.isNotEmpty() ||
                state.value.items.any { it.pending != null || it.write != null })) recover(context)
        if (recoveryError.value != null) throw java.io.IOException(requireNotNull(recoveryError.value))
    }

    @Synchronized
    fun requireRecovery() {
        recoveryNeeded = true
        recoveryError.value = "Could not save conversion state. Free internal storage and retry recovery."
    }

    private fun recover(context: Context) {
        recoveryNeeded = true
        val previous = state.value
        val items = previous.items.map { item ->
            val interrupted = if (item.status == Status.CONVERTING || item.status == Status.QUEUED) {
                item.copy(status = Status.INTERRUPTED, detail = "Conversion interrupted. Start again to retry.")
            } else item
            reconcile(context, interrupted)
        }
        val cleanup = previous.cleanup.filterNot { record ->
            val recoveredItem = reconcile(context, Item(InputFile(Uri.EMPTY, ""), pending = record.uri, write = record.write))
            recoveredItem.pending == null && recoveredItem.write == null
        }
        val recovered = previous.copy(active = false, items = items, cleanup = cleanup)
        try {
            if (recovered != previous) save(recovered)
            recoveryNeeded = false
            recoveryError.value = null
        } catch (_: Exception) {
            recoveryError.value = "Could not save recovered conversions. Free internal storage and retry."
        }
    }

    private fun reconcile(context: Context, item: Item): Item {
        val write = item.write
        var uri = item.pending
        try {
            val location = write?.location
            val parent = location?.parent
            if (parent != null && location.allocatedName != location.name) {
                val provisional = Storage.findDocument(context, parent, location.allocatedName)
                if (provisional == null && write.ready) {
                    val published = Storage.findDocument(context, parent, location.name)
                    if (published != null && Storage.verifyOutput(context, published, write)) {
                        return savedItem(item, published)
                    }
                    return item.copy(detail = "Check the output folder. A finished file could not be located.")
                }
                uri = provisional
            } else if (uri != null && Storage.outputMissing(context, uri)) {
                return item.copy(pending = null, write = null)
            }

            if (write == null) {
                if (uri != null && uri.authority == android.provider.MediaStore.AUTHORITY) {
                    if (Storage.isPublishedDownload(context, uri)) return savedItem(item, uri)
                    if (Storage.deleteOutput(context, uri)) return item.copy(pending = null)
                }
                return item
            }
            if (uri == null) {
                if (parent != null) {
                    if (location.allocatedName != location.name) return item.copy(pending = null, write = null)
                    return item.copy(detail = "An output may have been created. Check the selected folder before retrying.")
                }
                uri = Storage.findReservedDownload(context, write.location)
                if (uri == null) return item.copy(write = null)
            }
            if (write.ready || Storage.verifyOutput(context, uri, write)) {
                return savedItem(item, Storage.publishOutput(context, uri, write))
            }
            if (Storage.deleteOutput(context, uri)) return item.copy(pending = null, write = null)
        } catch (error: Exception) {
            android.util.Log.w("VideoConverter", "Output recovery failed", error)
            return item.copy(pending = uri, detail = "Could not check the interrupted output. Restore folder access and retry.")
        }
        return item.copy(pending = uri)
    }

    fun savedItem(item: Item, uri: Uri) = item.copy(input = item.input.copy(staged = null),
        status = Status.SAVED, detail = "", output = uri, pending = null, write = null, inputUnavailable = false)

    @Synchronized
    fun save(batch: Batch) {
        val array = JSONArray()
        batch.items.forEach { item ->
            array.put(JSONObject().apply {
                put("input", item.input.uri.toString())
                put("name", item.input.name)
                put("directories", JSONArray(item.input.directories))
                put("staged", item.input.staged)
                put("status", item.status.name)
                put("detail", item.detail)
                put("output", item.output?.toString().orEmpty())
                put("pending", item.pending?.toString().orEmpty())
                put("write", item.write?.toJson())
                put("inputUnavailable", item.inputUnavailable)
            })
        }
        val json = JSONObject()
            .put("items", array)
            .put("target", batch.target.name)
            .put("bitrate", batch.bitrate)
            .put("trimSilence", batch.trimSilence)
            .put("downloadPath", batch.downloadPath)
            .put("preserveFolders", batch.preserveFolders)
            .put("loudness", batch.loudness.lufs)
            .put("draftId", batch.draftId)
            .put("cleanup", JSONArray().apply {
                batch.cleanup.forEach { record ->
                    put(JSONObject().put("uri", record.uri?.toString()).put("write", record.write?.toJson()))
                }
            })
            .putOpt("legacyVideo", true.takeIf { batch.legacyVideo })
            .put("destination", batch.destination?.toString())
        journal.baseFile.writeAtomically(json.toString().encodeToByteArray())
        state.value = batch
    }

    @Synchronized
    fun update(position: Int, transform: (Item) -> Item) {
        val current = state.value
        val items = current.items.toMutableList()
        items[position] = transform(items[position])
        save(current.copy(items = items))
    }
}

internal object NativeConverter {
    init { System.loadLibrary("converter") }

    fun interface Progress {
        fun update(percent: Int): Boolean
    }

    fun encode(fd: Int, output: String, target: Int, bitrate: Int, trimSilence: Boolean, progress: Progress): Int =
        encodeAudio(fd, output, target, bitrate, trimSilence, 0, progress)

    external fun encodeAudio(
        fd: Int, output: String, target: Int, bitrate: Int, trimSilence: Boolean, loudness: Int, progress: Progress,
    ): Int
    external fun canConvert(fd: Int, progress: Progress): Boolean
    external fun setNonblocking(fd: Int): Int
}
